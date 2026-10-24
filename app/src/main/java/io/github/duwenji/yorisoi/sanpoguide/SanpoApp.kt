package io.github.duwenji.yorisoi.sanpoguide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkCompanion
import io.github.duwenji.yorisoi.sanpoguide.companion.CompanionFeed
import io.github.duwenji.yorisoi.sanpoguide.diagnostics.DiagnosticFields
import io.github.duwenji.yorisoi.sanpoguide.diagnostics.TimingInterceptor
import io.github.duwenji.yorisoi.sanpoguide.diagnostics.WalkDiagnostics
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherClient
import io.github.duwenji.yorisoi.kit.geo.map.GoogleMapTiles
import io.github.duwenji.yorisoi.kit.geo.OverpassClient
import io.github.duwenji.yorisoi.kit.geo.RouteClient
import io.github.duwenji.yorisoi.kit.geo.SpotCache
import io.github.duwenji.yorisoi.kit.geo.SpotPhotos
import io.github.duwenji.yorisoi.kit.geo.SpotRepository
import io.github.duwenji.yorisoi.sanpoguide.guide.GuideRepository
import io.github.duwenji.yorisoi.kit.llm.AiUsage
import io.github.duwenji.yorisoi.kit.llm.OperatorAi
import io.github.duwenji.yorisoi.kit.llm.RelayClient
import io.github.duwenji.yorisoi.kit.voice.Speaker
import io.github.duwenji.yorisoi.sanpoguide.history.HistoryStore
import io.github.duwenji.yorisoi.sanpoguide.walk.ActiveWalkPlanStore
import io.github.duwenji.yorisoi.sanpoguide.material.AiCompanionProducer
import io.github.duwenji.yorisoi.sanpoguide.material.AiGuideProducer
import io.github.duwenji.yorisoi.sanpoguide.material.AmbientProducer
import io.github.duwenji.yorisoi.sanpoguide.material.CommonsPhotoProducer
import io.github.duwenji.yorisoi.sanpoguide.material.CueFinder
import io.github.duwenji.yorisoi.sanpoguide.material.CueLayout
import io.github.duwenji.yorisoi.sanpoguide.material.CuePresenter
import io.github.duwenji.yorisoi.sanpoguide.material.FileCueMemory
import io.github.duwenji.yorisoi.sanpoguide.material.FallbackTextProducer
import io.github.duwenji.yorisoi.sanpoguide.material.MaterialContext
import io.github.duwenji.yorisoi.sanpoguide.material.MoodSceneProducer
import io.github.duwenji.yorisoi.sanpoguide.material.Narration
import io.github.duwenji.yorisoi.sanpoguide.material.Producers
import io.github.duwenji.yorisoi.core.format.material.Regions
import io.github.duwenji.yorisoi.sanpoguide.material.Subject
import io.github.duwenji.yorisoi.kit.mood.MoodSource
import io.github.duwenji.yorisoi.core.prompt.PromptTemplates
import io.github.duwenji.yorisoi.sanpoguide.prompt.Prompts
import io.github.duwenji.yorisoi.sanpoguide.station.BuiltInStations
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.channel.StationRepository
import io.github.duwenji.yorisoi.sanpoguide.station.remote.ChannelSync
import io.github.duwenji.yorisoi.core.format.CueEvent
import io.github.duwenji.yorisoi.sanpoguide.station.remote.ThirdPartyChannels
import io.github.duwenji.yorisoi.sanpoguide.walk.CueShow
import io.github.duwenji.yorisoi.sanpoguide.walk.WalkService
import io.github.duwenji.yorisoi.sanpoguide.report.Report
import io.github.duwenji.yorisoi.sanpoguide.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration
import java.io.File
import java.util.concurrent.TimeUnit

