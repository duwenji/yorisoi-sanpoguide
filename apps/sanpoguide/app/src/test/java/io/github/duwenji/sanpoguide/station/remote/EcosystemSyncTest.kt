package io.github.duwenji.sanpoguide.station.remote

import io.github.duwenji.sanpoguide.station.BuiltInStations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
 * SanpoGuide choosing from a list shared by every app of the ecosystem (API-003 2.0 G-4, API-002
 * 1.4 P-19・P-20): only walking channels it can show are offered and taken in.
 */
class EcosystemSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private val base = "https://channels.example.com"
    private val clock = Instant.parse("2026-10-12T00:00:00Z")
    private val files = HashMap<String, ByteArray>()
    private val provider = TestProvider()
    private val alice = TestPublisher()
    private val standard = BuiltInStations.standardPackage()
    private lateinit var sync: ChannelSync

    private val http = ProviderHttp { url, max ->
        val bytes = files[url] ?: throw IOException("HTTP 404")
        if (bytes.size > max) throw TooLargeException(url)
        bytes
    }

    @Before
    fun setUp() {
        files["$base/.well-known/sanpo-channels"] = provider.discovery().toByteArray()
        sync = ChannelSync(ChannelStore(folder.root), http, TinkEd25519, appVersion = 5, now = { clock })
    }

    /** One channel [id] per entry: its package's extra fields, and what the list says of it. */
    private fun publish(vararg channels: Triple<String, JSONObject?, JSONObject.() -> Unit>) {
        val entries = channels.map { (id, extra, listed) ->
            val zip = alice.packageZip(id, 1, extra)
            val sha = sha256(zip)
            files["$base/pkg/$sha.zip"] = zip
            JSONObject()
                .put("id", id).put("version", 1).put("publisher", alice.accountId).put("publisherName", "鎌倉歴史散歩の会")
                .put("name", id).put("summary", "s").put("lang", JSONArray(listOf("ja")))
                .put("icon", JSONObject().put("url", "$base/icons/x.png").put("sha256", "0".repeat(64)))
                .put("package", JSONObject().put("url", "$base/pkg/$sha.zip").put("sha256", sha).put("size", zip.size).put("format", extra?.optInt("format", 1) ?: 1))
                .put("minAppVersion", 1).put("approvedAt", clock.toString())
                .apply(listed)
        }
        files["$base/v1/channels.json"] = provider.list(1, JSONArray(entries), JSONArray(), clock, clock.plus(Duration.ofDays(14))).toByteArray()
        sync.addProvider(base, provider.id)
    }

    private fun format2(vararg activities: String, change: JSONObject.() -> Unit = {}) =
        JSONObject().put("format", 2).put("activities", JSONArray(activities.toList())).apply(change)

    private fun copies(vararg activities: String): JSONObject.() -> Unit = { put("activities", JSONArray(activities.toList())) }

    private fun offered() = sync.providers().single().channels.map { it.channel.id }

    @Test
    fun `walking channels are offered, others aren't (V-25, V-28)`() {
        publish(
            Triple("walk-and-ride", format2("walk", "cycle"), copies("walk", "cycle")),
            Triple("ride-only", format2("cycle"), copies("cycle")),
            Triple("old-walk", null) {},
        )
        assertEquals(listOf("walk-and-ride", "old-walk"), offered())
        assertThrows(ChannelSyncException::class.java) { sync.install(provider.id, "ride-only") }
        sync.install(provider.id, "walk-and-ride")
        sync.install(provider.id, "old-walk")
        assertEquals(setOf("walk-and-ride", "old-walk"), sync.stations(standard).map { it.manifest.id }.toSet())
    }

    @Test
    fun `a channel needing what SanpoGuide lacks isn't offered`() {
        val needsSound = format2("walk") { put("requires", JSONObject().put("features", JSONArray(listOf("present:play")))) }
        publish(Triple("with-sound", needsSound) { copies("walk")(); put("requires", JSONArray(listOf("present:play"))) })
        assertEquals(emptyList<String>(), offered())
    }

    @Test
    fun `a package whose restrictions SanpoGuide can't read isn't taken in (V-26)`() {
        // A newer talk level: the list's copy can't say so, the package does.
        val newer = format2("walk") { put("talk", JSONObject("""{ "level": "nonstop", "events": { "spot": true, "revisit": true, "milestone": true, "rest": false, "start": true, "finish": true } }""")) }
        publish(Triple("newer", newer, copies("walk")))
        assertEquals(listOf("newer"), offered())
        val e = assertThrows(ChannelSyncException::class.java) { sync.install(provider.id, "newer") }
        assertTrue(e.message!!.contains("使えない"))
    }

    @Test
    fun `the package must agree with the list's copy (API-002 P-19)`() {
        publish(Triple("walk-and-ride", format2("walk", "cycle"), copies("walk")))
        val e = assertThrows(ChannelSyncException::class.java) { sync.install(provider.id, "walk-and-ride") }
        assertTrue(e.message!!.contains("bad_manifest"))
    }

    @Test
    fun `SanpoGuide's own minimum version decides (API-002 P-20)`() {
        publish(
            Triple("for-later", format2("walk")) { copies("walk")(); put("minAppVersion", 1); put("minAppVersions", JSONObject().put("sanpoguide", 9)) },
            Triple("other-app-later", format2("walk")) { copies("walk")(); put("minAppVersion", 99); put("minAppVersions", JSONObject().put("cycle", 9)) },
        )
        val status = sync.providers().single().channels.associate { it.channel.id to it.status }
        assertEquals(ListedStatus.NEEDS_APP_UPDATE, status["for-later"])
        assertEquals(ListedStatus.AVAILABLE, status["other-app-later"])
    }
}
