package io.github.duwenji.sanpoguide.walk

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.duwenji.sanpoguide.R
import io.github.duwenji.sanpoguide.SanpoApp
import io.github.duwenji.sanpoguide.companion.FacilityAdvice
import io.github.duwenji.sanpoguide.companion.FacilityAdvisor
import io.github.duwenji.sanpoguide.companion.SpotCandidate
import io.github.duwenji.sanpoguide.companion.SpotChooser
import io.github.duwenji.sanpoguide.companion.TalkEvent
import io.github.duwenji.sanpoguide.companion.WeatherChangeDetector
import io.github.duwenji.sanpoguide.companion.WeatherChangeKind
import io.github.duwenji.sanpoguide.companion.Utterance
import io.github.duwenji.sanpoguide.companion.WalkSession
import io.github.duwenji.michizure.kit.geo.weather.Weather
import io.github.duwenji.michizure.kit.geo.Poi
import io.github.duwenji.sanpoguide.history.SpotVisit
import io.github.duwenji.sanpoguide.material.Content
import io.github.duwenji.sanpoguide.material.CueFire
import io.github.duwenji.sanpoguide.material.CueLayout
import io.github.duwenji.sanpoguide.material.MaterialContext
import io.github.duwenji.sanpoguide.material.Shown
import io.github.duwenji.sanpoguide.material.Subject
import io.github.duwenji.sanpoguide.settings.Threshold
import io.github.duwenji.michizure.kit.voice.AmbientPlayer
import io.github.duwenji.sanpoguide.station.Station
import io.github.duwenji.michizure.core.format.CueEvent
import io.github.duwenji.michizure.core.format.TalkEventKind
import io.github.duwenji.sanpoguide.ui.MainActivity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Walk mode: tracks the walk in the foreground and lets the companion talk — a greeting,
 * a guide or a "welcome back" at spots, occasional small talk, and a summary at the end.
 * Each walk is saved to the history.
 */
class WalkService : LifecycleService() {
    private val app get() = application as SanpoApp
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var session: WalkSession? = null
    private var lastLocation: Location? = null
    private var weather: Weather? = null
    private var weatherFetchedAt = 0L
    private var greeted = false
    private var ambient: AmbientPlayer? = null

