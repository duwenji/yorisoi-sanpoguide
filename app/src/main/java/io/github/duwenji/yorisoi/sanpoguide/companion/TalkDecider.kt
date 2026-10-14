package io.github.duwenji.yorisoi.sanpoguide.companion

import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.kit.companion.TalkEngine
import io.github.duwenji.yorisoi.kit.companion.TalkGates
import io.github.duwenji.yorisoi.kit.companion.TalkRule
import io.github.duwenji.yorisoi.kit.geo.Facility
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.sanpoguide.material.CueFire
import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel
import io.github.duwenji.yorisoi.sanpoguide.settings.Threshold
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds

/**
 * A spot within reach of the user, as the walk service sees it.
 *
 * @param alreadyThisWalk talked about on this walk already (by id, or the same place by name)
 * @param visitedBefore visited on an earlier walk: a "welcome back" rather than a guide
 * @param cue the channel's cue for this spot that would fire now, if any
 */
data class NearbySpot(
    val poi: Poi,
    val distanceM: Float,
    val alreadyThisWalk: Boolean,
    val visitedBefore: Boolean,
    val cue: CueFire? = null,
)

/**
 * Everything [TalkDecider] reads, as plain values gathered by the walk service each time.
 * No Android types, so a scene can be written down in a unit test.
 */
data class TalkSituation(
    val now: Long,
    val weather: Weather?,
    val limits: Thresholds,
    /** The channel's talk level, events it talks on, and the spot kinds it prefers or skips (as `Poi.category`). */
    val level: TalkLevel,
    val events: Set<TalkEventKind>,
    val preferredCategories: Set<String>,
    val skippedCategories: Set<String>,
    /** Spots within [TalkDecider.ANNOUNCE_RADIUS_M] (or a little more); the decider checks the radius itself. */
    val spots: List<NearbySpot>,
    /** Facilities around, with their distance from the user. */
    val facilities: List<Pair<Facility, Float>>,
    /** The channel's cue for where the user stands, if one would fire now. */
    val pointCue: CueFire?,
    // The walk so far (WalkSession).
    val elapsedMs: Long,
    val distanceM: Double,
    val restMinutes: Int,
    val restRemarked: Boolean,
    val lastSpokeAt: Long,
    val lastSpotTalkAt: Long,
    val lastMilestoneAt: Long,
    val lastMilestoneDistanceM: Double,
    val sunsetWarned: Boolean,
    val weatherWarnedAt: Map<WeatherChangeKind, Long>,
    val mentionedFacilities: Set<String>,
    val facilityMentionAt: Map<FacilityNeed, Long>,
    val pendingGreeting: String?,
    val pendingSelect: Boolean,
    val justSwitched: Boolean,
) : TalkGates<WalkGate> {
    override fun isOpen(gate: WalkGate): Boolean = when (gate) {
        WalkGate.SPOT_GAP -> now - lastSpotTalkAt >= level.spotGapMs
        WalkGate.CHAT_GAP -> now - lastSpokeAt >= level.minGapMs
        // While the user stands still, don't run through every spot within reach one by one;
        // the spot they arrived at has been introduced, and a rest remark may be due instead.
        WalkGate.MOVING -> restMinutes < TalkDecider.STATIONARY_MINUTES
        // Right after a switch the new channel's greeting has the floor.
        WalkGate.SETTLED -> !justSwitched
    }
}

/** When a kind of talk may come up on a walk. The situation says which are open. */
enum class WalkGate {
    /** The talk level's gap since the last spot talk has passed. */
    SPOT_GAP,
    /** The talk level's gap since the companion last said anything has passed. */
    CHAT_GAP,
    /** The user isn't standing still. */
    MOVING,
    /** Not right after a channel switch. */
    SETTLED,
}

/** What to say now, one per tick. The walk service carries it out. */
sealed interface TalkDecision {
    data class WeatherTurn(val change: WeatherChange) : TalkDecision
    /** The new channel's greeting; then its cues for being chosen, if [select]. */
    data class Greeting(val line: String, val select: Boolean) : TalkDecision
    /** A spot the channel put a cue on. */
    data class SpotCue(val poi: Poi, val distanceM: Int, val fire: CueFire) : TalkDecision
    /** A spot to introduce, or to greet again if visited on an earlier walk. */
    data class Spot(val poi: Poi, val distanceM: Int) : TalkDecision
    /** A point the channel put a cue on. */
    data class PointCue(val fire: CueFire) : TalkDecision
    data class Sunset(val minutesLeft: Int) : TalkDecision
    data class NearFacility(val advice: FacilityAdvice) : TalkDecision
    data class Rest(val minutes: Int) : TalkDecision
    data object Milestone : TalkDecision
    /** A milestone on a channel that doesn't talk on them: only saved to the history. */
    data object Checkpoint : TalkDecision
    data object Nothing : TalkDecision
}

private typealias Rule = TalkRule<TalkSituation, TalkDecision, WalkGate>

