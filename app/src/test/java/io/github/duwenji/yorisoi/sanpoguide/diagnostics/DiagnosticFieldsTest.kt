package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.kit.geo.Facility
import io.github.duwenji.yorisoi.kit.geo.FacilityKind
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.sanpoguide.companion.FacilityAdvice
import io.github.duwenji.yorisoi.sanpoguide.companion.FacilityNeed
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkDecision
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkSituation
import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticFieldsTest {
    private val situation = TalkSituation(
        now = 1_000_000L, weather = null, limits = Thresholds(),
        level = TalkLevel.NORMAL, events = TalkEventKind.entries.toSet(), preferredCategories = emptySet(), skippedCategories = emptySet(),
        spots = emptyList(), facilities = emptyList(), pointCue = null,
        elapsedMs = 600_000L, distanceM = 300.0, restMinutes = 0, restRemarked = false,
        lastSpokeAt = 0L, lastSpotTalkAt = 0L, lastMilestoneAt = 0L, lastMilestoneDistanceM = 0.0,
        sunsetWarned = false, weatherWarnedAt = emptyMap(), mentionedFacilities = emptySet(), facilityMentionAt = emptyMap(),
        pendingGreeting = null, pendingSelect = false, justSwitched = false,
    )

    @Test
    fun `a decision's fields and the situation's are written side by side without overwriting each other`() {
        val decisions = listOf(
            TalkDecision.Spot(Poi("way/1", "鶴岡八幡宮", 35.0, 139.0, "神社", emptyMap()), 40),
            TalkDecision.NearFacility(FacilityAdvice(Facility("t1", FacilityKind.TOILETS, 35.0, 139.0, null), FacilityNeed.entries.first(), 40)),
        )
        val situationKeys = DiagnosticFields.situation(situation).map { it.first }.toSet()
        decisions.forEach { d ->
            val keys = DiagnosticFields.decision(d).map { it.first }.toSet()
            assertEquals(emptySet<String>(), keys intersect situationKeys)
        }
    }

    @Test
    fun `the spot's distance and the distance walked are both kept`() {
        val fields = (DiagnosticFields.decision(TalkDecision.Spot(Poi("way/1", "鶴岡八幡宮", 35.0, 139.0, "神社", emptyMap()), 40)) +
            DiagnosticFields.situation(situation)).toMap()
        assertEquals(40, fields["distanceM"])
        assertEquals(300, fields["walkedM"])
    }
}
