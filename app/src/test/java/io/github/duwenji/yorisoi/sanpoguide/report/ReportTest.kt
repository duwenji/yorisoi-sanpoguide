package io.github.duwenji.yorisoi.sanpoguide.report

import io.github.duwenji.yorisoi.sanpoguide.station.BuiltInStations
import io.github.duwenji.yorisoi.kit.channel.StationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reports go to whoever answers for the channel, with the line but never the place (platform ADR-004 H-11). */
class ReportTest {
    private val standard = BuiltInStations.load().first { it.id == BuiltInStations.STANDARD }

    @Test
    fun `a built-in channel's problems go to the app, a listed one's to the list's operator`() {
        assertEquals(Report.APP_CONTACT, Report.target(standard).contact)
        val listed = BuiltInStations.load().first { it.id == "history" }
        // Built-in packages stand in for a listed channel; only its source matters here.
        val fromList = io.github.duwenji.yorisoi.kit.channel.Station(
            BuiltInStations.standardPackage(), BuiltInStations.standardPackage(), "p/x@1", id = "p/x",
            source = StationSource("p", "鎌倉のチャンネル", "鎌倉歴史散歩の会", contact = "channels@example.com"),
        )
        assertEquals("channels@example.com", Report.target(fromList).contact)
        assertEquals("鎌倉のチャンネル", Report.target(fromList).listName)
        // A list without a contact falls back to the app's.
        val noContact = io.github.duwenji.yorisoi.kit.channel.Station(
            BuiltInStations.standardPackage(), BuiltInStations.standardPackage(), "p/x@1", id = "p/x",
            source = StationSource("p", "リスト", "提供者"),
        )
        assertEquals(Report.APP_CONTACT, Report.target(noContact).contact)
        assertEquals(listed.name, Report.target(listed).channelName)
    }

    @Test
    fun `the draft names the channel and the line, and says it leaves the place out`() {
        val target = ReportTarget("標準", "standard@1", null, Report.APP_CONTACT)
        val body = Report.body(target, 1_760_600_000_000L to "この先の柵を越えると近道です。", "1.0.0")
        assertTrue(body.contains("チャンネル: 標準（standard@1）"))
        assertTrue(body.contains("発言: この先の柵を越えると近道です。"))
        assertTrue(body.contains("発言の時刻: 2025-10-16"))
        assertTrue(body.contains("位置は含めていません"))
        assertEquals("AI の発言について（標準）", Report.subject(target, 0L to "x"))
        assertEquals("チャンネルについて（標準）", Report.subject(target, null))
        assertFalse(Report.body(target, null, "1.0.0").contains("発言"))
    }
}
