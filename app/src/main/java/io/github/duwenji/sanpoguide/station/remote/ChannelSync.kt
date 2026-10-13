package io.github.duwenji.sanpoguide.station.remote

import io.github.duwenji.yorisoi.core.format.AppProfile
import io.github.duwenji.yorisoi.core.format.Compatibility
import io.github.duwenji.yorisoi.core.format.Strictness
import io.github.duwenji.sanpoguide.material.ChannelMaterials
import io.github.duwenji.sanpoguide.material.SelectedCollection
import io.github.duwenji.sanpoguide.material.SelectorPlan
import io.github.duwenji.sanpoguide.material.MaterialPlan
import io.github.duwenji.sanpoguide.material.Prefetch
import io.github.duwenji.yorisoi.core.format.material.CollectionCheck
import io.github.duwenji.yorisoi.core.format.material.CollectionListedAs
import io.github.duwenji.yorisoi.core.format.material.CollectionPackage
import io.github.duwenji.yorisoi.core.format.material.CollectionValidator
import io.github.duwenji.sanpoguide.station.SanpoProfile
import io.github.duwenji.sanpoguide.station.Station
import io.github.duwenji.sanpoguide.station.StationSource
import io.github.duwenji.yorisoi.core.format.ChannelList
import io.github.duwenji.yorisoi.core.format.CollectionReferences
import io.github.duwenji.yorisoi.core.format.CollectionStatus
import io.github.duwenji.yorisoi.core.format.Ed25519Verifier
import io.github.duwenji.yorisoi.core.format.ListedAs
import io.github.duwenji.yorisoi.core.format.ListedChannel
import io.github.duwenji.yorisoi.core.format.ListedCollection
import io.github.duwenji.yorisoi.core.format.PackageArchive
import io.github.duwenji.yorisoi.core.format.ProviderDocuments
import io.github.duwenji.yorisoi.core.format.ProviderException
import io.github.duwenji.yorisoi.core.format.ProviderIds
import io.github.duwenji.yorisoi.core.format.ProviderInfo
import io.github.duwenji.yorisoi.core.format.StationCheck
import io.github.duwenji.yorisoi.core.format.StationFiles
import io.github.duwenji.yorisoi.core.format.StationMedia
import io.github.duwenji.yorisoi.core.format.StationPackage
import io.github.duwenji.yorisoi.core.format.StationValidator
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Fetches a URL, giving up past [maxBytes] (API-002 大きさの上限). Blocking; call it off the main thread. */
fun interface ProviderHttp {
    @Throws(IOException::class)
    fun get(url: String, maxBytes: Int): ByteArray
}

class TooLargeException(url: String) : IOException("$url is too large")

/** Why the user's action can't be done, in their words. */
class ChannelSyncException(message: String) : Exception(message)

/** How a listed channel stands on this device. */
enum class ListedStatus { AVAILABLE, INSTALLED, UPDATE_PENDING, BLOCKED, NEEDS_APP_UPDATE, UNSUPPORTED }

data class ListedView(val channel: ListedChannel, val status: ListedStatus, val installed: InstalledChannel?)

data class ProviderView(
    val record: ProviderRecord,
    /** The name from the discovery document, or the id until one is accepted. */
    val name: String,
    /** The list last accepted, if it is still valid now. */
    val listValid: Boolean,
    val expiresAt: Instant?,
    val channels: List<ListedView>,
    /** Installed channels the list no longer carries. */
    val gone: List<InstalledChannel>,
)

/**
 * Third-party channels (SanpoGuide ADR-001 C-5, API-002 アプリの振る舞い): the providers the user
 * added, their lists, and the packages the user chose. Plain JVM so it is tested without a device;
 * [ThirdPartyChannels] runs it on Android. Not thread-safe: the caller serializes calls.
 *
 * @param appVersion the app's versionCode, against `minAppVersion`.
 * @param allowLocalHttp debug builds may use `http://localhost` and `http://10.0.2.2` (the emulator's host).
 * @param mediaStage the material this app shows (API-003 M-1: 1 text and images, 2 audio, 3 video).
 * @param region the device's region and the eight around it (API-004 P-6), for the collections
 *   selectors draw from; null while the location is unknown.
 * @param profile what this app is, for choosing the channels it can use (API-003 2.0 G-4) and the
 *   version it is against `minAppVersions` (API-002 1.4 P-20).
 */
