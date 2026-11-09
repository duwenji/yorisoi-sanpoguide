package io.github.duwenji.yorisoi.sanpoguide.guide

import io.github.duwenji.yorisoi.kit.geo.NearbyHighlight
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.sanpoguide.companion.Surroundings
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkEvent
import io.github.duwenji.yorisoi.sanpoguide.companion.aboutWhereYouAre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the AI is told of the spots around and of the map's data (platform REQ-NEARBY-TALK F-8, F-9). */
class NearbyVarsTest {
    private val temple = Poi("node/1", "高徳院", 35.3168, 139.5357, "寺院", mapOf("amenity" to "place_of_worship", "religion" to "buddhist"))
    private val around = listOf(
        NearbyHighlight(Poi("node/2", "長谷寺", 35.3125, 139.5333, "寺院", emptyMap()), 450, "南西", "右手"),
        NearbyHighlight(Poi("node/3", "長谷駅", 35.3121, 139.5363, "駅", emptyMap()), 550, "南", null),
    )

    @Suppress("UNCHECKED_CAST")
    private fun spots(vars: Map<String, Any?>) = (vars["nearby"] as Map<String, Any?>)["spots"] as List<Map<String, Any?>>

    @Test
    fun `the guide is told the spots around by name and kind, not where they are`() {
        val vars = GuidePrompt.spotVars(temple, 30, nearby = around + NearbyHighlight(temple, 50, "北", null))
        // Not the spot itself, should it be in the list.
        assertEquals(listOf(mapOf("name" to "長谷寺", "category" to "寺院"), mapOf("name" to "長谷駅", "category" to "駅")), spots(vars))
        assertNull(GuidePrompt.spotVars(temple, 30)["nearby"])
    }

    @Test
    fun `the guide is told what the map's data says of the spot`() {
        @Suppress("UNCHECKED_CAST")
        val lines = (GuidePrompt.spotVars(temple, 30)["facts"] as Map<String, Any?>)["lines"] as List<String>
        assertTrue(lines.any { it.startsWith("宗教: ") })
        assertNull(GuidePrompt.spotVars(Poi("node/9", "祠", 0.0, 0.0, "神社", emptyMap()), 30)["facts"])
    }

    @Test
    fun `the companion is told how far and which way`() {
        val vars = mapOf("nearby" to Surroundings.companionVars(around))
        assertEquals(mapOf("name" to "長谷寺", "category" to "寺院", "distance_m" to 450, "compass" to "南西", "side" to "右手"), spots(vars).first())
        assertNull(Surroundings.companionVars(emptyList()))
    }

    @Test
    fun `only lines about where the user is talk of the spots around`() {
        assertTrue(TalkEvent.Start.aboutWhereYouAre)
        assertTrue(TalkEvent.Milestone.aboutWhereYouAre)
        assertTrue(TalkEvent.Rest(4).aboutWhereYouAre)
        assertTrue(TalkEvent.Sunset(20).aboutWhereYouAre)
        assertFalse(TalkEvent.Revisit(temple, emptyList()).aboutWhereYouAre)
    }
}