class SanpoApp : Application() {
    /** For work that must outlive a screen or the walk service, such as the end-of-walk summary. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var prompts: PromptTemplates
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var spots: SpotRepository

    /** Spot searches kept on the device; they tell where the user walked, so cleared with the history. */
    lateinit var spotCache: SpotCache
        private set
    lateinit var stations: StationRepository
        private set
    /** Third parties' channels from the providers the user added. */
    lateinit var channels: ThirdPartyChannels
        private set
    /** Today's AI calls against the user's limit (platform ADR-004 H-9). */
    lateinit var aiUsage: AiUsage
        private set
    /** The operator's AI (platform ADR-AI-RELAY); null in a build without a relay. */
    var relay: RelayClient? = null
        private set
    lateinit var guides: GuideRepository
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var activeWalkPlan: ActiveWalkPlanStore
        private set
    lateinit var companion: WalkCompanion
        private set
    /** The material the walk and the screens use, from the producers (ADR-002). */
    lateinit var narration: Narration
        private set

    /** A third party's cues: which fire now, and what they show. */
    lateinit var cueFinder: CueFinder
        private set
    lateinit var cues: CuePresenter
        private set
    /** Shows what a cue came to: speech, cards, notifications, and what opens by a tap. */
    lateinit var cueShow: CueShow
        private set
    lateinit var mood: MoodSource
        private set
    /** The developer's detailed record of each walk taken in developer mode. */
    lateinit var diagnostics: WalkDiagnostics
        private set
    lateinit var photos: SpotPhotos
        private set
    val weather = WeatherClient()
    val routes = RouteClient()
    val googleTiles = GoogleMapTiles()
    val feed = CompanionFeed()

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = "SanpoGuide/${BuildConfig.VERSION_NAME} (Android)"