    /** One line at a time; events that arrive while the companion is talking wait for the next tick. */
    private val talking = Mutex()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (session == null) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, walkingNotification("いってらっしゃい。近くに来たらお知らせします"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            // Lists are fetched when a walk starts if a day has passed, never during it (API-002 取得の時期).
            app.channels.refreshIfDue()
            val s = WalkSession()
            s.startStation(app.stations.current.value.key)
            // A channel on trial says so first (API-002 試用チケット).
            app.stations.current.value.takeIf { it.source?.trial == true }?.let { s.pendingGreeting = trialNotice(it) + it.manifest.greeting }
            session = s
            app.feed.startWalk(s)
            startLocationUpdates()
            startTicker()
            startAmbient()
            watchStations()
            _walking.value = true
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        ambient?.release()
        ambient = null
        session?.let(::finishWalk)
        session = null
        _walking.value = false
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // Checked by MainActivity before starting the service.
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    /** Location updates stop while the user stands still, so rests and time-based talk need a clock. */
    private fun startTicker() = lifecycleScope.launch {
        while (isActive) {
            delay(TICK_MS)
            lastLocation?.let { refreshWeather(it) }
            maybeTalk()
        }
    }

    /** Background sound follows the mood and the settings; the player is made on first use. */
    private fun startAmbient() = lifecycleScope.launch {
        combine(app.settings.settings, app.mood.mood, app.stations.current, ::Triple).collect { (settings, mood, station) ->
            val player = ambient ?: if (settings.ambientEnabled) {
                AmbientPlayer(this@WalkService, app.speaker.speaking).also { ambient = it }
            } else {
                return@collect
            }
            player.set(
                if (settings.ambientEnabled) app.narration.ambient(station, mood) else null,
                settings.ambientVolume, settings.ambientEarphonesOnly,
            )
        }
    }

    /**
     * A channel switch mid-walk: recorded, and the new channel introduces itself. Changing only
     * a setting of the same channel (e.g. its talk level) isn't a switch.
     */
    private fun watchStations() = lifecycleScope.launch {
        app.stations.current.collect { station ->
            val s = session ?: return@collect
            if (s.stations.lastOrNull()?.station == station.key) return@collect
            s.switchStation(station.key, trialNotice(station) + station.manifest.greeting)
            s.pendingSelect = true
            maybeTalk()
        }
    }

    private fun trialNotice(station: Station) = if (station.source?.trial == true) "審査前の試用のチャンネルです。" else ""

    private fun onLocation(location: Location) {
        val s = session ?: return
        lastLocation = location
        s.onLocation(location)
        app.feed.update(s)
        lifecycleScope.launch {
            if (!greeted) {
                greeted = true
                refreshWeather(location)
                talk {
                    if (app.stations.current.value.talksOn(TalkEventKind.START)) speakLine(app.narration.line(TalkEvent.Start, s, weather))
                    // The channel's own cues for the start come after the greeting (API-003 `event: start`).
                    eventCues(s, CueEvent.START, Subject.Event(TalkEvent.Start))
                }
            }
            try {
                app.spots.onLocation(location)
            } catch (e: Exception) {
                Log.w(TAG, "Spot search failed", e)
            }
            maybeTalk()
        }
    }

    /** Decides whether anything is worth saying right now, most important first. */
    private suspend fun maybeTalk() {
        val s = session ?: return
        val location = lastLocation ?: return
        // Let the current line finish before starting another.
        if (app.speaker.isSpeaking) return
        val station = app.stations.current.value
        val level = station.talkLevel
        val limits = app.settings.settings.value.thresholds
        val now = System.currentTimeMillis()

        // A turn in the weather comes first, regardless of the talk level: it's about safety.
        val change = weather?.let { WeatherChangeDetector.detect(it, now, limits) }
        val warnedAt = change?.let { s.weatherWarnedAt[it.kind] }
        if (change != null && (warnedAt == null || now - warnedAt >= limits.ms(Threshold.WEATHER_REPEAT_MIN))) {
            talk {
                // A thunderstorm warning also covers the rain that comes with it.
                WeatherChangeKind.entries.filter { it >= change.kind }.forEach { s.weatherWarnedAt[it] = now }
                val line = app.narration.line(TalkEvent.WeatherTurn(change), s, weather)
                // Without earphones the spoken warning goes unheard; lightning is worth a sound.
                if (change.kind == WeatherChangeKind.THUNDER) {
                    getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, alertNotification(line))
                }
                speakLine(line)
            }
            return
        }

        // The user just switched channels: the new one says hello before anything else. No AI, so it's instant.
        s.pendingGreeting?.let { line ->
            talk {
                s.pendingGreeting = null
                speakLine(line)
                // A channel chosen mid-walk shows its cues for being chosen after its greeting (API-003 `event: select`).
                if (s.pendingSelect) {
                    s.pendingSelect = false
                    eventCues(s, CueEvent.SELECT, Subject.None)
                }
            }
            return
        }
        val justSwitched = s.justSwitched(now)

        // While the user stands still, don't run through every spot within reach one by one;
        // the spot they arrived at has been introduced, and a rest remark may be due instead.
        val stationary = s.restMinutes(now) >= STATIONARY_MINUTES
        if (!stationary && !justSwitched && now - s.lastSpotTalkAt >= level.spotGapMs) {
            val nearby = app.spots.spots.value
                .filter { poi -> poi.id !in s.talkedAbout && s.visits.none { it.matches(poi) } }
                .map { it to it.distanceFrom(location) }
                .filter { (_, d) -> d <= ANNOUNCE_RADIUS_M }
                .sortedBy { (_, d) -> d }
            // A spot the channel put a cue on comes first, even of a kind it skips or with spot talk
            // off: the channel placed it on purpose (API-003「きっかけ」). The spacing still holds.
            val cued = station.materials?.let { m ->
                nearby.firstNotNullOfOrNull { (poi, d) -> app.cueFinder.spot(station.id, m.media, poi, d.toInt(), s.shownCues, now)?.let { Triple(poi, d, it) } }
            }
            if (cued != null) {
                talk {
                    s.lastSpotTalkAt = now
                    talkAboutSpot(s, cued.first, cued.second.toInt(), cued.third)
                }
                return
            }
            // One spot per tick, by distance from the user (spots' distances to each other don't matter).
            val candidates = nearby
                .map { (poi, d) -> SpotCandidate(poi, d, app.history.visitsTo(poi, excludingWalk = s.id).isNotEmpty()) }
            val spot = SpotChooser.pick(
                candidates, station.preferredCategories, station.skippedCategories,
                newSpots = station.talksOn(TalkEventKind.SPOT), revisits = station.talksOn(TalkEventKind.REVISIT),
            )
            if (spot != null) {
                talk {
                    s.lastSpotTalkAt = now
                    talkAboutSpot(s, spot.poi, spot.distanceM.toInt())
                }
                return
            }
        }

        // A point the channel put a cue on, as for spots: not while standing still (API-003「きっかけ」).
        if (!stationary && !justSwitched) {
            val fire = station.materials?.let { app.cueFinder.point(station.id, it.media, location.latitude, location.longitude, s.shownCues, now) }
            if (fire != null) {
                talk { show(s, fire, present(s, fire, station), spotName = null) }
                return
            }
        }

        // Heads-up before dark, regardless of the talk level: it's about getting home, not chat.
        val sunsetAt = weather?.sunsetAt
        if (sunsetAt != null && !s.sunsetWarned) {
            val minutesLeft = ((sunsetAt - now) / 60_000).toInt()
            // Too close to be useful below the latest minute; an empty range if the settings cross.
            if (minutesLeft in limits[Threshold.SUNSET_LATEST_MIN]..limits[Threshold.SUNSET_NOTICE_MIN]) {
                talk {
                    s.sunsetWarned = true
                    speakLine(app.narration.line(TalkEvent.Sunset(minutesLeft), s, weather))
                }
                return
            }
        }

        if (now - s.lastSpokeAt < level.minGapMs) return

        val facility = FacilityAdvisor.pick(
            app.spots.facilities.value.map { it to it.distanceFrom(location) },
            s.elapsedMs(now), weather, s.mentionedFacilities, s.facilityMentionAt, now, limits,
        )
        if (facility != null) {
            talk {
                s.mentionedFacilities += facility.facility.id
                s.facilityMentionAt[facility.need] = now
                val direction = directionTo(location, facility)
                speakLine(app.narration.line(TalkEvent.NearFacility(facility, direction), s, weather))
            }
            return
        }

        // Small talk right after the greeting would crowd it.
        if (justSwitched) return

        val restMinutes = s.restMinutes(now)
        if (level.remarkOnRest && station.talksOn(TalkEventKind.REST) && restMinutes >= REST_MINUTES && !s.restRemarked) {
            talk {
                s.markRestRemarked()
                speakLine(app.narration.line(TalkEvent.Rest(restMinutes), s, weather))
            }
            return
        }
        val walkedSinceMilestone = s.distanceM - s.lastMilestoneDistanceM
        if (walkedSinceMilestone >= level.milestoneDistanceM ||
            (now - s.lastMilestoneAt >= level.milestoneIntervalMs && walkedSinceMilestone > 100)
        ) {
            if (station.talksOn(TalkEventKind.MILESTONE)) {
                talk {
                    s.lastMilestoneAt = now
                    s.lastMilestoneDistanceM = s.distanceM
                    speakLine(app.narration.line(TalkEvent.Milestone, s, weather))
                    saveProgress(s)
                }
            } else {
                // Still a checkpoint for the history, so a quiet walk isn't lost if the service dies.
                s.lastMilestoneAt = now
                s.lastMilestoneDistanceM = s.distanceM
                saveProgress(s)
            }
        }
    }

