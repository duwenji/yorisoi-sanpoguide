package io.github.duwenji.sanpoguide.material

import io.github.duwenji.sanpoguide.material.format.CollectionPackage
import io.github.duwenji.sanpoguide.material.format.MaterialType
import io.github.duwenji.sanpoguide.material.format.Resource
import io.github.duwenji.sanpoguide.material.format.ResourceLocation
import io.github.duwenji.sanpoguide.material.format.ResourceReader
import io.github.duwenji.sanpoguide.station.format.CollectionStatus
import io.github.duwenji.sanpoguide.station.format.Presentation
import io.github.duwenji.sanpoguide.station.format.Reference
import io.github.duwenji.sanpoguide.station.format.StationFiles
import io.github.duwenji.sanpoguide.station.format.StationMedia

/**
 * What a third party's channel can show on this device (ADR-002 T-8a): its own resources, the
 * collections it declares as resolved against its provider's list, and the outside resources
 * fetched ahead. Anything missing (not fetched yet, revoked, a later app stage) reads as null,
 * and the cue moves on to its next candidate (API-004 I-7).
 *
 * @param collections by alias, as `CollectionReferences.resolve` gave them.
 * @param collectionFiles the files of each usable collection, by alias.
 * @param stored outside resources fetched ahead, by SHA-256.
 */
class ChannelMaterials(
    val media: StationMedia,
    val collections: Map<String, CollectionStatus>,
    private val own: StationFiles,
    private val collectionFiles: Map<String, StationFiles>,
    private val stored: (sha256: String) -> ByteArray?,
) {
    /** A usable collection by its alias in this channel, or null. */
    fun collection(alias: String): CollectionStatus.Usable? = collections[alias] as? CollectionStatus.Usable

    /** The text of a text resource: the channel's own when [alias] is null, else the collection's. */
    fun text(resource: Resource, alias: String?): String? =
        (if (alias == null) media.resources.texts else collection(alias)?.pkg?.resources?.texts)?.get(resource.id)

    /** The bytes of an image (or later audio and video): from its package, or as fetched ahead. */
    fun bytes(resource: Resource, alias: String?): ByteArray? = when (val at = resource.location) {
        is ResourceLocation.File -> (if (alias == null) own else collectionFiles[alias])?.read(at.path)
        is ResourceLocation.External -> stored(at.sha256)
    }
}

/** An outside resource to fetch when the channel is chosen (API-003 F-10). */
data class Prefetch(val url: String, val sha256: String, val size: Long)

/**
 * Which outside resources to fetch ahead (API-003 F-10): the ones a cue may show, of the types the
 * app shows at its [stage] (video is fetched when tapped, never ahead), up to 50MB in all
 * (API-004 P-6). A collection named without an id may give any of its resources, so all of those
 * its presentation can show are fetched.
 *
 * @param collections usable collections by alias.
 */
object MaterialPlan {
    fun prefetch(media: StationMedia, collections: Map<String, CollectionPackage>, stage: Int): List<Prefetch> {
        val out = LinkedHashMap<String, Prefetch>()
        var total = 0L
        fun consider(r: Resource, presentation: Presentation) {
            val at = r.location as? ResourceLocation.External ?: return
            if (r.type == MaterialType.VIDEO || r.type !in presentation.presentAs.types) return
            if (presentation.presentAs.stageOf(r.type) > stage || at.sha256 in out) return
            if (total + at.size > ResourceReader.MAX_PREFETCH_TOTAL) return
            total += at.size
            out[at.sha256] = Prefetch(at.url, at.sha256, at.size)
        }
        for (p in media.cues.flatMap { it.present }) {
            for (ref in p.items.flatten()) {
                when (ref) {
                    is Reference.Self -> media.resources[ref.id]?.let { consider(it, p) }
                    is Reference.Alias -> {
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
