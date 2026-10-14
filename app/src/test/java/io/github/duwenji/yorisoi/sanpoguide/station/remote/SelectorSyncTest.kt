package io.github.duwenji.yorisoi.sanpoguide.station.remote

import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.sanpoguide.material.Content
import io.github.duwenji.yorisoi.sanpoguide.material.CueFinder
import io.github.duwenji.yorisoi.sanpoguide.material.CueMemory
import io.github.duwenji.yorisoi.sanpoguide.material.MaterialContext
import io.github.duwenji.yorisoi.sanpoguide.material.MaterialRequest
import io.github.duwenji.yorisoi.sanpoguide.material.MaterialResult
import io.github.duwenji.yorisoi.sanpoguide.material.SelectorProducer
import io.github.duwenji.yorisoi.sanpoguide.material.Subject
import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.core.format.material.Regions
import io.github.duwenji.yorisoi.sanpoguide.station.BuiltInStations
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Selectors and cues on any spot of a kind (API-004「基準で選ぶ素材提供者」P-6, API-003):
 * which collections are fetched ahead and where, in what order they are drawn from, and when a
 * cue on any shrine fires.
 */
class SelectorSyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val base = "https://channels.example"
    private val clock = Instant.parse("2026-10-11T00:00:00Z")
    private val files = HashMap<String, ByteArray>()
    private val fetched = ArrayList<String>()
    private val provider = TestProvider()
    private val alice = TestPublisher()
    private val kamakuraCity = TestPublisher()
    private val templeGuild = TestPublisher()
    private val sapporo = TestPublisher()
    private val standard = BuiltInStations.standardPackage()
    private lateinit var sync: ChannelSync

    // Kamakura, and a place about 900km away.
    private val hachimangu = Poi("way/123456", "鶴岡八幡宮", 35.3260, 139.5565, "神社", emptyMap())
    private val kamakura = Regions.geohash(hachimangu.lat, hachimangu.lon)
    private val farAway = Regions.geohash(43.0618, 141.3545)
    private var here: Set<String>? = null

    private val http = ProviderHttp { url, max ->
        fetched += url
        val bytes = files[url] ?: throw IOException("HTTP 404")
        if (bytes.size > max) throw TooLargeException(url)
        bytes
    }

    @Before
    fun setUp() {
        files["$base/.well-known/sanpo-channels"] = provider.discovery().toByteArray()
        sync = ChannelSync(ChannelStore(folder.root), http, TinkEd25519, appVersion = 1, now = { clock }, region = { here })
    }

    /** A channel with one selector of history photos, used by a cue on any shrine. */
    private fun channelZip(preferred: List<String> = emptyList()): ByteArray {
        val manifest = JSONObject()
            .put("selectors", JSONArray().put(JSONObject().put("as", "history")
                .put("match", JSONObject().put("type", "text").put("tags", JSONArray(listOf("history", "faith"))))
                .put("producers", JSONArray(preferred))))
            .put("cues", JSONArray().put(JSONObject().put("id", "shrines")
                .put("trigger", JSONObject().put("type", "spot").put("any", true).put("categories", JSONArray(listOf("shrine"))))
                .put("present", JSONArray().put(JSONObject().put("as", "speak").put("source", "history")))))
        return alice.packageZip("kamakura-history", 1, manifest, emptyMap())
    }

    private class Col(
        val id: String, val by: TestPublisher, val text: String, val tag: String, val lat: Double?, val lon: Double?, val approved: Instant,
        val grants: Any = "*", val promotional: Boolean = false,
    )

    private fun collectionZip(c: Col): ByteArray {
        val r = JSONObject().put("id", "note").put("type", "text").put("file", "resources/text/note.md").put("tags", JSONArray(listOf(c.tag)))
        if (c.lat != null && c.lon != null) {
            r.put("about", JSONObject().put("type", "spot").put("osm", hachimangu.id).put("name", hachimangu.name).put("lat", c.lat).put("lon", c.lon))
        }
        return c.by.collectionZip(c.id, 1, JSONArray().put(r), mapOf("resources/text/note.md" to "${c.text}\n".toByteArray()), c.grants)
    }

    private fun publish(collections: List<Col>, preferred: List<String> = emptyList()) {
        val channel = channelZip(preferred)
        val sha = sha256(channel)
        files["$base/pkg/$sha.zip"] = channel
        val entry = JSONObject()
            .put("id", "kamakura-history").put("version", 1).put("publisher", alice.accountId).put("publisherName", "鎌倉歴史散歩の会")
            .put("name", "鎌倉歴史散歩").put("summary", "寺社と武士の歴史").put("lang", JSONArray(listOf("ja")))
            .put("icon", JSONObject().put("url", "$base/icons/x.png").put("sha256", "0".repeat(64)))
            .put("package", JSONObject().put("url", "$base/pkg/$sha.zip").put("sha256", sha).put("size", channel.size).put("format", 1))
            .put("minAppVersion", 1).put("approvedAt", clock.toString())
        val listed = JSONArray()
        for (c in collections) {
            val zip = collectionZip(c)
            val colSha = sha256(zip)
            files["$base/col/$colSha.zip"] = zip
            val regions = if (c.lat != null && c.lon != null) listOf(Regions.geohash(c.lat, c.lon)) else emptyList()
            listed.put(JSONObject()
                .put("id", c.id).put("version", 1).put("producer", c.by.accountId).put("producerName", "制作元 ${c.id}")
                .put("name", c.id).put("summary", "解説").put("media", JSONArray(listOf("text")))
                .put("resourcesSize", zip.size).put("tags", JSONArray(listOf(c.tag))).put("regions", JSONArray(regions))
                .put("grants", JSONObject().put("publishers", c.grants))
                .put("package", JSONObject().put("url", "$base/col/$colSha.zip").put("sha256", colSha).put("size", zip.size).put("format", 1))
                .put("minAppVersion", 1).put("approvedAt", c.approved.toString())
                .apply { if (c.promotional) put("promotional", true) })
        }
        files["$base/v1/channels.json"] = provider.list(1, JSONArray().put(entry), JSONArray(), clock, clock.plus(Duration.ofDays(14)), listed).toByteArray()
    }

    private val near = Col("kamakura-notes", kamakuraCity, "八幡宮の歴史です", "history", 35.3260, 139.5565, clock.minus(Duration.ofDays(30)))
    private val everywhere = Col("shrine-notes", templeGuild, "神社の作法です", "faith", null, null, clock.minus(Duration.ofDays(1)))
    private val far = Col("sapporo-notes", sapporo, "札幌の歴史です", "history", 43.0618, 141.3545, clock)
    private val food = Col("food-notes", sapporo, "名物です", "food", null, null, clock)

    private fun selected() = sync.stations(standard).single().materials!!.selected["history"].orEmpty().map { it.listed.id }

    @Test
    fun `selectors draw only on an unmetered network, from around here and from nowhere in particular`() {
        publish(listOf(near, everywhere, far, food))
        here = Regions.around(hachimangu.lat, hachimangu.lon)
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history", unmetered = false)
        assertEquals(emptyList<String>(), selected())

        sync.refreshDue(unmetered = true)
        // Best first: the most recently approved; a collection with no matching tag is never a candidate.
        assertEquals(listOf("shrine-notes", "kamakura-notes"), selected())
        assertTrue(kamakura in here!! && farAway !in here!!)
    }

    @Test
    fun `a collection not granted to the channel's publisher is never drawn from`() {
        val members = Col("members-notes", templeGuild, "会員向けです", "history", null, null, clock, grants = JSONArray(listOf(TestPublisher().accountId)))
        publish(listOf(everywhere, members))
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history", unmetered = true)
        assertEquals(listOf("shrine-notes"), selected())
    }

    @Test
    fun `a promotional collection is never drawn from, only named by a channel (ADR-002 D-33)`() {
        val ads = Col("shop-notes", templeGuild, "お店の紹介です", "history", null, null, clock, promotional = true)
        publish(listOf(everywhere, ads))
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history", unmetered = true)
        assertEquals(listOf("shrine-notes"), selected())
    }

    @Test
    fun `the producers a selector names come first, and moving away drops what was about here`() {
        publish(listOf(near, everywhere, far), preferred = listOf(kamakuraCity.accountId))
        here = Regions.around(hachimangu.lat, hachimangu.lon)
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history", unmetered = true)
        assertEquals(listOf("kamakura-notes", "shrine-notes"), selected())

        here = Regions.around(43.0618, 141.3545)
        sync.refreshDue(unmetered = true)
        // The one about Kamakura went; the newer one about Sapporo comes before the older one about nowhere.
        assertEquals(listOf("sapporo-notes", "shrine-notes"), selected())
    }

    @Test
    fun `a selector's producer gives material about the spot, else what is about nowhere`() = runBlocking {
        publish(listOf(near, everywhere), preferred = listOf(kamakuraCity.accountId))
        here = Regions.around(hachimangu.lat, hachimangu.lon)
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history", unmetered = true)
        val station = sync.stations(standard).single()
        val m = station.materials!!
        val context = MaterialContext(station)
        val selector = m.media.selectors.single()
        val producer = SelectorProducer(selector, m.selected["history"]!!, m.lang, m::bytes, trial = false, stage = 1)

        val atShrine = producer.produce(MaterialRequest(Subject.Spot(hachimangu, 30), MaterialType.TEXT, context)) as MaterialResult.Ready
        assertEquals("八幡宮の歴史です", (atShrine.material.content as Content.Text).text)
        assertEquals("制作元 kamakura-notes", atShrine.material.credit?.via)
        // Elsewhere the collection about the shrine has nothing; the one about nowhere has no subject to match.
        val elsewhere = Poi("node/1", "白旗神社", 35.40, 139.60, "神社", emptyMap())
        assertEquals(MaterialResult.None, producer.produce(MaterialRequest(Subject.Spot(elsewhere, 30), MaterialType.TEXT, context)))
    }

    @Test
    fun `a cue on any shrine fires once a walk per shrine, and a cue naming the spot comes instead`() {
        publish(emptyList())
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history")
        val media = sync.stations(standard).single().materials!!.media
        val finder = CueFinder(object : CueMemory {
            override fun lastShown(key: String): Long? = null
            override fun record(key: String, at: Long) = Unit
        })
        val shown = mutableSetOf<String>()
        val shirahata = Poi("node/1", "白旗神社", 35.40, 139.60, "神社", emptyMap())
        val temple = Poi("node/2", "建長寺", 35.33, 139.55, "寺院", emptyMap())

        val first = finder.spot("c", media, hachimangu, 30, shown, 0)!!
        finder.record(first, shown, 0)
        assertNull(finder.spot("c", media, hachimangu, 30, shown, 0))
        assertTrue(finder.spot("c", media, shirahata, 30, shown, 0) != null)
        assertNull(finder.spot("c", media, temple, 30, shown, 0))

        // A cue naming the spot is the only one shown there.
        val named = media.copy(cues = media.cues + media.cues.single().copy(
            id = "hachimangu", trigger = io.github.duwenji.yorisoi.core.format.Trigger.Spot(hachimangu.id, null),
        ))
        assertEquals("hachimangu", finder.spot("c", named, hachimangu, 30, mutableSetOf(), 0)!!.cue.id)
    }
}
