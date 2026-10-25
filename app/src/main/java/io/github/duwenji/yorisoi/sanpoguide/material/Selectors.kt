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
import io.github.duwenji.yorisoi.kit.channel.SelectedCollection
import io.github.duwenji.yorisoi.kit.channel.material.SelectorPlan

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
