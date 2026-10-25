package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import io.github.duwenji.yorisoi.kit.companion.SessionRecorder
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.sanpoguide.history.WalkJson
import io.github.duwenji.yorisoi.sanpoguide.history.WalkRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The export of the history with the developer's record of each walk (the record itself is platform kit-companion's). */
class WalkExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val diagnostics by lazy { SessionRecorder(File(tmp.root, "diagnostics"), startType = "walk_start", endType = "walk_end") }

    private fun types(events: JSONArray) = (0 until events.length()).map { events.getJSONObject(it).getString("type") }

    @Test
    fun `the export holds each walk in the history's format, with its record`() {
        val walk = WalkRecord(9L, 9L, 70_000L, 120.0, listOf(LatLon(35.0, 139.0)), emptyList(), emptyList())
        diagnostics.start(9L, emptyList())
        diagnostics.finish(9L)

        val json = JSONObject(WalkExport.toJson(listOf(walk), diagnostics::read, "1.0.0", 100L).toString())

        assertEquals(WalkExport.FORMAT, json.getString("format"))
        assertEquals(WalkExport.VERSION, json.getInt("formatVersion"))
        val exported = json.getJSONArray("walks").getJSONObject(0)
        assertEquals(walk, WalkJson.fromJson(exported))
        assertEquals(listOf("walk_start", "walk_end"), types(exported.getJSONArray("diagnostics")))
    }
}
