package io.github.duwenji.yorisoi.sanpoguide.companion

import io.github.duwenji.yorisoi.core.format.Cue
import io.github.duwenji.yorisoi.core.format.Repeat
import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.core.format.Trigger
import io.github.duwenji.yorisoi.kit.geo.Facility
import io.github.duwenji.yorisoi.kit.geo.FacilityKind
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.ForecastSlot
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.channel.material.CueFire
import io.github.duwenji.yorisoi.kit.channel.material.Subject
import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeKind

/**
 * Scenes of a walk and what the companion says in each: every check of the decision
 * (architecture.md 図 2), once where it speaks and once where it holds back, and the clashes
 * between them. The decision's order is the contract here, not how it is written.
 */
class TalkDeciderTest {
    private val now = 10_000_000_000L
    private val minute = 60_000L

    private val allEvents = TalkEventKind.entries.toSet()

    /** A walk of 10 minutes where nothing is due: just spoken a while ago, nothing around. */
    private val calm = TalkSituation(
        now = now, weather = null, limits = Thresholds(),
        level = TalkLevel.NORMAL, events = allEvents, preferredCategories = emptySet(), skippedCategories = emptySet(),
        spots = emptyList(), facilities = emptyList(), pointCue = null,
        elapsedMs = 10 * minute, distanceM = 300.0, restMinutes = 0, restRemarked = false,
        lastSpokeAt = now - 10 * minute, lastSpotTalkAt = 0, lastMilestoneAt = now - 10 * minute, lastMilestoneDistanceM = 0.0,
        sunsetWarned = false, weatherWarnedAt = emptyMap(), mentionedFacilities = emptySet(), facilityMentionAt = emptyMap(),
        pendingGreeting = null, pendingSelect = false, justSwitched = false,
    )

    private fun poi(name: String, category: String = "神社") = Poi(name, name, 35.0, 139.0, category, emptyMap())
    private fun spot(
        name: String,
        distanceM: Float,
        category: String = "神社",
        visitedBefore: Boolean = false,
        already: Boolean = false,
        cue: CueFire? = null,
        planned: Boolean = false,
        visitedRecently: Boolean = false,
    ) = NearbySpot(poi(name, category), distanceM, already, visitedBefore, cue, planned, visitedRecently)
    private fun cue(key: String) = CueFire(Cue(key, Trigger.Point(35.0, 139.0, 50), null, Repeat.ONCE_PER_WALK, emptyList()), Subject.None, key)

    /** Clear now, a thunderstorm within the next quarter hour. */
    private val thunderComing = Weather("晴れ", 20.0, 0.0, code = 0, forecast = listOf(ForecastSlot(now + 15 * minute, 95, 0.0)))
    private fun sunsetIn(minutes: Int) = Weather("晴れ", 20.0, 0.0, sunsetAt = now + minutes * minute, code = 0)
    private val toilet = Facility("t1", FacilityKind.TOILETS, 35.0, 139.0, null) to 40f

    private fun decide(s: TalkSituation) = TalkDecider.decide(s)

    @Test
    fun `nothing due, nothing said`() {
        assertEquals(TalkDecision.Nothing, decide(calm))
    }

    // ---- ① a turn in the weather ----

    @Test
    fun `a coming thunderstorm comes first, whatever else is due`() {
        val busy = calm.copy(
            weather = thunderComing, lastSpokeAt = now, justSwitched = true, pendingGreeting = "こんにちは",
            spots = listOf(spot("八幡宮", 20f)),
        )
        val decision = decide(busy)
        assertEquals(WeatherChangeKind.THUNDER, (decision as TalkDecision.WeatherTurn).change.kind)
    }

    @Test
    fun `the same warning waits out its repeat gap`() {
        val warned = calm.copy(weather = thunderComing, weatherWarnedAt = mapOf(WeatherChangeKind.THUNDER to now - 30 * minute))
        assertEquals(TalkDecision.Nothing, decide(warned))
        val longAgo = warned.copy(weatherWarnedAt = mapOf(WeatherChangeKind.THUNDER to now - 60 * minute))
        assertEquals(TalkDecision.WeatherTurn::class, decide(longAgo)::class)
    }