        settings = SettingsRepository(this)
        diagnostics = WalkDiagnostics(File(filesDir, "diagnostics"))
        // The channel decides which shops to search for; read when searching, after start-up.
        spotCache = SpotCache(File(cacheDir, "spots"))
        spots = SpotRepository(
            OverpassClient(overpassHttp()),
            shops = { stations.current.value.searchedKinds },
            cache = spotCache,
            useCache = { settings.settings.value.spotCacheEnabled },
            freshMs = { TimeUnit.DAYS.toMillis(settings.settings.value.spotCacheFreshDays.toLong()) },
        )
        prompts = Prompts.fromResources()
        val developerMode = settings.settings.map { it.developerMode }.stateIn(appScope, SharingStarted.Eagerly, settings.settings.value.developerMode)
        // Only the coarse region (3-letter geohashes, about 150km) leaves the location (API-004 P-6).
        channels = ThirdPartyChannels(this, BuiltInStations.standardPackage(), appScope, developerMode) {
            spots.location.value?.let { Regions.around(it.latitude, it.longitude) }
        }
        val choice = settings.settings.map { it.channelChoice }.stateIn(appScope, SharingStarted.Eagerly, settings.settings.value.channelChoice)
        stations = StationRepository(BuiltInStations.load(), BuiltInStations.STANDARD, choice, appScope, channels.stations)
        // Each line remembers the channel it was said on, for a report (platform ADR-004 H-11).
        feed.channel = { Report.target(stations.current.value) }
        channels.refreshIfDue()
        appScope.launch {
            // A switch to or from a channel that prefers shops shows them, or clears them, right away.
            stations.current.map { it.searchedKinds }.distinctUntilChanged().drop(1).collect {
                try {
                    spots.onShopsChanged()
                } catch (e: Exception) {
                    Log.w("SanpoApp", "Spot search after a channel switch failed", e)
                }
            }
        }
        aiUsage = AiUsage.of(this)
        relay = OperatorAi.create(this, BuildConfig.AI_RELAY_URL, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
        guides = GuideRepository(settings, prompts, aiUsage, relay)
        guides.onCall = { call ->
            diagnostics.event(
                "ai", "kind" to call.kind, "model" to call.model, "ms" to call.ms,
                "system" to call.system, "user" to call.user, "reply" to call.reply,
                "error" to call.error, "violation" to call.violation,
            )
        }
        speaker = Speaker(this)
        photos = SpotPhotos(this)
        history = HistoryStore(this)
        activeWalkPlan = ActiveWalkPlanStore(File(filesDir, "active_walk_plan.json"))
        mood = MoodSource(spots, feed.weather, appScope)
        companion = WalkCompanion(
            guides, history, prompts,
            mood = { mood.mood.value.takeIf { stations.current.value.moodTone } },
            shareLocation = { settings.settings.value.shareLocationWithAi },
        )
        val producers = Producers(
            listOf(
                AiGuideProducer(guides, settings),
                AiCompanionProducer(companion, settings),
                FallbackTextProducer(prompts, companion),
                CommonsPhotoProducer(photos, settings),
                AmbientProducer(),
                MoodSceneProducer(),
            ),
            log = { message, e ->
                Log.w("Producers", message, e)
                diagnostics.event("producer", "message" to message, "error" to e?.toString())
            },
        )
        narration = Narration(producers, station = { stations.current.value }, mood = { mood.mood.value })
        cueFinder = CueFinder(FileCueMemory(java.io.File(filesDir, "cues.json")))
        cues = CuePresenter(producers, ChannelSync.MEDIA_STAGE)
        cueShow = CueShow(this, feed, speaker)
        appScope.launch {
            // A channel the user chooses outside a walk shows its cues for being chosen (API-003
            // `event: select`); mid-walk, the walk shows them after the channel's greeting.
            settings.settings.map { it.stationId }.distinctUntilChanged().drop(1).collect { id ->
                if (WalkService.walking.value) return@collect
                val station = withTimeoutOrNull(STATION_WAIT_MS) { stations.current.first { it.id == id } } ?: return@collect
                showEventCues(station, CueEvent.SELECT, Subject.None, mutableSetOf(), MaterialContext(station, mood = mood.mood.value))
            }
        }
        appScope.launch { history.load() }

        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(
                NotificationChannel(CHANNEL_CUE, "チャンネルの案内", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "選んだチャンネルが、場所や散歩の始まり・終わりに合わせて出すお知らせです"
                }
            )
            createNotificationChannel(
                NotificationChannel(CHANNEL_WALK, "散策モード", NotificationManager.IMPORTANCE_LOW)
            )
            createNotificationChannel(
                NotificationChannel(CHANNEL_SPOT, "スポット案内", NotificationManager.IMPORTANCE_HIGH)
            )
            // High importance keeps the default sound and vibration.
            createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "雷雨の予報", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "散策中に雷雨が近づいたとき、音で知らせます"
                }
            )
        }
    }

    /**
     * Shows the cues of [station] for [event], each after the other (API-003 `event`), outside
     * the walk's own speech: at the end of a walk, or when a channel is chosen outside one.
     */
    suspend fun showEventCues(station: Station, event: CueEvent, subject: Subject, shownThisWalk: MutableSet<String>, context: MaterialContext) {
        val media = station.materials?.media ?: return
        for (fire in cueFinder.events(station.id, media, event, subject, shownThisWalk, System.currentTimeMillis())) {
            val now = System.currentTimeMillis()
            cueFinder.record(fire, shownThisWalk, now)
            cueShow.show(CueLayout.of("${fire.key}@$now", cues.present(fire, station, context)), station.name, spotName = null)
        }
    }

    /**
     * Overpass's own settings (kit-geo's `OverpassClient` defaults) plus a record of each server
     * tried, so a walk's record shows how often the public servers give up, where each query
     * searched and how much came back.
     */
    private fun overpassHttp(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(40, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            TimingInterceptor({ request, status, error, ms, body ->
                diagnostics.event(
                    "overpass",
                    listOf("host" to request.url.host, "status" to status, "error" to error, "ms" to ms) +
                        DiagnosticFields.overpassQuery(request) +
                        (body?.let(DiagnosticFields::overpassAnswer) ?: emptyList()),
                )
            }),
        )
        .build()

    companion object {
        // A chosen channel becomes the current one at once, unless its package is still being read.
        private const val STATION_WAIT_MS = 10_000L
        const val CHANNEL_CUE = "cue"
        const val CHANNEL_WALK = "walk"
        const val CHANNEL_SPOT = "spot"
        const val CHANNEL_ALERT = "weather_alert"
    }
}
