package io.github.duwenji.yorisoi.sanpoguide.station.remote

import io.github.duwenji.yorisoi.sanpoguide.station.BuiltInStations
import io.github.duwenji.yorisoi.core.format.CollectionStatus
import io.github.duwenji.yorisoi.core.format.RejectCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Material fetched ahead with a third party's channel (ADR-002 T-8a, API-003 F-10): its own
 * resources, the collections it declares at the listed version, and their outside resources.
 */
class ChannelMaterialsSyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val base = "https://channels.example"
    private var clock = Instant.parse("2026-10-11T00:00:00Z")
    private val files = HashMap<String, ByteArray>()
    private val fetched = ArrayList<String>()
    private val provider = TestProvider()
    private val alice = TestPublisher()
    private val producer = TestPublisher()
    private val standard = BuiltInStations.standardPackage()
    private lateinit var sync: ChannelSync

    private val http = ProviderHttp { url, max ->
        fetched += url
        val bytes = files[url] ?: throw IOException("HTTP 404")
        if (bytes.size > max) throw TooLargeException(url)
        bytes
    }

    private val ownImage = png(640, 480)
    private val gate = png(1200, 800, seed = 1)
    private val photo = png(1600, 1200, seed = 2)
    private val voice = ByteArray(4096) { 7 }

    @Before
    fun setUp() {
        files["$base/.well-known/sanpo-channels"] = provider.discovery().toByteArray()
        files["$base/res/gate.png"] = gate
        files["$base/col/photo.png"] = photo
        files["$base/col/voice.ogg"] = voice
        sync = ChannelSync(ChannelStore(folder.root), http, TinkEd25519, appVersion = 1, now = { clock })
    }

    private fun external(id: String, type: String, url: String, bytes: ByteArray, mime: String) = JSONObject()
        .put("id", id).put("type", type).put("url", url).put("sha256", sha256(bytes)).put("size", bytes.size).put("mime", mime)
        .put("credit", JSONObject().put("author", "鎌倉市観光協会").put("license", "permission"))

    /** The channel: its own text and two images, and a collection used by id and by subject. */
    private fun channelZip(version: Int = 1, extra: JSONObject.() -> Unit = {}): ByteArray {
        val manifest = JSONObject()
            .put("resources", JSONArray()
                .put(JSONObject().put("id", "intro").put("type", "text").put("file", "resources/text/intro.md"))
                .put(JSONObject().put("id", "old-photo").put("type", "image").put("file", "resources/img/old.png").put("alt", "明治時代の本宮")
                    .put("credit", JSONObject().put("author", "不明").put("license", "PD")))
                .put(external("gate", "image", "$base/res/gate.png", gate, "image/png").put("alt", "三の鳥居")))
            .put("collections", JSONArray().put(JSONObject().put("as", "kamakura").put("id", "kamakura-guide").put("minVersion", 1)))
            .put("cues", JSONArray().put(JSONObject().put("id", "hachimangu")
                .put("trigger", JSONObject().put("type", "spot").put("osm", "way/123456").put("name", "鶴岡八幡宮"))
                .put("present", JSONArray()
                    .put(JSONObject().put("as", "speak").put("resource", "intro"))
                    .put(JSONObject().put("as", "card").put("source", JSONArray(listOf("kamakura#photo", "self#old-photo"))))
                    .put(JSONObject().put("as", "sheet").put("sources", JSONArray().put("self#gate").put("kamakura"))))))
            .apply(extra)
        return alice.packageZip(
            "kamakura-history", version, manifest,
            mapOf("resources/text/intro.md" to "鶴岡八幡宮は、源頼朝が今の場所に移しました。\n".toByteArray(), "resources/img/old.png" to ownImage),
        )
    }

    /** The collection: a text about the spot, a photo and a recording outside the package. */
    private fun collectionZip(version: Int, grants: Any = "*"): ByteArray {
        val about = JSONObject().put("type", "spot").put("osm", "way/123456").put("name", "鶴岡八幡宮").put("lat", 35.3260).put("lon", 139.5565)
        val resources = JSONArray()
            .put(JSONObject().put("id", "intro").put("type", "text").put("file", "resources/text/intro.md").put("about", about).put("tags", JSONArray(listOf("history"))))
            .put(external("photo", "image", "$base/col/photo.png", photo, "image/png").put("alt", "本宮の正面").put("about", about))
            .put(external("voice", "audio", "$base/col/voice.ogg", voice, "audio/ogg").put("durationSec", 60).put("transcript", "intro").put("about", about))
        return producer.collectionZip("kamakura-guide", version, resources, mapOf("resources/text/intro.md" to "本宮は石段の上にあります。\n".toByteArray()), grants)
    }

    private fun publish(seq: Int, channel: ByteArray, collection: ByteArray?, collectionVersion: Int = 1, revokedCollections: JSONArray? = null) {
        val sha = sha256(channel)
        files["$base/pkg/$sha.zip"] = channel
        val entry = JSONObject()
            .put("id", "kamakura-history").put("version", 1).put("publisher", alice.accountId).put("publisherName", "鎌倉歴史散歩の会")
            .put("name", "鎌倉歴史散歩").put("summary", "寺社と武士の歴史").put("lang", JSONArray(listOf("ja")))
            .put("icon", JSONObject().put("url", "$base/icons/x.png").put("sha256", "0".repeat(64)))
            .put("package", JSONObject().put("url", "$base/pkg/$sha.zip").put("sha256", sha).put("size", channel.size).put("format", 1))
            .put("minAppVersion", 1).put("approvedAt", clock.toString())
        val collections = collection?.let {
            val colSha = sha256(it)
            files["$base/col/$colSha.zip"] = it
            JSONArray().put(JSONObject()
                .put("id", "kamakura-guide").put("version", collectionVersion).put("producer", producer.accountId).put("producerName", "鎌倉市観光協会")
                .put("name", "鎌倉音声ガイド").put("summary", "寺社の解説と写真").put("media", JSONArray(listOf("text", "image", "audio")))
                .put("resourcesSize", photo.size + voice.size).put("tags", JSONArray(listOf("history"))).put("regions", JSONArray(listOf("xn7")))
                .put("grants", JSONObject().put("publishers", "*"))
                .put("package", JSONObject().put("url", "$base/col/$colSha.zip").put("sha256", colSha).put("size", it.size).put("format", 1))
                .put("minAppVersion", 1).put("approvedAt", clock.toString()))
        }
        files["$base/v1/channels.json"] = provider.list(seq, JSONArray().put(entry), JSONArray(), clock, clock.plus(Duration.ofDays(14)), collections, revokedCollections).toByteArray()
    }

    private fun install() {
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history")
    }

    private fun materials() = sync.stations(standard).single().materials!!

    private fun stored(bytes: ByteArray) = ChannelStore(folder.root).hasResource(sha256(bytes))

    @Test
    fun `choosing a channel fetches its collections and the outside images its cues may show`() {
        publish(1, channelZip(), collectionZip(1))
        install()

        val m = materials()
        val kamakura = m.collection("kamakura")!!
        assertEquals(1, kamakura.pkg.manifest.version)
        assertEquals("鶴岡八幡宮は、源頼朝が今の場所に移しました。", m.text(m.media.resources["intro"]!!, null))
        assertEquals("本宮は石段の上にあります。", m.text(kamakura.pkg.resources["intro"]!!, "kamakura"))
        assertArrayEquals(ownImage, m.bytes(m.media.resources["old-photo"]!!, null))
        assertArrayEquals(gate, m.bytes(m.media.resources["gate"]!!, null))
        assertArrayEquals(photo, m.bytes(kamakura.pkg.resources["photo"]!!, "kamakura"))
        // Audio is for a later app stage: not fetched, and not there.
        assertNull(m.bytes(kamakura.pkg.resources["voice"]!!, "kamakura"))
        assertFalse("$base/col/voice.ogg" in fetched)
        assertEquals(1, sync.state.collections.size)

        // Kept across restarts, without fetching again.
        fetched.clear()
        val again = ChannelSync(ChannelStore(folder.root), http, TinkEd25519, appVersion = 1, now = { clock })
        assertArrayEquals(photo, again.stations(standard).single().materials!!.let { it.bytes(it.collection("kamakura")!!.pkg.resources["photo"]!!, "kamakura") })
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `material that can't be fetched is left out, and comes on the next unmetered refresh`() {
        files.remove("$base/col/photo.png")
        publish(1, channelZip(), collectionZip(1))
        install()
        val m = materials()
        assertNull(m.bytes(m.collection("kamakura")!!.pkg.resources["photo"]!!, "kamakura"))

        files["$base/col/photo.png"] = photo
        sync.refreshAll(unmetered = false)
        assertFalse(stored(photo))
        sync.refreshAll(unmetered = true)
        assertTrue(stored(photo))

        // A file that isn't what the collection says is never kept.
        files["$base/res/gate.png"] = png(1200, 800, seed = 9)
        ChannelStore(folder.root).deleteResource(sha256(gate))
        sync.refreshAll(unmetered = true)
        assertFalse(stored(gate))
    }

    @Test
    fun `a new version of a collection comes on Wi-Fi, and until then the collection waits`() {
        publish(1, channelZip(), collectionZip(1))
        install()
        val first = sync.state.collections.single()

        clock = clock.plus(Duration.ofDays(1))
        publish(2, channelZip(), collectionZip(2), collectionVersion = 2)
        sync.refreshAll(unmetered = false)
        assertTrue(materials().collections["kamakura"] is CollectionStatus.NotFetched)

        sync.refreshAll(unmetered = true)
        assertEquals(2, materials().collection("kamakura")!!.pkg.manifest.version)
        assertNull(ChannelStore(folder.root).readCollection(first.sha256))
    }

    @Test
    fun `a revoked or ungranted collection is not used`() {
        publish(1, channelZip(), collectionZip(1))
        install()
        clock = clock.plus(Duration.ofDays(1))
        val revoked = JSONArray().put(JSONObject().put("id", "kamakura-guide").put("reason", "権利の申し立て").put("revokedAt", clock.toString()))
        publish(2, channelZip(), collectionZip(1), revokedCollections = revoked)
        sync.refreshAll(unmetered = true)
        val status = materials().collections["kamakura"]
        assertEquals(RejectCode.COLLECTION_NOT_LISTED, (status as CollectionStatus.Unusable).code)
        assertNull(materials().collection("kamakura"))
    }

    @Test
    fun `removing the channel removes its collections and fetched resources`() {
        publish(1, channelZip(), collectionZip(1))
        install()
        assertTrue(stored(photo) && stored(gate))
        sync.uninstall(provider.id, "kamakura-history")
        assertTrue(sync.state.collections.isEmpty())
        assertTrue(ChannelStore(folder.root).resourceNames().isEmpty())
    }

    @Test
    fun `a channel needing audio is turned away by this app stage`() {
        val channel = channelZip {
            getJSONArray("resources").put(external("narration", "audio", "$base/res/n.ogg", voice, "audio/ogg").put("durationSec", 60).put("transcript", "intro"))
            getJSONArray("cues").put(JSONObject().put("id", "kirido").put("trigger", JSONObject().put("type", "point").put("lat", 35.3251).put("lon", 139.5562).put("radiusM", 40))
                .put("present", JSONArray().put(JSONObject().put("as", "play").put("resource", "narration"))))
        }
        publish(1, channel, collectionZip(1))
        sync.addProvider(base, provider.id)
        val e = assertThrows(ChannelSyncException::class.java) { sync.install(provider.id, "kamakura-history") }
        assertTrue(e.message!!, e.message!!.contains("unsupported_resource"))
    }
}

/** A PNG header with the given size: enough for the package checks, which read only the header. */
private fun png(width: Int, height: Int, seed: Int = 0): ByteArray {
    fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
        be32(13) + "IHDR".toByteArray() + be32(width) + be32(height) + byteArrayOf(8, 6, 0, 0, 0, seed.toByte(), 0, 0, 0)
}
