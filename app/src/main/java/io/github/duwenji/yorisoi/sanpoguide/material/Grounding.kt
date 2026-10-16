package io.github.duwenji.yorisoi.sanpoguide.material

import io.github.duwenji.yorisoi.core.format.ListedCollection
import io.github.duwenji.yorisoi.core.format.material.CollectionPackage
import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.core.format.material.Resource
import io.github.duwenji.yorisoi.sanpoguide.station.Station

/**
 * A text from a reviewed collection that the AI may take as fact (API-004「AI の根拠」, platform
 * ADR-004 H-3): what it says, and whose it is for the credit line.
 */
data class Ground(
    val text: String,
    val producerName: String,
    val author: String?,
    val collection: String,
    val version: Int,
    val resource: String,
)

/**
 * Gathers the grounds for a request to a generated producer (API-004「AI の根拠」): texts about the
 * same subject from the collections this channel may use whose producers let the AI take them as
 * grounds (`grants.grounding`, I-13). Only what was fetched ahead is read; nothing is fetched for it.
 */
object Grounding {
    const val MAX_GROUNDS = 3
    const val MAX_CHARS = 1_500

    fun collect(subject: Subject, station: Station): List<Ground> {
        if (subject !is Subject.Spot && subject !is Subject.Point) return emptyList()
        val materials = station.materials ?: return emptyList()
        // The collections the channel names first, in its order; then those its selectors draw from, best first.
        val sources = materials.media.collections.mapNotNull { use -> materials.collection(use.alias)?.let { it.listed to it.pkg } } +
            materials.selected.values.flatten().map { it.listed to it.pkg }
        val grounds = mutableListOf<Ground>()
        var left = MAX_CHARS
        for ((listed, pkg) in sources.distinctBy { (listed, _) -> listed.id }) {
            if (!listed.grants.grounding) continue
            val used = mutableSetOf<String>()
            for (r in pkg.resources.items) {
                val about = r.about ?: continue
                if (!SubjectMatch.matches(about, subject)) continue
                val (id, text) = textOf(r, pkg) ?: continue
                // An audio and its transcript are one text.
                if (!used.add(id)) continue
                val cut = text.take(left)
                grounds += ground(cut, listed, r, id)
                left -= cut.length
                if (grounds.size == MAX_GROUNDS || left <= 0) return grounds
            }
        }
        return grounds
    }

    /** What the AI may read of [r]: a text's body, an image's alt, an audio's or video's transcript. */
    private fun textOf(r: Resource, pkg: CollectionPackage): Pair<String, String>? {
        val texts = pkg.resources.texts
        return when (r.type) {
            MaterialType.TEXT -> texts[r.id]?.let { r.id to it }
            MaterialType.IMAGE -> r.alt?.let { r.id to it }
            MaterialType.AUDIO, MaterialType.VIDEO -> r.transcript?.let { id -> texts[id]?.let { id to it } }
        }?.takeIf { it.second.isNotBlank() }
    }

    private fun ground(text: String, listed: ListedCollection, r: Resource, id: String) =
        Ground(text, listed.producerName, r.credit?.author, listed.id, listed.version, id)
}

/** [this] with its grounds when [generated] producers answer it (API-004 I-14); others never see them. */
fun MaterialRequest.groundedFor(generated: Boolean): MaterialRequest =
    if (!generated || grounding.isNotEmpty()) this else copy(grounding = Grounding.collect(subject, context.station))
