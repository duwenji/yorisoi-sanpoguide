package io.github.duwenji.yorisoi.sanpoguide.history

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.sanpoguide.companion.PlannedStop
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkPlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WalkJsonTest {
    private val walk = WalkRecord(
        id = 1L, startedAt = 1L, endedAt = 2_000_000L, distanceM = 1234.5,
        route = listOf(LatLon(35.3, 139.5)),
        visits = listOf(SpotVisit("way/1", "鶴岡八幡宮", "神社", 500L, "朱色の本宮が…", 35.32, 139.55, station = "builtin:history@1")),
        stations = listOf(StationSegment("builtin:standard@1", 1L), StationSegment("builtin:history@1", 400L)),
        plan = WalkPlan(
            themeCategory = "公園",
            durationMinutes = 60,
            distanceKm = 3,
            note = "水辺を歩きたい",
            stops = listOf(PlannedStop("way/2", "中央公園", "公園", 35.0, 139.0)),
        ),
    )

    @Test
    fun `a walk survives a round trip, channels included`() {
        assertEquals(walk, WalkJson.fromJson(JSONObject(WalkJson.toJson(walk).toString())))
    }

    @Test
    fun `a walk keeps the frequent ways gone along (platform REQ-FREQUENT-WAYS F-12)`() {
        val withWays = walk.copy(ways = listOf(WalkWay("w1", "川沿いの道", 4, new = false), WalkWay("w2", "中央公園のあたりの道", 3, new = true)))
        assertEquals(withWays, WalkJson.fromJson(JSONObject(WalkJson.toJson(withWays).toString())))
        // Walks kept before there were ways.
        assertEquals(emptyList<WalkWay>(), WalkJson.fromJson(JSONObject(WalkJson.toJson(walk).toString()).apply { remove("ways") }).ways)
    }

    @Test
    fun `a legacy walk without plan loads with an empty plan`() {
        val legacy = JSONObject(
            """{"id":1,"startedAt":1,"endedAt":2000000,"distanceM":0.0,"route":[],"visits":[],"stations":[]}"""
        )

        assertEquals(WalkPlan(), WalkJson.fromJson(legacy).plan)
    }
}