    /**
     * Talks about a spot: what the channel's cue shows when it has one ([fire]), else the narration.
     * A cue that comes to nothing (all its candidates failed) leaves the usual narration.
     */
    private suspend fun talkAboutSpot(s: WalkSession, poi: Poi, distanceM: Int, fire: CueFire? = null) {
        s.talkedAbout += poi.id
        val station = app.stations.current.value
        val shown = fire?.let { present(s, it, station) }.orEmpty()
        val past = app.history.visitsTo(poi, excludingWalk = s.id)
        val text = when {
            shown.isNotEmpty() -> textOf(shown) ?: poi.name
            past.isEmpty() -> {
                // The AI, else a fixed line (a short "nearby" one when the AI failed).
                app.narration.spotGuide(poi, distanceM)
            }
            else -> app.narration.line(TalkEvent.Revisit(poi, past), s, weather)
        }
        s.visits += SpotVisit(
            poi.id, poi.name, poi.category, System.currentTimeMillis(), text, poi.lat, poi.lon,
            station = app.stations.current.value.key,
        )
        app.feed.update(s)
        app.feed.guide(poi)
        saveProgress(s)
        getSystemService(NotificationManager::class.java)
            .notify(SPOT_NOTIFICATION_ID, spotNotification(poi, text))
        if (fire != null && shown.isNotEmpty()) show(s, fire, shown, poi.name) else speakLine(text, poi.name)
    }

    // ---- a channel's cues (ADR-002 T-8b) ----

    private suspend fun present(s: WalkSession, fire: CueFire, station: Station): List<Shown> =
        app.cues.present(fire, station, MaterialContext(station, s, weather, app.mood.mood.value))

    /**
     * Shows what a cue came to, in its order ([CueShow]); its spoken lines go in the queue the
     * companion speaks from (API-003 F-13). The cue counts as shown even when it came to
     * nothing, so it isn't tried again on every tick.
     */
    private fun show(s: WalkSession, fire: CueFire, shown: List<Shown>, spotName: String?) {
        val now = System.currentTimeMillis()
        app.cueFinder.record(fire, s.shownCues, now)
        app.cueShow.show(CueLayout.of("${fire.key}@$now", shown), app.stations.current.value.name, spotName, ::speakLine)
    }