class ChannelSync(
    private val store: ChannelStore,
    private val http: ProviderHttp,
    private val verifier: Ed25519Verifier,
    private val appVersion: Int,
    private val now: () -> Instant,
    private val allowLocalHttp: Boolean = false,
    private val mediaStage: Int = MEDIA_STAGE,
    private val region: () -> Set<String>? = { null },
    private val profile: AppProfile = SanpoProfile.of(appVersion),
) {
    companion object {
        /** The app shows text and images (ADR-002 T-8). */
        const val MEDIA_STAGE = 1
        val REFRESH_EVERY: Duration = Duration.ofHours(24)
        const val DISCOVERY_PATH = "/.well-known/sanpo-channels"
        private const val MAX_DISCOVERY_BYTES = 64 * 1024
        private const val MAX_NOTICES = 20
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    }

    private val docs = ProviderDocuments(verifier)
    var state: ChannelState = store.load()
        private set

    // ---- providers ----

    /**
     * Adds a provider from its URL and the id the user got by another route (API-002: never taken
     * from the server), after its discovery document checks out against that id.
     */
    fun addProvider(url: String, providerId: String) {
        val base = normalizeBase(url)
        val id = providerId.trim()
        if (!ProviderIds.isWellFormed(id)) throw ChannelSyncException("リストIDの形が正しくありません（sc1 で始まる 35 文字）")
        if (state.providers.any { it.id == id }) throw ChannelSyncException("このリストはもう登録されています")
        val info = try {
            docs.discovery(String(http.get(base + DISCOVERY_PATH, MAX_DISCOVERY_BYTES), Charsets.UTF_8), id)
        } catch (e: ProviderException) {
            throw ChannelSyncException("リストの情報を確かめられませんでした（${e.message}）")
        } catch (e: IOException) {
            throw ChannelSyncException("リストに接続できませんでした（${e.message}）")
        }
        update { it.copy(providers = it.providers + ProviderRecord(id = id, url = base, name = info.name)) }
        refresh(id, unmetered = false)
    }

    fun removeProvider(providerId: String) {
        state.installed.filter { it.provider == providerId }.forEach { store.deletePackage(it.sha256) }
        state.trials.filter { it.provider == providerId }.forEach { store.deletePackage(it.sha256) }
        update { s ->
            s.copy(
                providers = s.providers.filter { it.id != providerId },
                installed = s.installed.filter { it.provider != providerId },
                trials = s.trials.filter { it.provider != providerId },
            )
        }
        collectGarbage()
    }

    fun setEnabled(providerId: String, enabled: Boolean) = updateProvider(providerId) { it.copy(enabled = enabled) }

    /** Refreshes the enabled providers not tried for [REFRESH_EVERY] (at start-up and when a walk starts). */
    fun refreshDue(unmetered: Boolean) {
        for (p in state.providers) {
            if (p.enabled && (p.triedAt == null || Duration.between(p.triedAt, now()) >= REFRESH_EVERY)) refresh(p.id, unmetered)
        }
        if (unmetered) fetchNearby()
        noticeExpiredLists()
        dropExpiredTrials()
    }

    fun refreshAll(unmetered: Boolean) {
        state.providers.filter { it.enabled }.forEach { refresh(it.id, unmetered) }
        noticeExpiredLists()
    }

    /**
     * Fetches the provider's discovery document and list. A list that fails a check is dropped and
     * the last one kept (until it expires); the reason shows in the settings (API-002 確認に失敗したとき).
     */
    fun refresh(providerId: String, unmetered: Boolean) {
        val record = state.providers.firstOrNull { it.id == providerId } ?: return
        val at = now()
        try {
            val discoveryJson = String(http.get(record.url + DISCOVERY_PATH, MAX_DISCOVERY_BYTES), Charsets.UTF_8)
            val info = docs.discovery(discoveryJson, record.id, minKeysetSeq = record.keysetSeq)
            val listJson = String(http.get(resolve(record.url, info.list), ProviderDocuments.MAX_LIST_BYTES), Charsets.UTF_8)
            val list = try {
                docs.channelList(listJson, info.keyset, at, record.listSeq, record.listSha256)
            } catch (e: ProviderException) {
                if (e.error.code == "expired" && docs.issuedInFuture(listJson, at)) {
                    throw ChannelSyncException("端末の時計がずれているようです。時刻を確かめてください")
                }
                throw e
            }
            updateProvider(record.id) {
                it.copy(
                    name = info.name, discovery = discoveryJson, list = listJson, keysetSeq = info.keyset.seq, listSeq = list.seq,
                    listSha256 = list.sha256, fetchedAt = at, triedAt = at, error = null, expiryNoticed = false,
                )
            }
            apply(record.id, info, list, unmetered)
        } catch (e: ProviderException) {
            updateProvider(record.id) { it.copy(triedAt = at, error = e.message) }
        } catch (e: ChannelSyncException) {
            updateProvider(record.id) { it.copy(triedAt = at, error = e.message) }
        } catch (e: IOException) {
            updateProvider(record.id) { it.copy(triedAt = at, error = "fetch_failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** What a new list means for the channels on this device: withdrawn ones go, newer versions come. */
    private fun apply(providerId: String, info: ProviderInfo, list: ChannelList, unmetered: Boolean) {
        for (c in state.installed.filter { it.provider == providerId }) {
            val revoked = list.revoked.firstOrNull { it.id == c.id && it.covers(c.version) }
            val listed = list.channels.firstOrNull { it.id == c.id }
            when {
                revoked != null -> {
                    store.deletePackage(c.sha256)
                    update { s -> s.copy(installed = s.installed - c) }
                    collectGarbage()
                    notice("「${listed?.name ?: c.name.ifEmpty { c.id }}」はリスト（${info.name}）から取り下げられました: ${revoked.reason}")
                }
                listed != null && listed.version > c.version && unmetered -> runCatching { download(providerId, listed, c, list, unmetered) }
            }
        }
        // New versions of collections, and material a metered network left behind, come on Wi-Fi (ADR-002 T-8a).
        if (unmetered) {
            for (c in state.installed.filter { it.provider == providerId }) {
                open(c.sha256, ListedAs(c.id, c.version, c.publisher))?.let { fetchMaterials(providerId, list, it.pkg, c.publisher, unmetered) }
            }
            for (t in state.trials.filter { it.provider == providerId }) open(t.sha256, null)?.let { fetchMaterials(providerId, list, it.pkg, t.publisher, unmetered) }
            collectGarbage()
        }
    }

    // ---- channels ----

    /** Takes a listed channel onto the device: the package is fetched and checked when the user chooses it. */
    fun install(providerId: String, channelId: String, unmetered: Boolean = false) {
        val (_, list) = validList(providerId) ?: throw ChannelSyncException("リストが有効ではありません。取得し直してください")
        val entry = list.channels.firstOrNull { it.id == channelId } ?: throw ChannelSyncException("リストにないチャンネルです")
        if (entry.pkg.format !in StationValidator.FORMATS) throw ChannelSyncException("このアプリでは使えない形式のチャンネルです")
        if (Compatibility.reasons(entry, profile).isNotEmpty()) throw ChannelSyncException("このアプリでは使えないチャンネルです")
        if (needsUpdate(entry)) throw ChannelSyncException("このチャンネルを使うには、アプリの更新が必要です")
        val installed = state.installed.firstOrNull { it.provider == providerId && it.id == channelId }
        download(providerId, entry, installed, list, unmetered)
    }

    fun uninstall(providerId: String, channelId: String) {
        val c = state.installed.firstOrNull { it.provider == providerId && it.id == channelId } ?: return
        store.deletePackage(c.sha256)
        update { s -> s.copy(installed = s.installed - c) }
        collectGarbage()
    }

    /**
     * Fetches, checks and keeps a package; replaces [previous] only once the new one checks out.
     * A new publisher is accepted only with the list's `publisherChange` from the one on the device (P-9).
     * The material its cues may show is fetched with it (API-003 F-10).
     */
    private fun download(providerId: String, entry: ListedChannel, previous: InstalledChannel?, list: ChannelList, unmetered: Boolean) {
        if (previous != null && entry.publisher != previous.publisher) {
            val change = entry.publisherChange
            if (change == null || change.from != previous.publisher) {
                update { s -> s.copy(installed = s.installed.map { if (it == previous) it.copy(blocked = "publisher_changed") else it }) }
                throw ChannelSyncException("チャンネル提供者が裏付けなく変わったため、新しい版は使いません（publisher_changed）")
            }
        }
        val url = checkedUrl(entry.pkg.url)
        val bytes = try {
            http.get(url, minOf(entry.pkg.size, ProviderDocuments.MAX_PACKAGE_BYTES))
        } catch (e: IOException) {
            throw ChannelSyncException("パッケージを取得できませんでした（${e.message}）")
        }
        if (bytes.size != entry.pkg.size || sha256(bytes) != entry.pkg.sha256) throw ChannelSyncException("パッケージがリストと一致しません（hash_mismatch）")
        val listedAs = ListedAs(entry.id, entry.version, entry.publisher, entry.activities, entry.genres, entry.requires)
        val check = StationValidator.checkArchive(bytes, listedAs, verifier, mediaStage, Strictness.APP)
        if (check is StationCheck.Rejected) throw ChannelSyncException("パッケージの確認に失敗しました（package_rejected: ${check.code.json}）")
        check as StationCheck.Ok
        // What the package holds may rule out this app where the list's copy couldn't (an unknown restriction).
        if (Compatibility.reasons(check.station.manifest, profile).isNotEmpty()) throw ChannelSyncException("このアプリでは使えないチャンネルです")

        store.writePackage(entry.pkg.sha256, bytes)
        val next = InstalledChannel(providerId, entry.id, entry.version, entry.publisher, entry.pkg.sha256, name = entry.name)
        update { s -> s.copy(installed = s.installed.filter { !(it.provider == providerId && it.id == entry.id) } + next) }
        if (previous != null && previous.sha256 != next.sha256) store.deletePackage(previous.sha256)
        fetchMaterials(providerId, list, check.station, entry.publisher, unmetered)
        collectGarbage()
        if (previous != null && entry.publisher != previous.publisher) {
            notice("「${entry.name}」のチャンネル提供者の鍵が移し替えられました: ${entry.publisherChange!!.reason}")
        }
    }

    /**
     * The third-party channels usable now: installed, from an enabled provider whose last list is
     * still valid, still listed and not withdrawn, and whose package still checks out.
     */
    fun stations(standard: StationPackage, developerMode: Boolean = false): List<Station> = installedStations(standard) +
        if (developerMode) trialStations(standard) else emptyList()

    private fun installedStations(standard: StationPackage): List<Station> = state.installed.mapNotNull { c ->
        val provider = state.providers.firstOrNull { it.id == c.provider && it.enabled } ?: return@mapNotNull null
        val (info, list) = validList(c.provider) ?: return@mapNotNull null
        val entry = list.channels.firstOrNull { it.id == c.id } ?: return@mapNotNull null
        if (list.revoked.any { it.id == c.id && it.covers(c.version) }) return@mapNotNull null
        if (Compatibility.reasons(entry, profile).isNotEmpty()) return@mapNotNull null
        val opened = open(c.sha256, ListedAs(c.id, c.version, c.publisher)) ?: return@mapNotNull null
        Station(
            opened.pkg, standard, key = "${c.provider}/${c.id}@${c.version}", id = "${c.provider}/${c.id}",
            source = StationSource(provider.id, info.name, entry.publisherName, promotional = entry.promotional),
            materials = materials(c.provider, list, opened, c.publisher),
        )
    }.sortedBy { it.name }

    /** Each provider with its channels and how they stand, for the settings. */
    fun providers(): List<ProviderView> = state.providers.map { p ->
        val valid = validList(p.id)
        val list = valid?.second
        val mine = state.installed.filter { it.provider == p.id }
        ProviderView(
            record = p,
            name = valid?.first?.name ?: p.name ?: p.id,
            listValid = valid != null,
            expiresAt = list?.expiresAt,
            // Channels for another activity, or needing what this app lacks, aren't offered (API-003 G-4).
            channels = list?.channels.orEmpty().filter { Compatibility.reasons(it, profile).isEmpty() }.map { entry ->
                val installed = mine.firstOrNull { it.id == entry.id }
                val status = when {
                    entry.pkg.format !in StationValidator.FORMATS -> ListedStatus.UNSUPPORTED
                    needsUpdate(entry) -> ListedStatus.NEEDS_APP_UPDATE
                    installed == null -> ListedStatus.AVAILABLE
                    installed.blocked != null -> ListedStatus.BLOCKED
                    installed.version < entry.version -> ListedStatus.UPDATE_PENDING
                    else -> ListedStatus.INSTALLED
                }
                ListedView(entry, status, installed)
            },
            gone = mine.filter { c -> list?.channels?.none { it.id == c.id } ?: false },
        )
    }

    fun dismissNotices() = update { it.copy(notices = emptyList()) }

    // ---- test tickets (API-002 試用チケット) ----

    /**
     * Reads a test ticket from a QR code: signed by a provider the user added, valid now (7 days at
     * most), and only in developer mode. The package is fetched and checked like a listed one, and
     * must be the ticket's channel by the ticket's publisher. Returns the trial's station id.
     */
    fun loadTicket(json: String, developerMode: Boolean, unmetered: Boolean = false): String {
        if (!developerMode) throw ChannelSyncException("試用チケットは開発者モードのときだけ読み込めます（ticket_invalid）")
        val providerId = runCatching { ticketProvider(json) }.getOrNull() ?: throw ChannelSyncException("試用チケットとして読めません（ticket_invalid）")
        val record = state.providers.firstOrNull { it.id == providerId } ?: throw ChannelSyncException("この試用チケットのリスト（$providerId）を先に足してください")
        val info = runCatching { docs.discovery(record.discovery ?: "", record.id) }.getOrNull()
            ?: throw ChannelSyncException("リストの情報がまだありません。「今すぐ確かめる」を押してから読み込んでください")
        val ticket = try {
            docs.testTicket(json, info.keyset, now())
        } catch (e: ProviderException) {
            throw ChannelSyncException("試用チケットを確かめられませんでした（${e.message}）")
        }
        if (ticket.pkg.format != 1) throw ChannelSyncException("このアプリでは使えない形式のチャンネルです")
        val bytes = try {
            http.get(checkedUrl(ticket.pkg.url), minOf(ticket.pkg.size, ProviderDocuments.MAX_PACKAGE_BYTES))
        } catch (e: IOException) {
            throw ChannelSyncException("パッケージを取得できませんでした（${e.message}）")
        }
        if (bytes.size != ticket.pkg.size || sha256(bytes) != ticket.pkg.sha256) throw ChannelSyncException("パッケージがチケットと一致しません（hash_mismatch）")
        val pkg = when (val check = StationValidator.checkArchive(bytes, null, verifier, mediaStage, Strictness.APP)) {
            is StationCheck.Rejected -> throw ChannelSyncException("パッケージの確認に失敗しました（package_rejected: ${check.code.json}）")
            is StationCheck.Ok -> check.station
        }
        if (Compatibility.reasons(pkg.manifest, profile).isNotEmpty()) throw ChannelSyncException("このアプリでは使えないチャンネルです")
        if (pkg.manifest.id != ticket.channel || pkg.manifest.publisher != ticket.publisher) {
            throw ChannelSyncException("パッケージがチケットのチャンネル・チャンネル提供者と違います（ticket_invalid）")
        }
        store.writePackage(ticket.pkg.sha256, bytes)
        val trial = TrialChannel(providerId, ticket.channel, pkg.manifest.version, ticket.publisher, ticket.pkg.sha256, pkg.manifest.name, ticket.expiresAt)
        val replaced = state.trials.filter { it.provider == providerId && it.id == ticket.channel }
        update { s -> s.copy(trials = s.trials - replaced.toSet() + trial) }
        replaced.filter { it.sha256 != trial.sha256 }.forEach { store.deletePackage(it.sha256) }
        validList(providerId)?.let { (_, list) -> fetchMaterials(providerId, list, pkg, ticket.publisher, unmetered) }
        collectGarbage()
        return trialId(trial)
    }

    fun removeTrial(providerId: String, channelId: String) {
        val gone = state.trials.filter { it.provider == providerId && it.id == channelId }
        gone.forEach { store.deletePackage(it.sha256) }
        update { s -> s.copy(trials = s.trials - gone.toSet()) }
        collectGarbage()
    }

    private fun ticketProvider(json: String): String {
        val payload = Base64.getUrlDecoder().decode(JSONObject(json).getString("payload"))
        return JSONObject(String(payload, Charsets.UTF_8)).getString("provider")
    }

    private fun trialId(t: TrialChannel) = "trial:${t.provider}/${t.id}"

    private fun trialStations(standard: StationPackage): List<Station> = state.trials.mapNotNull { t ->
        if (!now().isBefore(t.expiresAt)) return@mapNotNull null
        val provider = state.providers.firstOrNull { it.id == t.provider } ?: return@mapNotNull null
        val opened = open(t.sha256, null) ?: return@mapNotNull null
        Station(
            opened.pkg, standard, key = "${trialId(t)}@${t.version}#${t.sha256.take(8)}", id = trialId(t),
            source = StationSource(provider.id, provider.name ?: provider.id, t.publisher, trial = true),
            materials = materials(t.provider, validList(t.provider)?.second, opened, t.publisher),
        )
    }

    /** Trials whose ticket expired go, package and all. */
    private fun dropExpiredTrials() {
        val expired = state.trials.filter { !now().isBefore(it.expiresAt) }
        if (expired.isEmpty()) return
        expired.forEach { store.deletePackage(it.sha256) }
        update { s -> s.copy(trials = s.trials - expired.toSet()) }
        collectGarbage()
    }

    // ---- material (ADR-002 T-8a) ----

    private class Opened(val pkg: StationPackage, val files: StationFiles)

    /** A package on the device, checked again as it is used. */
    private fun open(sha256: String, listedAs: ListedAs?): Opened? {
        val zip = store.readPackage(sha256) ?: return null
        val pkg = (StationValidator.checkArchive(zip, listedAs, verifier, mediaStage, Strictness.APP) as? StationCheck.Ok)?.station ?: return null
        if (Compatibility.reasons(pkg.manifest, profile).isNotEmpty()) return null
        return Opened(pkg, PackageArchive.open(zip))
    }

    /** API-002 P-20: this app's own minimum, if the list has one. */
    private fun needsUpdate(entry: ListedChannel): Boolean = (entry.minAppVersionFor(profile.id) ?: 0) > appVersion

    private val collectionCache = HashMap<String, Pair<CollectionPackage, StationFiles>>()

    /** A collection on the device, with its own checks (API-004 手順 1・2); the list's are the caller's. */
    private fun openCollection(sha256: String): Pair<CollectionPackage, StationFiles>? = collectionCache.getOrPut(sha256) {
        val zip = store.readCollection(sha256) ?: return null
        val files = runCatching { PackageArchive.open(zip) }.getOrNull() ?: return null
        val pkg = (CollectionValidator.check(files, null, verifier, Strictness.APP) as? CollectionCheck.Ok)?.collection ?: return null
        pkg to files
    }

    /** The collections kept for [providerId] at the version [list] names, signed by the producer it names, by id. */
    private fun keptCollections(providerId: String, list: ChannelList): Map<String, Pair<CollectionPackage, StationFiles>> =
        state.collections.filter { it.provider == providerId }.mapNotNull { c ->
            val listed = list.collections.firstOrNull { it.id == c.id && it.version == c.version } ?: return@mapNotNull null
            val opened = openCollection(c.sha256) ?: return@mapNotNull null
            val producer = opened.first.manifest.producer
            if (producer != listed.producer && producer !in list.formerProducerIds(listed.producer)) return@mapNotNull null
            c.id to opened
        }.toMap()

    /** What a channel can show now; collections are resolved against [list], and none are usable without one. */
    private fun materials(providerId: String, list: ChannelList?, opened: Opened, publisher: String): ChannelMaterials? {
        val media = opened.pkg.media
        if (media.isEmpty) return null
        val kept = list?.let { keptCollections(providerId, it) }.orEmpty()
        val statuses = list?.let { CollectionReferences.resolve(media, publisher, it, kept.mapValues { e -> e.value.first }, profile.id).statuses }.orEmpty()
        val files = media.collections.mapNotNull { use ->
            kept[use.id]?.takeIf { statuses[use.alias] is CollectionStatus.Usable }?.let { use.alias to it.second }
        }.toMap()
        val selected = list?.let { selected(media, it, publisher, kept) }.orEmpty()
        return ChannelMaterials(media, statuses, opened.files, files, store::readResource, selected, opened.pkg.manifest.lang)
    }

    /**
     * The collections on the device each of [media]'s selectors may draw from now, best first, by
     * alias: still candidates on [list], at its version, and agreeing with its copy of the grants.
     */
    private fun selected(
        media: StationMedia, list: ChannelList, publisher: String, kept: Map<String, Pair<CollectionPackage, StationFiles>>,
    ): Map<String, List<SelectedCollection>> = media.selectors.associate { selector ->
        selector.alias to SelectorPlan.candidates(selector, list, publisher, appVersion, profile.id).mapNotNull { listed ->
            val (pkg, files) = kept[listed.id] ?: return@mapNotNull null
            SelectedCollection(listed, pkg, files).takeIf { pkg.manifest.grants == listed.grants }
        }
    }

    /**
     * Fetches what [pkg] may show and the device doesn't have: the collections it declares, at
     * the version [list] names; on an [unmetered] network, the collections its selectors draw
     * from around here (API-004 P-6); then the outside resources of [MaterialPlan]. Each failure
     * leaves just that material out (its cue moves on to the next candidate) until the next try.
     */
    private fun fetchMaterials(providerId: String, list: ChannelList, pkg: StationPackage, publisher: String, unmetered: Boolean) {
        val media = pkg.media
        if (media.isEmpty) return
        fun resolved() = CollectionReferences.resolve(media, publisher, list, keptCollections(providerId, list).mapValues { it.value.first }, profile.id)
        for (status in resolved().statuses.values) {
            if (status is CollectionStatus.NotFetched) runCatching { fetchCollection(providerId, status.listed, list) }
        }
        if (unmetered && media.selectors.isNotEmpty()) {
            val kept = keptCollections(providerId, list)
            for (listed in SelectorPlan.toFetch(media, list, publisher, appVersion, region(), profile.id)) {
                if (listed.id !in kept) runCatching { fetchCollection(providerId, listed, list) }
            }
        }
        val usable = resolved().statuses.mapNotNull { (alias, s) -> (s as? CollectionStatus.Usable)?.let { alias to it.pkg } }.toMap()
        val selected = selected(media, list, publisher, keptCollections(providerId, list)).mapValues { e -> e.value.map { it.pkg } }
        for (item in MaterialPlan.prefetch(media, usable, mediaStage, selected, pkg.manifest.lang)) {
            if (!store.hasResource(item.sha256)) runCatching { fetchResource(item) }
        }
    }

    /**
     * On an unmetered network at start-up and when a walk starts: the collections the channels'
     * selectors draw from where the device is now (API-004 P-6). Only the region is used, and
     * nothing is fetched on coming near a spot (API-003 F-10).
     */
    private fun fetchNearby() {
        for (c in state.installed) {
            val (_, list) = validList(c.provider) ?: continue
            val opened = open(c.sha256, ListedAs(c.id, c.version, c.publisher)) ?: continue
            if (opened.pkg.media.selectors.isNotEmpty()) fetchMaterials(c.provider, list, opened.pkg, c.publisher, unmetered = true)
        }
        for (t in state.trials) {
            val (_, list) = validList(t.provider) ?: continue
            val opened = open(t.sha256, null) ?: continue
            if (opened.pkg.media.selectors.isNotEmpty()) fetchMaterials(t.provider, list, opened.pkg, t.publisher, unmetered = true)
        }
        collectGarbage()
    }

    private fun fetchCollection(providerId: String, listed: ListedCollection, list: ChannelList) {
        val bytes = http.get(checkedUrl(listed.pkg.url), minOf(listed.pkg.size, ProviderDocuments.MAX_PACKAGE_BYTES))
        if (bytes.size != listed.pkg.size || sha256(bytes) != listed.pkg.sha256) throw IOException("hash_mismatch")
        val listedAs = CollectionListedAs(listed.id, listed.version, listed.producer, list.formerProducerIds(listed.producer))
        if (CollectionValidator.checkArchive(bytes, listedAs, verifier, Strictness.APP) !is CollectionCheck.Ok) throw IOException("collection_invalid")
        store.writeCollection(listed.pkg.sha256, bytes)
        val next = FetchedCollection(providerId, listed.id, listed.version, listed.pkg.sha256)
        val previous = state.collections.filter { it.provider == providerId && it.id == listed.id }
        update { s -> s.copy(collections = s.collections - previous.toSet() + next) }
        previous.filter { it.sha256 != next.sha256 }.forEach {
            store.deleteCollection(it.sha256)
            collectionCache.remove(it.sha256)
        }
    }

    private fun fetchResource(item: Prefetch) {
        val bytes = http.get(checkedUrl(item.url), item.size.toInt())
        if (bytes.size.toLong() != item.size || sha256(bytes) != item.sha256) throw IOException("hash_mismatch")
        store.writeResource(item.sha256, bytes)
    }

    /**
     * Drops the collections no channel on the device declares, and the resources none can show.
     * An expired list doesn't count against them: the channel comes back with the next list.
     */
    private fun collectGarbage() {
        val collections = HashSet<Pair<String, String>>()
        val resources = HashSet<String>()
        val channels = state.installed.map { Triple(it.provider, it.publisher, open(it.sha256, ListedAs(it.id, it.version, it.publisher))) } +
            state.trials.map { Triple(it.provider, it.publisher, open(it.sha256, null)) }
        val here = region()
        for ((provider, publisher, opened) in channels) {
            val pkg = opened?.pkg ?: continue
            val media = pkg.media
            media.collections.forEach { collections += provider to it.id }
            val byAlias = media.collections.mapNotNull { use ->
                state.collections.firstOrNull { it.provider == provider && it.id == use.id }
                    ?.let { c -> openCollection(c.sha256)?.let { use.alias to it.first } }
            }.toMap()
            // A selector's collections stay while they are candidates around here; with no valid
            // list, or no location, nothing can be told and they all stay.
            var selected = emptyMap<String, List<CollectionPackage>>()
            if (media.selectors.isNotEmpty()) {
                val list = validList(provider)?.second
                if (list == null) {
                    state.collections.filter { it.provider == provider }.forEach { collections += provider to it.id }
                } else {
                    val keep = if (here == null) {
                        media.selectors.flatMap { SelectorPlan.candidates(it, list, publisher, appVersion, profile.id) }.map { it.id }.toSet()
                    } else {
                        SelectorPlan.toFetch(media, list, publisher, appVersion, here, profile.id).map { it.id }.toSet()
                    }
                    keep.forEach { collections += provider to it }
                    selected = selected(media, list, publisher, keptCollections(provider, list).filterKeys { it in keep })
                        .mapValues { e -> e.value.map { it.pkg } }
                }
            }
            MaterialPlan.prefetch(media, byAlias, mediaStage, selected, pkg.manifest.lang).forEach { resources += it.sha256 }
        }
        val dropped = state.collections.filter { (it.provider to it.id) !in collections }
        if (dropped.isNotEmpty()) {
            dropped.forEach {
                store.deleteCollection(it.sha256)
                collectionCache.remove(it.sha256)
            }
            update { s -> s.copy(collections = s.collections - dropped.toSet()) }
        }
        (store.resourceNames() - resources).forEach(store::deleteResource)
    }

    // ---- helpers ----

    private val verified = HashMap<String, Pair<ProviderInfo, ChannelList>>()

    /** The provider's last accepted documents, checked again (now: a list that expired since is not valid). */
    private fun validList(providerId: String): Pair<ProviderInfo, ChannelList>? {
        val p = state.providers.firstOrNull { it.id == providerId } ?: return null
        val discovery = p.discovery ?: return null
        val listJson = p.list ?: return null
        val pair = verified.getOrPut(p.id + "\n" + p.listSha256) {
            runCatching {
                val info = docs.discovery(discovery, p.id)
                info to docs.channelList(listJson, info.keyset, now())
            }.getOrNull() ?: return null
        }
        return pair.takeIf { now().isBefore(it.second.expiresAt) }
    }

    /** API-002: an expired list's channels stop from the next walk; say so once, if one was in use. */
    private fun noticeExpiredLists() {
        for (p in state.providers) {
            if (p.list == null || p.expiryNoticed || validList(p.id) != null) continue
            if (state.installed.any { it.provider == p.id }) {
                notice("リスト（${p.name ?: p.id}）の期限が切れたため、そのチャンネルは使えません。取得できるようになると戻ります")
            }
            updateProvider(p.id) { it.copy(expiryNoticed = true) }
        }
    }

    private fun notice(text: String) = update { it.copy(notices = (it.notices + ChannelNotice(now(), text)).takeLast(MAX_NOTICES)) }

    private fun updateProvider(id: String, change: (ProviderRecord) -> ProviderRecord) =
        update { s -> s.copy(providers = s.providers.map { if (it.id == id) change(it) else it }) }

    private fun update(change: (ChannelState) -> ChannelState) {
        state = change(state)
        store.save(state)
    }

    /** `https://…` without a trailing slash; debug builds may also use local `http://`. */
    fun normalizeBase(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        checkedUrl(trimmed)
        return trimmed
    }

    private fun checkedUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val ok = uri != null && uri.host != null && (uri.scheme == "https" || (allowLocalHttp && uri.scheme == "http" && uri.host in LOCAL_HOSTS))
        if (!ok) throw ChannelSyncException("URL は https:// で始まる必要があります")
        return url
    }

    private fun resolve(base: String, path: String): String = checkedUrl(URI("$base/").resolve(path).toString())

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
