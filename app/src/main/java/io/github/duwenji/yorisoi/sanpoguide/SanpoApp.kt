package io.github.duwenji.yorisoi.sanpoguide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkCompanion
import io.github.duwenji.yorisoi.sanpoguide.companion.CompanionFeed
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherClient
import io.github.duwenji.yorisoi.kit.geo.map.GoogleMapTiles
import io.github.duwenji.yorisoi.kit.geo.OverpassClient
import io.github.duwenji.yorisoi.kit.geo.RouteClient
import io.github.duwenji.yorisoi.kit.geo.SpotPhotos
import io.github.duwenji.yorisoi.kit.geo.SpotRepository
import io.github.duwenji.yorisoi.sanpoguide.guide.GuideRepository
import io.github.duwenji.yorisoi.kit.voice.Speaker
import io.github.duwenji.yorisoi.sanpoguide.history.HistoryStore
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
import io.github.duwenji.yorisoi.sanpoguide.station.Station
import io.github.duwenji.yorisoi.sanpoguide.station.StationRepository
import io.github.duwenji.yorisoi.sanpoguide.station.remote.ChannelSync
import io.github.duwenji.yorisoi.core.format.CueEvent
import io.github.duwenji.yorisoi.sanpoguide.station.remote.ThirdPartyChannels
import io.github.duwenji.yorisoi.sanpoguide.walk.CueShow
import io.github.duwenji.yorisoi.sanpoguide.walk.WalkService
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
import org.osmdroid.config.Configuration

class SanpoApp : Application() {
    /** For work that must outlive a screen or the walk service, such as the end-of-walk summary. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var prompts: PromptTemplates
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var spots: SpotRepository
        private set
    lateinit var stations: StationRepository
        private set
    /** Third parties' channels from the providers the user added (ADR-001 T-5). */
    lateinit var channels: ThirdPartyChannels
        private set
    lateinit var guides: GuideRepository
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var companion: WalkCompanion
        private set
    /** The material the walk and the screens use, from the producers (ADR-002). */
    lateinit var narration: Narration
        private set

    /** A third party's cues: which fire now, and what they show (ADR-002 T-8b). */
    lateinit var cueFinder: CueFinder
        private set
    lateinit var cues: CuePresenter
        private set
    /** Shows what a cue came to: speech, cards, notifications, and what opens by a tap (T-8c). */
    lateinit var cueShow: CueShow
        private set
    lateinit var mood: MoodSource
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
        // The channel decides which shops to search for; read when searching, after start-up.
        spots = SpotRepository(OverpassClient(), shops = { stations.current.value.searchedKinds })
        prompts = Prompts.fromResources()
        val developerMode = settings.settings.map { it.developerMode }.stateIn(appScope, SharingStarted.Eagerly, settings.settings.value.developerMode)
        // Only the coarse region (3-letter geohashes, about 150km) leaves the location (API-004 P-6).
        channels = ThirdPartyChannels(this, BuiltInStations.standardPackage(), appScope, developerMode) {
            spots.location.value?.let { Regions.around(it.latitude, it.longitude) }
        }
        stations = StationRepository(BuiltInStations.load(), settings.settings, appScope, channels.stations)
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
        guides = GuideRepository(settings, prompts)
        speaker = Speaker(this)
        photos = SpotPhotos(this)
        history = HistoryStore(this)
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
            )
        )
        narration = Narration(producers, station = { stations.current.value })
        cueFinder = CueFinder(FileCueMemory(java.io.File(filesDir, "cues.json")))
        cues = CuePresenter(producers, ChannelSync.MEDIA_STAGE, hidePromotions = { settings.settings.value.hidePromotions })
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

    companion object {
        // A chosen channel becomes the current one at once, unless its package is still being read.
        private const val STATION_WAIT_MS = 10_000L
        const val CHANNEL_CUE = "cue"
        const val CHANNEL_WALK = "walk"
        const val CHANNEL_SPOT = "spot"
        const val CHANNEL_ALERT = "weather_alert"
    }
}