    /** The cues of the current channel for [event], each after the other. */
    private suspend fun eventCues(s: WalkSession, event: CueEvent, subject: Subject) {
        val station = app.stations.current.value
        val media = station.materials?.media ?: return
        for (fire in app.cueFinder.events(station.id, media, event, subject, s.shownCues, System.currentTimeMillis())) {
            show(s, fire, present(s, fire, station), spotName = null)
        }
    }

    /** What a cue said, for the history and the spot notification: its first text. */
    private fun textOf(shown: List<Shown>): String? =
        shown.firstNotNullOfOrNull { item -> item.items.firstNotNullOfOrNull { (it.content as? Content.Text)?.text } }

    /** "前方" etc. while the user is walking; null when standing (no reliable heading) or already there. */
    private fun directionTo(location: Location, advice: FacilityAdvice): String? {
        if (!location.hasBearing() || location.speed < MIN_HEADING_SPEED || advice.distanceM < 15) return null
        val target = Location("").apply { latitude = advice.facility.lat; longitude = advice.facility.lon }
        return FacilityAdvisor.relativeDirection(location.bearing, location.bearingTo(target))
    }

    private fun speakLine(text: String, spotName: String? = null) = speakLine(Utterance(System.currentTimeMillis(), text, spotName))

    private fun speakLine(line: Utterance) {
        // A line that finishes generating after the walk ended is dropped, so it can't
        // re-post the ongoing walk notification.
        val s = session ?: return
        s.remember(line.text)
        app.feed.add(line)
        app.speaker.speak(CueShow.spoken(line))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, walkingNotification(line.text))
    }

    private inline fun talk(block: () -> Unit) {
        if (!talking.tryLock()) return
        try {
            block()
        } finally {
            talking.unlock()
        }
    }

    private suspend fun refreshWeather(location: Location) {
        val now = System.currentTimeMillis()
        if (now - weatherFetchedAt < WEATHER_REFRESH_MS) return
        weatherFetchedAt = now
        weather = try {
            app.weather.current(location.latitude, location.longitude)
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed", e)
            weather
        }
        if (session != null) app.feed.updateWeather(weather)
    }

    /** Saves the walk so far, in the app scope so a stopping service doesn't cut the write short. */
    private fun saveProgress(s: WalkSession) {
        val record = s.toRecord()
        app.appScope.launch { app.history.save(record) }
    }

    private fun finishWalk(s: WalkSession) {
        val record = s.toRecord()
        val lastWeather = weather
        app.feed.endWalk()
        if (record.durationMs < MIN_SAVED_WALK_MS && record.visits.isEmpty()) return
        val station = app.stations.current.value
        val summarize = station.talksOn(TalkEventKind.FINISH)
        app.appScope.launch {
            app.history.save(record)
            if (summarize) {
                val line = app.narration.line(TalkEvent.Finish(record), null, lastWeather)
                app.feed.add(Utterance(System.currentTimeMillis(), line))
                app.speaker.speak(line)
            }
            // The channel's own cues for the end come after the summary (API-003 `event: finish`).
            app.showEventCues(station, CueEvent.FINISH, Subject.Event(TalkEvent.Finish(record)), s.shownCues, MaterialContext(station, null, lastWeather))
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun walkingNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WalkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SanpoApp.CHANNEL_WALK)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("散策中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .addAction(0, "終了", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun spotNotification(poi: Poi, text: String): Notification =
        NotificationCompat.Builder(this, SanpoApp.CHANNEL_SPOT)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("${poi.name}（${poi.category}）")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    private fun alertNotification(text: String): Notification =
        NotificationCompat.Builder(this, SanpoApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("雷雨の予報")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    companion object {
        private const val TAG = "WalkService"
        private const val NOTIFICATION_ID = 1
        // One slot for spot talks: each new spot replaces the last instead of piling up.
        private const val SPOT_NOTIFICATION_ID = 2
        private const val ALERT_NOTIFICATION_ID = 3
        private const val ACTION_STOP = "stop"
        private const val TICK_MS = 30_000L
        // Often enough that the lookahead for weather changes stays current.
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
        private const val REST_MINUTES = 4
        private const val STATIONARY_MINUTES = 2
        // Below walking pace (m/s) the reported heading is noise.
        private const val MIN_HEADING_SPEED = 0.5f
        private const val MIN_SAVED_WALK_MS = 60_000L
        // Measured from the user, not around each spot.
        const val ANNOUNCE_RADIUS_M = 60f

        private val _walking = MutableStateFlow(false)
        val walking: StateFlow<Boolean> = _walking.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WalkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WalkService::class.java))
        }
    }
}
