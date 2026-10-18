package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.sanpoguide.history.WalkJson
import io.github.duwenji.yorisoi.sanpoguide.history.WalkRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WalkDiagnosticsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private val dir by lazy { File(tmp.root, "diagnostics") }
    private val diagnostics by lazy { WalkDiagnostics(dir, clock = { now }) }

    private fun types(events: JSONArray) = (0 until events.length()).map { events.getJSONObject(it).getString("type") }

    @Test
    fun `a walk's events are read back in order, with their time and fields`() {
        diagnostics.start(7L, listOf("station" to "builtin:standard@1"))
        now = 2_000L
        diagnostics.event("said", "text" to "こんにちは", "spot" to null)
        diagnostics.finish(7L)

        val events = diagnostics.read(7L)
        assertEquals(listOf("walk_start", "said", "walk_end"), types(events))
        assertEquals("builtin:standard@1", events.getJSONObject(0).getString("station"))
        val said = events.getJSONObject(1)
        assertEquals(2_000L, said.getLong("t"))
        assertEquals("こんにちは", said.getString("text"))
        assertTrue(said.isNull("spot"))
    }

    @Test
    fun `nothing is written while no walk is recorded`() {
        diagnostics.event("said", "text" to "誰も聞いていない")
        assertFalse(diagnostics.recording)
        assertEquals(0, diagnostics.read(7L).length())
    }

    @Test
    fun `finishing an earlier walk leaves the later one recording`() {
        diagnostics.start(1L, emptyList())
        diagnostics.start(2L, emptyList())
        diagnostics.finish(1L)
        diagnostics.event("location")

        assertTrue(diagnostics.recording)
        assertEquals(listOf("walk_start", "location"), types(diagnostics.read(2L)))
    }

    @Test
    fun `a line cut short is skipped, not the whole walk`() {
        diagnostics.start(3L, emptyList())
        diagnostics.finish(3L)
        diagnostics.read(3L) // waits for the writes
        File(dir, "3.jsonl").appendText("{\"t\":5,\"type\":\"sai")

        assertEquals(listOf("walk_start", "walk_end"), types(diagnostics.read(3L)))
    }

    @Test
    fun `only the latest walks are kept`() {
        (1L..WalkDiagnostics.MAX_WALKS + 2L).forEach { id ->
            diagnostics.start(id, emptyList())
            diagnostics.finish(id)
        }
        diagnostics.read(1L) // waits for the writes

        assertEquals(WalkDiagnostics.MAX_WALKS, dir.listFiles()!!.size)
        assertEquals(0, diagnostics.read(2L).length())
        assertEquals(2, diagnostics.read(WalkDiagnostics.MAX_WALKS + 2L).length())
    }

    @Test
    fun `clear removes every record`() {
        diagnostics.start(4L, emptyList())
        diagnostics.finish(4L)
        diagnostics.clear()
        assertEquals(0, diagnostics.read(4L).length())
    }

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
