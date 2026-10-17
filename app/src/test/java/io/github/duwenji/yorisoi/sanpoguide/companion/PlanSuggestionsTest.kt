package io.github.duwenji.yorisoi.sanpoguide.companion

import io.github.duwenji.yorisoi.kit.geo.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSuggestionsTest {
    private fun candidate(name: String, category: String, distanceM: Float) =
        SpotCandidate(Poi(name, name, 0.0, 0.0, category, emptyMap()), distanceM, false)

    @Test
    fun `no theme uses nearest order and theme uses category preference`() {
        val candidates = listOf(
            candidate("遠い公園", "公園", 100f),
            candidate("近い神社", "神社", 10f),
            candidate("近い公園", "公園", 20f),
            candidate("史跡", "史跡", 30f),
        )

        assertEquals(
            listOf("近い神社", "近い公園", "史跡"),
            PlanSuggestions.recommend(candidates, null).map { it.poi.name },
        )
        assertEquals("近い公園", PlanSuggestions.recommend(candidates, "公園").first().poi.name)
        assertEquals(3, PlanSuggestions.recommend(candidates, null).size)
        assertTrue(PlanSuggestions.recommend(emptyList(), null).isEmpty())
    }
}