/**
 * Decides whether anything is worth saying right now, most important first (architecture.md 図 2):
 * the walk's rules in order, each behind its gates. Pure: the walk service
 * gathers a [TalkSituation] and carries out the [TalkDecision].
 */
object TalkDecider {
    /** Spot talks cover this far from the user (measured from the user, not around each spot). */
    const val ANNOUNCE_RADIUS_M = 60f
    /** Standing this long counts as staying put: no running through the spots within reach. */
    const val STATIONARY_MINUTES = 2
    /** A rest remark comes after standing this long. */
    const val REST_MINUTES = 4

    /** The walk's rules, in the order they are asked. [TalkRule.safety]: a channel can't switch it off. */
    val rules: List<Rule> = listOf(
        // A turn in the weather comes first, regardless of the talk level: it's about safety.
        Rule("weather", safety = true) { s ->
            val change = s.weather?.let { WeatherChangeDetector.detect(it, s.now, s.limits) }
            val warnedAt = change?.let { s.weatherWarnedAt[it.kind] }
            change?.takeIf { warnedAt == null || s.now - warnedAt >= s.limits.ms(Threshold.WEATHER_REPEAT_MIN) }
                ?.let(TalkDecision::WeatherTurn)
        },
        // The user just switched channels: the new one says hello before anything else. No AI, so it's instant.
        Rule("greeting") { s -> s.pendingGreeting?.let { TalkDecision.Greeting(it, s.pendingSelect) } },
        Rule("spot", setOf(WalkGate.SPOT_GAP, WalkGate.MOVING, WalkGate.SETTLED)) { s ->
            val nearby = s.spots
                .filter { !it.alreadyThisWalk && it.distanceM <= ANNOUNCE_RADIUS_M }
                .sortedBy { it.distanceM }
            // A spot the channel put a cue on comes first, even of a kind it skips or with spot talk
            // off: the channel placed it on purpose (API-003「きっかけ」). The spacing still holds.
            nearby.firstOrNull { it.cue != null }?.let { TalkDecision.SpotCue(it.poi, it.distanceM.toInt(), it.cue!!) }
                // One spot per tick, by distance from the user (spots' distances to each other don't matter).
                ?: SpotChooser.pick(
                    nearby.map { SpotCandidate(it.poi, it.distanceM, it.visitedBefore) },
                    s.preferredCategories, s.skippedCategories,
                    newSpots = TalkEventKind.SPOT in s.events, revisits = TalkEventKind.REVISIT in s.events,
                )?.let { TalkDecision.Spot(it.poi, it.distanceM.toInt()) }
        },
        // A point the channel put a cue on, as for spots: not while standing still (API-003「きっかけ」).
        Rule("point", setOf(WalkGate.MOVING, WalkGate.SETTLED)) { s -> s.pointCue?.let(TalkDecision::PointCue) },
        // Heads-up before dark, regardless of the talk level: it's about getting home, not chat.
        Rule("sunset", safety = true) { s ->
            val sunsetAt = s.weather?.sunsetAt
            val minutesLeft = sunsetAt?.let { ((it - s.now) / 60_000).toInt() }
            // Too close to be useful below the latest minute; an empty range if the settings cross.
            minutesLeft?.takeIf { !s.sunsetWarned && it in s.limits[Threshold.SUNSET_LATEST_MIN]..s.limits[Threshold.SUNSET_NOTICE_MIN] }
                ?.let(TalkDecision::Sunset)
        },
        // From here on the small-talk gap holds. Facilities still come up right after a switch.
        Rule("facility", setOf(WalkGate.CHAT_GAP), safety = true) { s ->
            FacilityAdvisor.pick(s.facilities, s.elapsedMs, s.weather, s.mentionedFacilities, s.facilityMentionAt, s.now, s.limits)
                ?.let(TalkDecision::NearFacility)
        },
        // Small talk right after the greeting would crowd it.
        Rule("rest", setOf(WalkGate.CHAT_GAP, WalkGate.SETTLED)) { s ->
            TalkDecision.Rest(s.restMinutes).takeIf {
                s.level.remarkOnRest && TalkEventKind.REST in s.events && s.restMinutes >= REST_MINUTES && !s.restRemarked
            }
        },
        Rule("milestone", setOf(WalkGate.CHAT_GAP, WalkGate.SETTLED)) { s ->
            val walkedSinceMilestone = s.distanceM - s.lastMilestoneDistanceM
            val due = walkedSinceMilestone >= s.level.milestoneDistanceM ||
                (s.now - s.lastMilestoneAt >= s.level.milestoneIntervalMs && walkedSinceMilestone > 100)
            // Still a checkpoint for the history on a quiet channel, so the walk isn't lost if the service dies.
            if (!due) null else if (TalkEventKind.MILESTONE in s.events) TalkDecision.Milestone else TalkDecision.Checkpoint
        },
    )

    private val engine = TalkEngine(rules, TalkDecision.Nothing)

    fun decide(s: TalkSituation): TalkDecision = engine.decide(s)
}
