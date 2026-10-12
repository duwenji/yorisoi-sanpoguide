package io.github.duwenji.michizure.core.format

import io.github.duwenji.michizure.core.format.material.CollectionPackage
import io.github.duwenji.michizure.core.format.material.MaterialType

/** Whether a collection a channel declares can be used (API-004 確かめる手順 3). */
sealed interface CollectionStatus {
    /** On the list and granted; [pkg] is the collection read at the listed version. */
    data class Usable(val listed: ListedCollection, val pkg: CollectionPackage) : CollectionStatus
    /** On the list and granted, but its package hasn't been read yet (the app fetches it next). */
    data class NotFetched(val listed: ListedCollection) : CollectionStatus
    data class Unusable(val code: RejectCode, val detail: String) : CollectionStatus
}

/** A reference into a collection that names nothing there, or something its presentation can't show (手順 4). */
data class ReferenceProblem(val cue: String, val reference: Reference.Alias, val code: RejectCode, val detail: String)

/** The outcome of 手順 3・4 for one channel. */
data class CollectionResolution(val statuses: Map<String, CollectionStatus>, val problems: List<ReferenceProblem>) {
    /**
     * The first reason to turn the channel away, for callers that need every reference sound: the
     * management system when a channel is submitted, and the app when it takes a third party's
     * channel in (API-003 1.2 手順 9a). Not yet fetched collections don't count.
     */
    fun rejection(): Pair<RejectCode, String>? =
        statuses.entries.firstNotNullOfOrNull { (alias, s) -> (s as? CollectionStatus.Unusable)?.let { it.code to "$alias: ${it.detail}" } }
            ?: problems.firstOrNull()?.let { it.code to "${it.cue}: ${it.detail}" }

    /** The types a collection reference may bring, for [MediaStage.required]. */
    fun types(alias: String, id: String?): Set<MaterialType> {
        val pkg = (statuses[alias] as? CollectionStatus.Usable)?.pkg ?: return emptySet()
        return if (id != null) setOfNotNull(pkg.resources[id]?.type) else pkg.resources.items.map { it.type }.toSet()
    }
}

/**
 * Checks the collections a channel uses against its provider's list (API-004 確かめる手順 3・4).
 * Shared by the management system, which needs every reference sound before approving, and the
 * app, which skips just the candidates it can't use (ADR-002 D-12).
 */
object CollectionReferences {
    /**
     * @param publisher the channel's publisher; a grant to one of its former ids (`publisherChange`)
     *   still counts (API-004 W-13). Null for a built-in channel, which any grant to everyone allows.
     * @param packages the collections read so far, by id, at the version the list names.
     * @param appId the app resolving, whose id a collection's `grants.apps` must hold (API-004 1.5
     *   I-11); null for the management system, which reviews a channel for every app.
     */
    fun resolve(
        media: StationMedia, publisher: String?, list: ChannelList, packages: Map<String, CollectionPackage>, appId: String? = null,
    ): CollectionResolution {
        val publisherIds = publisher?.let { setOf(it) + list.formerPublisherIds(it) }.orEmpty()
        val statuses = media.collections.associate { use -> use.alias to status(use, publisherIds, list, packages[use.id], appId) }
        val problems = media.cues.flatMap { cue ->
            cue.present.flatMap { p ->
                p.items.flatten().filterIsInstance<Reference.Alias>().mapNotNull { ref ->
                    val usable = statuses[ref.alias] as? CollectionStatus.Usable ?: return@mapNotNull null
                    val id = ref.id ?: return@mapNotNull null
                    val resource = usable.pkg.resources[id]
                        ?: return@mapNotNull ReferenceProblem(cue.id, ref, RejectCode.UNRESOLVED_REFERENCE, "${usable.listed.id} has no \"$id\"")
                    if (resource.type !in p.presentAs.types) {
                        ReferenceProblem(cue.id, ref, RejectCode.BAD_CUE, "${p.presentAs.json} can't show ${resource.type.json} \"$id\"")
                    } else {
                        null
                    }
                }
            }
        }
        return CollectionResolution(statuses, problems)
    }

    /**
     * The resource ids each collection's resources are referenced by in [media], by collection id:
     * what an update of the collection must keep while the channel is listed (ADR-002 D-8).
     */
    fun usedResources(media: StationMedia): Map<String, Set<String>> {
        val idOf = media.collections.associate { it.alias to it.id }
        return media.cues.flatMap { c -> c.present.flatMap { it.items.flatten() } }
            .filterIsInstance<Reference.Alias>()
            .mapNotNull { ref -> idOf[ref.alias]?.let { collection -> ref.id?.let { collection to it } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    private fun status(use: CollectionUse, publisherIds: Set<String>, list: ChannelList, pkg: CollectionPackage?, appId: String?): CollectionStatus {
        val listed = list.collections.firstOrNull { it.id == use.id }
            ?: return CollectionStatus.Unusable(RejectCode.COLLECTION_NOT_LISTED, "${use.id} is not on the list")
        if (list.revokedCollections.any { it.id == use.id && it.covers(listed.version) }) {
            return CollectionStatus.Unusable(RejectCode.COLLECTION_NOT_LISTED, "${use.id}@${listed.version} is revoked")
        }
        if (listed.version < use.minVersion) {
            return CollectionStatus.Unusable(RejectCode.COLLECTION_NOT_LISTED, "${use.id}@${listed.version} is older than ${use.minVersion}")
        }
        val granted = listed.grants.publishers == null || publisherIds.any { listed.grants.allows(it) }
        if (!granted) return CollectionStatus.Unusable(RejectCode.NOT_GRANTED, "${use.id} is not granted to this publisher")
        if (appId != null && !listed.grants.allowsApp(appId)) return CollectionStatus.Unusable(RejectCode.NOT_GRANTED, "${use.id} is not granted to $appId")
        pkg ?: return CollectionStatus.NotFetched(listed)
        // The package is the one the list names (its own check), and the list's copy of the grants must agree (API-002 P-15).
        if (pkg.manifest.id != listed.id || pkg.manifest.version != listed.version) {
            return CollectionStatus.Unusable(RejectCode.BAD_MANIFEST, "read ${pkg.manifest.id}@${pkg.manifest.version}, listed ${listed.id}@${listed.version}")
        }
        if (pkg.manifest.grants != listed.grants) return CollectionStatus.Unusable(RejectCode.BAD_MANIFEST, "${use.id}: grants differ from the list")
        return CollectionStatus.Usable(listed, pkg)
    }
}