    // ---- ② a new channel's greeting ----

    @Test
    fun `a new channel says hello before any spot`() {
        val switched = calm.copy(pendingGreeting = "ここからは歴史の話を", pendingSelect = true, justSwitched = true, spots = listOf(spot("八幡宮", 20f)))
        assertEquals(TalkDecision.Greeting("ここからは歴史の話を", select = true), decide(switched))
    }

    // ---- ③ spots within reach ----

    @Test
    fun `a spot within reach is introduced`() {
        assertEquals(TalkDecision.Spot(poi("八幡宮"), 30), decide(calm.copy(spots = listOf(spot("八幡宮", 30.5f)))))
    }

    @Test
    fun `no spot talk out of reach, twice on one walk, while standing, right after a switch or too soon`() {
        val within = calm.copy(spots = listOf(spot("八幡宮", 30f)))
        assertEquals(TalkDecision.Nothing, decide(calm.copy(spots = listOf(spot("八幡宮", 61f)))))
        assertEquals(TalkDecision.Nothing, decide(calm.copy(spots = listOf(spot("八幡宮", 30f, already = true)))))
        assertEquals(TalkDecision.Nothing, decide(within.copy(restMinutes = TalkDecider.STATIONARY_MINUTES)))
        assertEquals(TalkDecision.Nothing, decide(within.copy(justSwitched = true)))
        // NORMAL keeps two minutes between spot talks.
        assertEquals(TalkDecision.Nothing, decide(within.copy(lastSpotTalkAt = now - minute)))
        assertEquals(TalkDecision.Spot::class, decide(within.copy(lastSpotTalkAt = now - 2 * minute))::class)
    }

    @Test
    fun `a spot visited on a walk less than a day ago is passed over, unless a cue or the plan points at it (platform REQ-FREQUENT-WAYS F-10)`() {
        assertEquals(TalkDecision.Nothing, decide(calm.copy(spots = listOf(spot("八幡宮", 30f, visitedBefore = true, visitedRecently = true)))))
        val next = decide(calm.copy(spots = listOf(spot("八幡宮", 30f, visitedBefore = true, visitedRecently = true), spot("円覚寺", 40f))))
        assertEquals(TalkDecision.Spot(poi("円覚寺"), 40), next)
        val planned = decide(calm.copy(spots = listOf(spot("八幡宮", 30f, visitedBefore = true, visitedRecently = true, planned = true))))
        assertEquals(TalkDecision.Spot(poi("八幡宮"), 30), planned)
    }

    @Test
    fun `a spot talk ignores the small-talk gap`() {
        assertEquals(TalkDecision.Spot::class, decide(calm.copy(lastSpokeAt = now, spots = listOf(spot("八幡宮", 30f))))::class)
    }

    @Test
    fun `the channel decides which spots are talked about`() {
        val newSpot = calm.copy(spots = listOf(spot("八幡宮", 30f)))
        assertEquals(TalkDecision.Nothing, decide(newSpot.copy(events = allEvents - TalkEventKind.SPOT)))
        assertEquals(TalkDecision.Nothing, decide(newSpot.copy(skippedCategories = setOf("神社"))))
        val revisit = calm.copy(spots = listOf(spot("八幡宮", 30f, visitedBefore = true)), events = allEvents - TalkEventKind.SPOT)
        assertEquals(TalkDecision.Spot::class, decide(revisit)::class)
        assertEquals(TalkDecision.Nothing, decide(revisit.copy(events = allEvents - TalkEventKind.SPOT - TalkEventKind.REVISIT)))
    }

    @Test
    fun `a spot with the channel's cue comes first, the nearest cued one, even of a kind it skips`() {
        val far = cue("far")
        val near = cue("near")
        val s = calm.copy(
            spots = listOf(spot("石碑", 10f, "史跡"), spot("寺", 50f, cue = far), spot("祠", 40f, cue = near)),
            skippedCategories = setOf("神社"), events = allEvents - TalkEventKind.SPOT,
        )
        assertEquals(TalkDecision.SpotCue(poi("祠"), 40, near), decide(s))
        // Still not while standing still, nor right after a switch.
        assertEquals(TalkDecision.Nothing, decide(s.copy(restMinutes = TalkDecider.STATIONARY_MINUTES)))
    }

