package io.github.duwenji.yorisoi.sanpoguide.material

import io.github.duwenji.yorisoi.kit.channel.material.Content
import io.github.duwenji.yorisoi.kit.channel.material.Material
import io.github.duwenji.yorisoi.kit.channel.material.Shown
import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.core.format.PresentAs

/** One thing a cue shows by itself, in the cue's order. */
sealed interface CueStep {
    /** Said in the queue the companion speaks from (API-003 F-13), and kept in the feed. */
    data class Speak(val material: Material) : CueStep

    /** A line in the feed: a text, or an image. Shown only; it doesn't turn the screen on. */
    data class Card(val material: Material) : CueStep

    /** A notification: a text, or an image. */
    data class Notify(val material: Material) : CueStep
}

/**
 * What a cue holds for the user to open by tapping its card or its notification: the sheet's
 * material and an image for the full screen. Neither ever opens by itself (API-003「見せ方」).
 */
data class CueDetail(val id: String, val sheet: List<Material>, val fullscreen: Material?)

/** A cue as it is shown: the [steps] in order, and the [detail] they lead to, if any. */
data class CueLayout(val steps: List<CueStep>, val detail: CueDetail?) {
    companion object {
        /**
         * Lays out what a cue came to. Each presentation shows its first item; a sheet shows
         * them all. Presentations of a later app stage are already left out by [CuePresenter].
         */
        fun of(id: String, shown: List<Shown>): CueLayout {
            val steps = mutableListOf<CueStep>()
            val sheet = mutableListOf<Material>()
            var fullscreen: Material? = null
            for (item in shown) {
                val first = item.items.firstOrNull() ?: continue
                when (item.presentAs) {
                    PresentAs.SPEAK -> if (first.content is Content.Text) steps += CueStep.Speak(first)
                    PresentAs.CARD -> steps += CueStep.Card(first)
                    PresentAs.NOTIFY -> steps += CueStep.Notify(first)
                    PresentAs.SHEET -> sheet += item.items
                    PresentAs.FULLSCREEN -> if (fullscreen == null && first.type == MaterialType.IMAGE) fullscreen = first
                    else -> Unit
                }
            }
            val detail = if (sheet.isNotEmpty() || fullscreen != null) CueDetail(id, sheet, fullscreen) else null
            return CueLayout(steps, detail)
        }
    }
}

/** How walking and standing are told apart for what may be shown (API-003「見せ方」). */
object Pace {
    /** At or above this speed (m/s) the user is walking. */
    const val WALKING_MPS = 0.5f

    /** A location older than this says nothing about now. */
    const val FRESH_MS = 60_000L

    /** Whether the user is walking now: a fresh location moving at walking speed. */
    fun walking(speedMps: Float?, ageMs: Long): Boolean =
        speedMps != null && speedMps >= WALKING_MPS && ageMs <= FRESH_MS
}

/**
 * The line shown with a material (API-003 F-11): an image's author and license, for material
 * from a collection whose it is, and for the AI's text whose texts it was written from
 * (API-004 `basedOn`). Spoken lines never read it.
 */
fun creditLine(m: Material): String? {
    val c = m.credit ?: return null
    val license = c.license?.let(::licenseLabel)
    val via = c.via?.let { "提供: $it" }
    val basedOn = m.basedOn.takeIf { it.isNotEmpty() }?.let { "${it.joinToString("・")}の資料をもとに作成" }
    val parts = if (m.type == MaterialType.TEXT) {
        listOfNotNull(c.author?.let { "文: $it" }, license, via, basedOn)
    } else {
        listOfNotNull("${kindLabel(m.type)}: ${c.author ?: "作者不明"}", license, via)
    }
    return parts.joinToString(" / ").ifEmpty { null }
}


private fun kindLabel(type: MaterialType) = when (type) {
    MaterialType.AUDIO -> "音声"
    MaterialType.VIDEO -> "映像"
    else -> "画像"
}

/** API-003 `credit.license`: an SPDX id is shown as it is. */
private fun licenseLabel(license: String): String? = when (license) {
    "PD" -> "パブリックドメイン"
    "permission" -> "許諾を得て掲載"
    // The AI's own text: its author already says 「AI（model）」.
    "generated" -> null
    else -> license
}
