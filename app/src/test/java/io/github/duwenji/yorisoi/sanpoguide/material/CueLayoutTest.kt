package io.github.duwenji.yorisoi.sanpoguide.material

import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.core.format.PresentAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a cue's material is laid out and credited (API-003「見せ方」). */
class CueLayoutTest {
    private fun text(t: String, credit: Credit? = null) = Material(MaterialType.TEXT, Content.Text(t), "self", credit)
    private fun image(alt: String, credit: Credit? = Credit("不明", "PD")) =
        Material(MaterialType.IMAGE, Content.EncodedImage(byteArrayOf(1)), "self", credit, alt)

    @Test
    fun `steps keep the cue's order and the sheet and full screen only wait for a tap`() {
        val intro = text("鶴岡八幡宮の参道です")
        val photo = image("明治の本宮")
        val note = text("段葛は明治に整えられました")
        val layout = CueLayout.of(
            "c#1@0",
            listOf(
                Shown(PresentAs.SPEAK, listOf(intro)),
                Shown(PresentAs.CARD, listOf(photo)),
                Shown(PresentAs.SHEET, listOf(note, photo)),
                Shown(PresentAs.FULLSCREEN, listOf(photo)),
                Shown(PresentAs.NOTIFY, listOf(text("近くに古い写真があります"))),
            ),
        )
        assertEquals(
            listOf(CueStep.Speak(intro), CueStep.Card(photo), CueStep.Notify(text("近くに古い写真があります"))),
            layout.steps,
        )
        assertEquals(CueDetail("c#1@0", listOf(note, photo), photo), layout.detail)
    }

    @Test
    fun `a cue with nothing to open has no detail, and only an image goes full screen`() {
        val layout = CueLayout.of("c#2@0", listOf(Shown(PresentAs.CARD, listOf(text("案内"))), Shown(PresentAs.FULLSCREEN, listOf(text("文字")))))
        assertEquals(listOf(CueStep.Card(text("案内"))), layout.steps)
        assertNull(layout.detail)
    }

    @Test
    fun `credits name the author, the license in words and whose material it is`() {
        assertEquals("画像: 不明 / パブリックドメイン", creditLine(image("本宮")))
        assertEquals(
            "画像: 鎌倉歴史散歩の会 / 許諾を得て掲載 / 提供: 鎌倉市観光協会",
            creditLine(image("本宮", Credit("鎌倉歴史散歩の会", "permission", via = "鎌倉市観光協会"))),
        )
        assertEquals("画像: 作者不明 / CC-BY-4.0", creditLine(image("本宮", Credit(null, "CC-BY-4.0"))))
        assertEquals("提供: 鎌倉市観光協会", creditLine(text("案内", Credit(null, null, via = "鎌倉市観光協会"))))
        assertNull(creditLine(text("案内")))
    }

    @Test
    fun `walking is a fresh location at walking speed`() {
        assertTrue(Pace.walking(0.5f, 0))
        assertTrue(Pace.walking(1.2f, Pace.FRESH_MS))
        assertFalse(Pace.walking(0.4f, 0))
        assertFalse(Pace.walking(null, 0))
        // An old location says nothing of now.
        assertFalse(Pace.walking(1.2f, Pace.FRESH_MS + 1))
    }
}
