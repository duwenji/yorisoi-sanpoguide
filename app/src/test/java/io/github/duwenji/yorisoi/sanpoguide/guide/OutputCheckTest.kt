package io.github.duwenji.yorisoi.sanpoguide.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Replies the app won't read out (platform ADR-004 H-6 ③). */
class OutputCheckTest {
    @Test
    fun `URLs, email addresses and phone numbers are caught, in any width`() {
        assertEquals("a URL", OutputCheck.violation("詳しくは https://example.com で"))
        assertEquals("a URL", OutputCheck.violation("ｗｗｗ．example．com をご覧ください"))
        assertEquals("an email address", OutputCheck.violation("問い合わせは info@example.co.jp まで"))
        assertEquals("a phone number", OutputCheck.violation("電話は0467-22-0000です"))
        assertEquals("a phone number", OutputCheck.violation("０１２０ー１２３ー４５６へ"))
        assertEquals("a phone number", OutputCheck.violation("(0467)22-0000"))
        assertEquals("a phone number", OutputCheck.violation("09012345678"))
    }

    @Test
    fun `ordinary narration passes`() {
        assertNull(OutputCheck.violation("本宮は1180年に今の場所へ移されました。石段は61段あります。"))
        assertNull(OutputCheck.violation("ここから約450m、徒歩で6分ほどです。コーヒーの香りがします。"))
        assertNull(OutputCheck.violation("10月16日、気温は18℃です。"))
    }

    @Test
    fun `stage directions about the delivery are taken out`() {
        assertEquals(
            "そろそろ雨にぬれた路地の先に、日輪寺が見えてくる頃ですね。",
            OutputCheck.withoutStageDirections("（しっとりと、落ち着いた口調で）そろそろ雨にぬれた路地の先に、日輪寺が見えてくる頃ですね。"),
        )
        assertEquals(
            "門が見えてきました。ここは江戸のころからの古いお寺です。",
            OutputCheck.withoutStageDirections("門が見えてきました。(少し間を置いて) ここは江戸のころからの古いお寺です。"),
        )
        assertEquals("いい風ですね。", OutputCheck.withoutStageDirections("（明るい声で）いい風ですね。"))
    }

    @Test
    fun `readings and other brackets stay`() {
        val reading = "すぐ近くにある「吽形（うんぎょう）」というアート作品ですね。"
        assertEquals(reading, OutputCheck.withoutStageDirections(reading))
        val aside = "浅草寺（せんそうじ）の本堂です。（本堂は戦後に再建されました）"
        assertEquals(aside, OutputCheck.withoutStageDirections(aside))
    }
}
