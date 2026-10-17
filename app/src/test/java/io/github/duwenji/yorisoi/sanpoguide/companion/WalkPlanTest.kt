package io.github.duwenji.yorisoi.sanpoguide.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkPlanTest {
    @Test
    fun `prompt summary excludes note and planned locations`() {
        val plan = WalkPlan(
            themeCategory = "公園",
            durationMinutes = 60,
            distanceKm = 3,
            note = "この文はAIへ送らない",
            stops = listOf(PlannedStop("way/1", "中央公園", "公園", 35.0, 139.0)),
        )

        assertEquals("テーマ: 公園・時間: 60分・距離: 3km", plan.promptSummary())
        assertFalse(plan.promptSummary().orEmpty().contains("この文はAIへ送らない"))
        assertFalse(plan.promptSummary().orEmpty().contains("中央公園"))
        assertFalse(plan.promptSummary().orEmpty().contains("way/1"))
        assertFalse(plan.promptSummary().orEmpty().contains("35.0"))
        assertFalse(plan.promptSummary().orEmpty().contains("139.0"))
    }

    @Test
    fun `toggling a selected stop removes it and toggling it again adds it`() {
        val stop = PlannedStop("way/1", "中央公園", "公園", 35.0, 139.0)
        val plan = WalkPlan().toggleStop(stop)

        assertEquals(listOf(stop), plan.stops)
        assertTrue(plan.toggleStop(stop).stops.isEmpty())
    }
}