    @Test
    fun `planned spots do not outrank weather or channel cues and remain subject to skips`() {
        val planned = spot("予定した公園", 20f, category = "公園", planned = true)
        val warning = decide(calm.copy(weather = thunderComing, spots = listOf(planned)))
        assertEquals(WeatherChangeKind.THUNDER, (warning as TalkDecision.WeatherTurn).change.kind)

        val fire = cue("temple")
        val withCue = calm.copy(spots = listOf(planned, spot("寺", 50f, cue = fire)))
        assertEquals(TalkDecision.SpotCue(poi("寺"), 50, fire), decide(withCue))

        val skipped = calm.copy(spots = listOf(planned), skippedCategories = setOf("公園"))
        assertEquals(TalkDecision.Nothing, decide(skipped))
    }

    @Test
    fun `an active point cue is not delayed by a planned stop`() {
        val point = cue("point")
        val planned = spot("予定した公園", 20f, category = "公園", planned = true)

        assertEquals(TalkDecision.PointCue(point), decide(calm.copy(pointCue = point, spots = listOf(planned))))
    }

    // ---- ④ a point the channel put a cue on ----

    @Test
    fun `a point cue fires when nothing nearer is due, but not while standing or right after a switch`() {
        val point = cue("point")
        assertEquals(TalkDecision.PointCue(point), decide(calm.copy(pointCue = point)))
        assertEquals(TalkDecision.Spot::class, decide(calm.copy(pointCue = point, spots = listOf(spot("八幡宮", 30f))))::class)
        assertEquals(TalkDecision.Nothing, decide(calm.copy(pointCue = point, restMinutes = TalkDecider.STATIONARY_MINUTES)))
        assertEquals(TalkDecision.Nothing, decide(calm.copy(pointCue = point, justSwitched = true)))
    }

    // ---- ⑤ sunset ----

    @Test
    fun `sunset is announced in its window, regardless of the small-talk gap`() {
        assertEquals(TalkDecision.Sunset(20), decide(calm.copy(weather = sunsetIn(20), lastSpokeAt = now)))
        assertEquals(TalkDecision.Nothing, decide(calm.copy(weather = sunsetIn(40))))
        assertEquals(TalkDecision.Nothing, decide(calm.copy(weather = sunsetIn(3))))
        assertEquals(TalkDecision.Nothing, decide(calm.copy(weather = sunsetIn(20), sunsetWarned = true)))
        // A point cue still comes first.
        assertEquals(TalkDecision.PointCue::class, decide(calm.copy(weather = sunsetIn(20), pointCue = cue("p")))::class)
    }

    // ---- the small-talk gap ----

    @Test
    fun `below this line the small-talk gap holds`() {
        val due = calm.copy(elapsedMs = 25 * minute, facilities = listOf(toilet), restMinutes = 5, distanceM = 2000.0)
        // NORMAL keeps three minutes between unprompted lines.
        assertEquals(TalkDecision.Nothing, decide(due.copy(lastSpokeAt = now - 2 * minute)))
        assertEquals(TalkDecision.NearFacility::class, decide(due.copy(lastSpokeAt = now - 3 * minute))::class)
    }

    // ---- ⑥ facilities ----

    @Test
    fun `a facility is mentioned when it fits, even right after a switch`() {
        val due = calm.copy(elapsedMs = 25 * minute, facilities = listOf(toilet))
        assertEquals(TalkDecision.NearFacility::class, decide(due)::class)
        assertEquals(TalkDecision.NearFacility::class, decide(due.copy(justSwitched = true))::class)
        // Toilets only after twenty minutes of walking.
        assertEquals(TalkDecision.Nothing, decide(due.copy(elapsedMs = 15 * minute)))
        assertEquals(TalkDecision.Nothing, decide(due.copy(mentionedFacilities = setOf("t1"))))
    }

    // ---- ⑦ rest ----

