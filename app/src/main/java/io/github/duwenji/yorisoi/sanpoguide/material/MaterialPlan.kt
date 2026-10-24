package io.github.duwenji.yorisoi.sanpoguide.material

import io.github.duwenji.yorisoi.core.format.material.CollectionPackage
import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.core.format.material.Resource
import io.github.duwenji.yorisoi.core.format.material.ResourceLocation
import io.github.duwenji.yorisoi.core.format.material.ResourceReader
import io.github.duwenji.yorisoi.core.format.CollectionStatus
import io.github.duwenji.yorisoi.core.format.Presentation
import io.github.duwenji.yorisoi.core.format.Reference
import io.github.duwenji.yorisoi.core.format.StationFiles
import io.github.duwenji.yorisoi.core.format.StationMedia

/** An outside resource to fetch when the channel is chosen (API-003 F-10). */
data class Prefetch(val url: String, val sha256: String, val size: Long)

/**
 * Which outside resources to fetch ahead (API-003 F-10): the ones a cue may show, of the types the
 * app shows at its [stage] (video is fetched when tapped, never ahead), up to 50MB in all
 * (API-004 P-6). A collection named without an id may give any of its resources, so all of those
 * its presentation can show are fetched.
 *
 * The selectors' outside resources count against a 50MB of their own (P-6), best collection first.
 *
 * @param collections usable collections by alias.
 * @param selected the collections each selector draws from on the device, best first, by its alias.
 */
object MaterialPlan {
    fun prefetch(
        media: StationMedia, collections: Map<String, CollectionPackage>, stage: Int,
        selected: Map<String, List<CollectionPackage>> = emptyMap(), lang: String = "ja",
    ): List<Prefetch> {
        val out = LinkedHashMap<String, Prefetch>()
        var total = 0L
        var selectedTotal = 0L
        fun consider(r: Resource, presentation: Presentation, bySelector: Boolean = false) {
            val at = r.location as? ResourceLocation.External ?: return
            if (r.type == MaterialType.VIDEO || r.type !in presentation.presentAs.types) return
            if (presentation.presentAs.stageOf(r.type) > stage || at.sha256 in out) return
            val sum = if (bySelector) selectedTotal else total
            if (sum + at.size > ResourceReader.MAX_PREFETCH_TOTAL) return
            if (bySelector) selectedTotal += at.size else total += at.size
            out[at.sha256] = Prefetch(at.url, at.sha256, at.size)
        }
        val selectors = media.selectors.associateBy { it.alias }
        for (p in media.cues.flatMap { it.present }) {
            for (ref in p.items.flatten()) {
                when (ref) {
                    is Reference.Self -> media.resources[ref.id]?.let { consider(it, p) }
                    is Reference.Alias -> {
                        selectors[ref.alias]?.let { selector ->
                            for (pkg in selected[ref.alias].orEmpty()) {
                                pkg.resources.items.filter { SelectorPlan.matches(selector, it, pkg.manifest.lang, lang) }.forEach { consider(it, p, bySelector = true) }
                            }
                        }
                        val pkg = collections[ref.alias] ?: continue
                        val id = ref.id
                        if (id != null) pkg.resources[id]?.let { consider(it, p) } else pkg.resources.items.forEach { consider(it, p) }
                    }
                    is Reference.BuiltIn -> Unit
                }
            }
        }
        return out.values.toList()
    }
}
