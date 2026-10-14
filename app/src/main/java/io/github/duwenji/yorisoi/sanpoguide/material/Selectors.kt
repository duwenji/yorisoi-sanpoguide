package io.github.duwenji.yorisoi.sanpoguide.material

import io.github.duwenji.yorisoi.core.format.material.CollectionPackage
import io.github.duwenji.yorisoi.core.format.material.Exposure
import io.github.duwenji.yorisoi.core.format.material.Fetch
import io.github.duwenji.yorisoi.core.format.material.ProducerDescriptor
import io.github.duwenji.yorisoi.core.format.material.Resource
import io.github.duwenji.yorisoi.core.format.material.ResourceReader
import io.github.duwenji.yorisoi.core.format.material.SubjectKind
import io.github.duwenji.yorisoi.core.format.material.Trust
import io.github.duwenji.yorisoi.core.format.AppIds
import io.github.duwenji.yorisoi.core.format.ChannelList
import io.github.duwenji.yorisoi.core.format.ListedCollection
import io.github.duwenji.yorisoi.core.format.Selector
import io.github.duwenji.yorisoi.core.format.StationFiles
import io.github.duwenji.yorisoi.core.format.StationMedia

/** A collection on the device that a selector may draw from, as its provider's list names it. */
class SelectedCollection(val listed: ListedCollection, val pkg: CollectionPackage, val files: StationFiles)

/**
 * Which collections a channel's selectors draw from (API-004「基準で選ぶ素材提供者」): listed by the
 * channel's provider, not revoked, granted to its publisher (D-27) and to this app (API-004 I-11),
 * shown by this app, not promotional (ADR-002 D-33: only a channel naming it may use one), and carrying the kind and a tag asked for. Best first (「複数の素材が当たったとき」): the producers
 * the selector names, in its order, then the most recently approved.
 */
object SelectorPlan {
    fun candidates(selector: Selector, list: ChannelList, publisher: String, appVersion: Int, appId: String = AppIds.SANPOGUIDE): List<ListedCollection> {
        val publishers = setOf(publisher) + list.formerPublisherIds(publisher)
        return list.collections.filter { c ->
            list.revokedCollections.none { it.id == c.id && it.covers(c.version) } &&
                (c.grants.publishers == null || publishers.any(c.grants::allows)) &&
                c.grants.allowsApp(appId) &&
                !c.promotional &&
                (c.minAppVersionFor(appId) ?: 0) <= appVersion &&
                c.tags.any { it in selector.tags } &&
                (c.media.isEmpty() || selector.type.json in c.media)
        }.sortedWith(
            compareBy<ListedCollection> { c -> selector.producers.indexOf(c.producer).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                .thenByDescending { it.approvedAt },
        )
    }

    /**
     * What to fetch ahead for [media]'s selectors (API-004 P-6): collections about the [region]
     * the device is in or one next to it, and those about nowhere in particular; best first,
     * up to 50MB in all. With no [region] (the location is unknown), only the latter.
     */
    fun toFetch(
        media: StationMedia, list: ChannelList, publisher: String, appVersion: Int, region: Set<String>?, appId: String = AppIds.SANPOGUIDE,
    ): List<ListedCollection> {
        val out = LinkedHashMap<String, ListedCollection>()
        var total = 0L
        for (selector in media.selectors) {
            for (c in candidates(selector, list, publisher, appVersion, appId)) {
                if (c.id in out) continue
                if (c.regions.isNotEmpty() && (region == null || c.regions.none { it in region })) continue
                if (total + c.resourcesSize > ResourceReader.MAX_PREFETCH_TOTAL) continue
                total += c.resourcesSize
                out[c.id] = c
            }
        }
        return out.values.toList()
    }

    /**
     * Whether [r], in a collection written in [lang], is what [selector] asks for: its kind, one of
     * its tags, its language ([channelLang] unless it names one), its length, and about the cue's
     * subject or about nothing, as it says.
     */
    fun matches(selector: Selector, r: Resource, lang: String, channelLang: String): Boolean =
        r.type == selector.type &&
            r.tags.any { it in selector.tags } &&
            lang == (selector.lang ?: channelLang) &&
            selector.maxDurationSec.let { max -> max == null || (r.durationSec ?: 0) <= max } &&
            (r.about != null) == selector.aboutTrigger
}

/**
 * The producer a selector makes (API-004 I-10): material from [collections], best first, that the
 * selector asks for and that is about the cue's subject (or about nothing, for `subject: none`).
 * Like the channel's own resources it was fetched ahead and checked, so producing only reads the
 * device (P-1). The order is fixed, so a spot gets the same material each time on a walk.
 */
class SelectorProducer(
    private val selector: Selector,
    private val collections: List<SelectedCollection>,
    private val channelLang: String,
    private val bytes: (SelectedCollection, Resource) -> ByteArray?,
    trial: Boolean,
    private val stage: Int,
) : Producer {
    override val descriptor = ProducerDescriptor(
        selector.alias, 1, setOf(selector.type), SubjectKind.entries.toSet(),
        if (trial) Trust.TRIAL else Trust.REVIEWED, Fetch.PREFETCH, Exposure.NONE, generated = false,
    )

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        var missing = false
        for (c in collections) {
            for (r in c.pkg.resources.items) {
                if (!SelectorPlan.matches(selector, r, c.pkg.manifest.lang, channelLang)) continue
                val about = r.about
                if (about != null && !SubjectMatch.matches(about, request.subject)) continue
                val read = readResource(
                    r, request, stage, { c.pkg.resources.texts[it.id] }, { bytes(c, it) }, descriptor.id, c.listed.producerName,
                )
                when (read) {
                    is ResourceRead.Ok -> return MaterialResult.Ready(read.material)
                    ResourceRead.Missing -> missing = true
                    ResourceRead.No -> Unit
                }
            }
        }
        return if (missing) MaterialResult.Unavailable(MaterialResult.Reason.NOT_PREPARED) else MaterialResult.None
    }
}