    @Test
    fun `a rest remark after standing a while, if the channel and the level want one`() {
        val resting = calm.copy(restMinutes = TalkDecider.REST_MINUTES)
        assertEquals(TalkDecision.Rest(TalkDecider.REST_MINUTES), decide(resting))
        assertEquals(TalkDecision.Nothing, decide(resting.copy(restMinutes = TalkDecider.REST_MINUTES - 1)))
        assertEquals(TalkDecision.Nothing, decide(resting.copy(restRemarked = true)))
        assertEquals(TalkDecision.Nothing, decide(resting.copy(level = TalkLevel.QUIET)))
        assertEquals(TalkDecision.Nothing, decide(resting.copy(events = allEvents - TalkEventKind.REST)))
        assertEquals(TalkDecision.Nothing, decide(resting.copy(justSwitched = true)))
        // A facility that fits comes first.
        assertEquals(TalkDecision.NearFacility::class, decide(resting.copy(elapsedMs = 25 * minute, facilities = listOf(toilet)))::class)
    }

    // ---- ⑧ milestones ----

    @Test
    fun `a milestone by distance or by time`() {
        assertEquals(TalkDecision.Milestone, decide(calm.copy(distanceM = 1000.0)))
        assertEquals(TalkDecision.Milestone, decide(calm.copy(lastMilestoneAt = now - 15 * minute, distanceM = 150.0)))
        // Time alone isn't enough without having moved.
        assertEquals(TalkDecision.Nothing, decide(calm.copy(lastMilestoneAt = now - 15 * minute, distanceM = 50.0)))
        assertEquals(TalkDecision.Nothing, decide(calm.copy(distanceM = 1000.0, justSwitched = true)))
        // A rest remark comes first.
        assertEquals(TalkDecision.Rest::class, decide(calm.copy(distanceM = 1000.0, restMinutes = 5))::class)
    }

    @Test
    fun `a channel that keeps quiet on milestones still saves a checkpoint`() {
        assertEquals(TalkDecision.Checkpoint, decide(calm.copy(distanceM = 1000.0, events = allEvents - TalkEventKind.MILESTONE)))
    }

    @Test
    fun `a channel can't silence the weather, sunset or facilities (channels_md V-08)`() {
        val silent = calm.copy(events = emptySet())
        assertEquals(TalkDecision.WeatherTurn::class, decide(silent.copy(weather = thunderComing))::class)
        assertEquals(TalkDecision.Sunset(20), decide(silent.copy(weather = sunsetIn(20))))
        assertEquals(TalkDecision.NearFacility::class, decide(silent.copy(elapsedMs = 25 * minute, facilities = listOf(toilet)))::class)
        // ...while what it can switch off stays off.
        assertEquals(TalkDecision.Nothing, decide(silent.copy(spots = listOf(spot("八幡宮", 30f)), restMinutes = 1)))
        assertEquals(TalkDecision.Checkpoint, decide(silent.copy(distanceM = 1000.0)))
    }

    @Test
    fun `the walk's rules, in order, and those a channel can't switch off`() {
        assertEquals(
            listOf("weather", "greeting", "spot", "point", "sunset", "facility", "rest", "milestone"),
            TalkDecider.rules.map { it.name },
        )
        // ADR-003 E-6: what the profile lists as events a channel can't stop (channel-package-format.md F-4).
        assertEquals(setOf("weather", "sunset", "facility"), TalkDecider.rules.filter { it.safety }.map { it.name }.toSet())
    }

    @Test
    fun `the talk level sets the spacing`() {
        // QUIET: two kilometres between milestones, five minutes between spot talks.
        assertEquals(TalkDecision.Nothing, decide(calm.copy(level = TalkLevel.QUIET, distanceM = 1000.0)))
        assertEquals(TalkDecision.Milestone, decide(calm.copy(level = TalkLevel.CHATTY, distanceM = 500.0)))
        val spotSoon = calm.copy(spots = listOf(spot("八幡宮", 30f)), lastSpotTalkAt = now - 3 * minute)
        assertEquals(TalkDecision.Nothing, decide(spotSoon.copy(level = TalkLevel.QUIET)))
        assertEquals(TalkDecision.Spot::class, decide(spotSoon)::class)
    }
}
