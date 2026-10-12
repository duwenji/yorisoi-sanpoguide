package io.github.duwenji.sanpoguide.material

import io.github.duwenji.michizure.kit.geo.RouteGeometry
import io.github.duwenji.michizure.kit.geo.LatLon
import io.github.duwenji.michizure.core.format.material.About
import io.github.duwenji.michizure.core.format.material.Exposure
import io.github.duwenji.michizure.core.format.material.Fetch
import io.github.duwenji.michizure.core.format.material.MaterialType
import io.github.duwenji.michizure.core.format.material.ProducerDescriptor
import io.github.duwenji.michizure.core.format.material.Resource
import io.github.duwenji.michizure.core.format.material.Resources
import io.github.duwenji.michizure.core.format.material.SubjectKind
import io.github.duwenji.michizure.core.format.material.Trust

/**
 * Material a third party made ahead: a channel's own resources (`self`) or a collection it
 * declares (by its alias). Everything was fetched and checked when the channel was chosen
 * (ADR-002 T-8a), so producing only reads the device and tells no one the subject (API-004 P-1).
 *
 * @param stage the app stage (API-003 M-1): resources of a later one are passed over.
 * @param producerName shown beside the credit (API-002 `collections[].producerName`); null for the channel's own.
 * @param promotedBy whose promotion these are (ADR-002 D-32): a promotional collection's producer, or a
 *   promotional channel's publisher for its own; null when they aren't.
 * @param hidePromotions the user's setting (D-34): promotion is then passed over for the next candidate.
 */
class ResourceProducer(
    id: String,
    private val resources: Resources,
    private val text: (Resource) -> String?,
    private val bytes: (Resource) -> ByteArray?,
    trial: Boolean,
    private val stage: Int,
    private val producerName: String? = null,
    private val promotedBy: String? = null,
    private val hidePromotions: () -> Boolean = { false },
) : Producer {
    override val descriptor = ProducerDescriptor(
        id, 1, MaterialType.entries.toSet(), setOf(SubjectKind.SPOT, SubjectKind.POINT),
        if (trial) Trust.TRIAL else Trust.REVIEWED, Fetch.PREFETCH, Exposure.NONE, generated = false,
    )

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        // API-004 I-7: promotion the user turned off is material this producer can't show.
        if (promotedBy != null && hidePromotions()) return MaterialResult.None
        val id = request.id
        val candidates = if (id != null) {
            listOfNotNull(resources[id])
        } else {
            resources.items.filter { r -> r.about?.let { SubjectMatch.matches(it, request.subject) } == true }
        }
        var missing = false
        for (r in candidates) {
            when (val read = readResource(r, request, stage, text, bytes, descriptor.id, producerName, promotedBy)) {
                is ResourceRead.Ok -> return MaterialResult.Ready(read.material)
                ResourceRead.Missing -> missing = true
                ResourceRead.No -> Unit
            }
        }
        // Not fetched yet (a metered network, or a failure): the next Wi-Fi brings it.
        return if (missing) MaterialResult.Unavailable(MaterialResult.Reason.NOT_PREPARED) else MaterialResult.None
    }
}

/** A resource read for a request: its material, not fetched yet, or not something this request can show. */
internal sealed interface ResourceRead {
    data class Ok(val material: Material) : ResourceRead
    data object Missing : ResourceRead
    data object No : ResourceRead
}

/**
 * Reads [r] as material for [request]: of a type it accepts and the app's [stage] shows, a text
 * within its length. [producerName] is shown beside the credit (a collection's producer).
 */
internal fun readResource(
    r: Resource, request: MaterialRequest, stage: Int, text: (Resource) -> String?, bytes: (Resource) -> ByteArray?,
    producerId: String, producerName: String?, promotedBy: String? = null,
): ResourceRead {
    if (r.type !in request.accepts || stageOf(r.type) > stage) return ResourceRead.No
    val content = when (r.type) {
        MaterialType.TEXT -> text(r)?.takeIf { t -> request.maxTextChars?.let { t.codePointCount(0, t.length) <= it } ?: true }?.let(Content::Text)
        MaterialType.IMAGE -> bytes(r)?.let(Content::EncodedImage) ?: return ResourceRead.Missing
        // Audio and video come with later app stages.
        else -> null
    } ?: return ResourceRead.No
    // A collection's text often has no credit of its own; whose it is is still shown.
    val credit = r.credit?.let { Credit(it.author, it.license, it.source, via = producerName) }
        ?: producerName?.let { Credit(null, null, via = it) }
    return ResourceRead.Ok(Material(r.type, content, producerId, credit, alt = r.alt, promotedBy = promotedBy))
}

private fun stageOf(type: MaterialType) = when (type) {
    MaterialType.VIDEO -> 3
    MaterialType.AUDIO -> 2
    else -> 1
}

/**
 * Whether material is about a cue's subject (API-004 `about`, matched as API-003 matches cues).
 * A spot is the same by its OSM id, or by its name: the spot is already within reach of the user,
 * so a spot of that name there is the one meant (the area and the node of one place have
 * different ids). A point holds what lies within its radius.
 */
object SubjectMatch {
    fun matches(about: About, subject: Subject): Boolean = when (subject) {
        is Subject.Spot -> when (about) {
            is About.Spot -> about.osm == subject.poi.id || (about.name != null && about.name == subject.poi.name &&
                distance(about.lat, about.lon, subject.poi.lat, subject.poi.lon) <= SAME_NAME_WITHIN_M)
            is About.Point -> distance(about.lat, about.lon, subject.poi.lat, subject.poi.lon) <= about.radiusM
        }
        is Subject.Point -> when (about) {
            is About.Spot -> distance(about.lat, about.lon, subject.lat, subject.lon) <= subject.radiusM
            is About.Point -> distance(about.lat, about.lon, subject.lat, subject.lon) <= maxOf(about.radiusM, subject.radiusM)
        }
        else -> false
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double) = RouteGeometry.distanceM(LatLon(lat1, lon1), LatLon(lat2, lon2))

    /** As for a revisit (API-003「きっかけ」`spot.name`). */
    const val SAME_NAME_WITHIN_M = 200.0
}
