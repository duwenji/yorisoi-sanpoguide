package io.github.duwenji.sanpoguide.companion

import io.github.duwenji.michizure.core.format.TalkEventKind
import io.github.duwenji.michizure.kit.geo.Facility
import io.github.duwenji.michizure.kit.geo.Poi
import io.github.duwenji.michizure.kit.geo.weather.Weather
import io.github.duwenji.sanpoguide.material.CueFire
import io.github.duwenji.sanpoguide.settings.TalkLevel
import io.github.duwenji.sanpoguide.settings.Threshold
import io.github.duwenji.sanpoguide.settings.Thresholds

/**
 * A spot within reach of the user, as the walk service sees it (RFC-003 D-2, D-5).
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
 * Everything [TalkDecider] reads, as plain values gathered by the walk service each time (RFC-003
 * D-2). No Android types, so a scene can be written down in a unit test.
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
)

/** What to say now, one per tick (RFC-003 D-3). The walk service carries it out. */
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

/**
 * Decides whether anything is worth saying right now, most important first (architecture.md 図 2).
 * Pure: the walk service gathers a [TalkSituation] and carries out the [TalkDecision].
 */
object TalkDecider {
    /** Spot talks cover this far from the user (measured from the user, not around each spot). */
    const val ANNOUNCE_RADIUS_M = 60f
    /** Standing this long counts as staying put: no running through the spots within reach. */
    const val STATIONARY_MINUTES = 2
    /** A rest remark comes after standing this long. */
    const val REST_MINUTES = 4

    fun decide(s: TalkSituation): TalkDecision {
        val now = s.now
        val level = s.level
        val limits = s.limits

        // A turn in the weather comes first, regardless of the talk level: it's about safety.
        val change = s.weather?.let { WeatherChangeDetector.detect(it, now, limits) }
        val warnedAt = change?.let { s.weatherWarnedAt[it.kind] }
        if (change != null && (warnedAt == null || now - warnedAt >= limits.ms(Threshold.WEATHER_REPEAT_MIN))) {
            return TalkDecision.WeatherTurn(change)
        }

        // The user just switched channels: the new one says hello before anything else. No AI, so it's instant.
        s.pendingGreeting?.let { return TalkDecision.Greeting(it, s.pendingSelect) }

        // While the user stands still, don't run through every spot within reach one by one;
        // the spot they arrived at has been introduced, and a rest remark may be due instead.
        val stationary = s.restMinutes >= STATIONARY_MINUTES
        if (!stationary && !s.justSwitched && now - s.lastSpotTalkAt >= level.spotGapMs) {
            val nearby = s.spots
                .filter { !it.alreadyThisWalk && it.distanceM <= ANNOUNCE_RADIUS_M }
                .sortedBy { it.distanceM }
            // A spot the channel put a cue on comes first, even of a kind it skips or with spot talk
            // off: the channel placed it on purpose (API-003「きっかけ」). The spacing still holds.
            nearby.firstOrNull { it.cue != null }?.let { return TalkDecision.SpotCue(it.poi, it.distanceM.toInt(), it.cue!!) }
            // One spot per tick, by distance from the user (spots' distances to each other don't matter).
            val spot = SpotChooser.pick(
                nearby.map { SpotCandidate(it.poi, it.distanceM, it.visitedBefore) },
                s.preferredCategories, s.skippedCategories,
                newSpots = TalkEventKind.SPOT in s.events, revisits = TalkEventKind.REVISIT in s.events,
            )
            if (spot != null) return TalkDecision.Spot(spot.poi, spot.distanceM.toInt())
        }

        // A point the channel put a cue on, as for spots: not while standing still (API-003「きっかけ」).
        if (!stationary && !s.justSwitched && s.pointCue != null) return TalkDecision.PointCue(s.pointCue)

        // Heads-up before dark, regardless of the talk level: it's about getting home, not chat.
        val sunsetAt = s.weather?.sunsetAt
        if (sunsetAt != null && !s.sunsetWarned) {
            val minutesLeft = ((sunsetAt - now) / 60_000).toInt()
            // Too close to be useful below the latest minute; an empty range if the settings cross.
            if (minutesLeft in limits[Threshold.SUNSET_LATEST_MIN]..limits[Threshold.SUNSET_NOTICE_MIN]) {
                return TalkDecision.Sunset(minutesLeft)
            }
        }

        if (now - s.lastSpokeAt < level.minGapMs) return TalkDecision.Nothing

        val facility = FacilityAdvisor.pick(
            s.facilities, s.elapsedMs, s.weather, s.mentionedFacilities, s.facilityMentionAt, now, limits,
        )
        if (facility != null) return TalkDecision.NearFacility(facility)

        // Small talk right after the greeting would crowd it.
        if (s.justSwitched) return TalkDecision.Nothing

        if (level.remarkOnRest && TalkEventKind.REST in s.events && s.restMinutes >= REST_MINUTES && !s.restRemarked) {
            return TalkDecision.Rest(s.restMinutes)
        }
        val walkedSinceMilestone = s.distanceM - s.lastMilestoneDistanceM
        if (walkedSinceMilestone >= level.milestoneDistanceM ||
            (now - s.lastMilestoneAt >= level.milestoneIntervalMs && walkedSinceMilestone > 100)
        ) {
            // Still a checkpoint for the history on a quiet channel, so the walk isn't lost if the service dies.
            return if (TalkEventKind.MILESTONE in s.events) TalkDecision.Milestone else TalkDecision.Checkpoint
        }
        return TalkDecision.Nothing
    }
}
