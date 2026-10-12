package io.github.duwenji.sanpoguide.companion

import io.github.duwenji.michizure.kit.geo.weather.Weather
import io.github.duwenji.michizure.kit.geo.Poi
import io.github.duwenji.michizure.kit.geo.LatLon
import io.github.duwenji.sanpoguide.material.CueDetail
import io.github.duwenji.sanpoguide.material.Material
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A line the companion said, as shown in the app. A channel's cue may add an [image] (a card's),
 * the [credit] of its material, and a [detail] the user opens by tapping (ADR-002 T-8c).
 */
data class Utterance(
    val at: Long,
    val text: String,
    val spotName: String? = null,
    val image: Material? = null,
    val credit: String? = null,
    val detail: CueDetail? = null,
    /** Said before the line, never shown: a promotion's notice (ADR-002 D-32). */
    val spokenPrefix: String? = null,
)

/** Live walk figures for the UI; null while not walking. */
data class LiveWalk(val startedAt: Long, val distanceM: Double, val spotCount: Int)

/** What the walk service publishes for the UI: live stats and the companion's recent lines. */
class CompanionFeed {
    private val _live = MutableStateFlow<LiveWalk?>(null)
    val live: StateFlow<LiveWalk?> = _live.asStateFlow()

    private val _lines = MutableStateFlow<List<Utterance>>(emptyList())
    /** Newest first. */
    val lines: StateFlow<List<Utterance>> = _lines.asStateFlow()

    private val _weather = MutableStateFlow<Weather?>(null)
    /** The latest weather while walking (fetched every 15 minutes); null outside walk mode. */
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    private val _talkedAbout = MutableStateFlow<Set<String>>(emptySet())
    /** Spot ids talked about during the current walk. */
    val talkedAbout: StateFlow<Set<String>> = _talkedAbout.asStateFlow()

    private val _guiding = MutableStateFlow<Poi?>(null)
    /** The spot the current walk last introduced; the map shows the way to it. Null outside walk mode. */
    val guiding: StateFlow<Poi?> = _guiding.asStateFlow()

    private val _walked = MutableStateFlow<List<LatLon>>(emptyList())
    /** The way walked so far on the current walk, for the map; empty outside walk mode. */
    val walked: StateFlow<List<LatLon>> = _walked.asStateFlow()

    fun startWalk(session: WalkSession) {
        _lines.value = emptyList()
        _walked.value = emptyList()
        _talkedAbout.value = emptySet()
        _guiding.value = null
        update(session)
    }

    fun update(session: WalkSession) {
        _live.value = LiveWalk(session.startedAt, session.distanceM, session.visits.size)
        _talkedAbout.value = session.talkedAbout.toSet()
        // Points are added only every few meters; copy the route only when it has grown.
        if (session.routeSize != _walked.value.size) _walked.value = session.walkedRoute()
    }

    fun endWalk() {
        _live.value = null
        _weather.value = null
        _guiding.value = null
        _walked.value = emptyList()
    }

    fun updateWeather(weather: Weather?) {
        _weather.value = weather
    }

    fun guide(spot: Poi) {
        _guiding.value = spot
    }

    fun add(line: Utterance) = _lines.update { (listOf(line) + it).take(MAX_LINES) }

    private val _opened = MutableStateFlow<CueDetail?>(null)
    /** The cue detail the user opened from a card or a notification; null when none is open. */
    val opened: StateFlow<CueDetail?> = _opened.asStateFlow()

    fun open(detail: CueDetail) {
        _opened.value = detail
    }

    /** Opens the detail of a line still in the feed (a notification's); a detail gone from it is not opened. */
    fun open(id: String) {
        _lines.value.firstNotNullOfOrNull { it.detail?.takeIf { d -> d.id == id } }?.let(::open)
    }

    fun close() {
        _opened.value = null
    }

    private companion object {
        const val MAX_LINES = 30
    }
}
