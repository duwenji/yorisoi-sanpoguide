package io.github.duwenji.yorisoi.sanpoguide.station.remote

import io.github.duwenji.yorisoi.sanpoguide.station.BuiltInStations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import io.github.duwenji.yorisoi.sanpoguide.station.companionSystemVars

/**
 * Third-party channels end to end on the JVM (API-002 アプリの振る舞い): a provider and publishers
 * sign as the channel management system and its publishers do, and Tink checks the signatures.
 */
class ChannelSyncTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val base = "https://channels.example"
    private val start = Instant.parse("2026-10-05T00:00:00Z")
    private var clock = start
    private val files = HashMap<String, ByteArray>()
    private val provider = TestProvider()
    private val alice = TestPublisher()
    private val bob = TestPublisher()
    private val standard = BuiltInStations.standardPackage()
    private lateinit var sync: ChannelSync

    private val http = ProviderHttp { url, max ->
        val bytes = files[url] ?: throw IOException("HTTP 404")
        if (bytes.size > max) throw TooLargeException(url)
        bytes
    }

    private fun newSync() = ChannelSync(ChannelStore(folder.root), http, TinkEd25519, appVersion = 1, now = { clock })

    @Before
    fun setUp() {
        files["$base/.well-known/sanpo-channels"] = provider.discovery().toByteArray()
        sync = newSync()
    }

    /** Publishes [packages] (and [revoked]) as list [seq]; returns the list entries. */
    private fun publish(seq: Int, vararg packages: Pair<TestPublisher, Int>, revoked: JSONArray = JSONArray(), change: JSONObject? = null, issuedAt: Instant = clock) {
        val channels = packages.map { (publisher, version) ->
            val zip = publisher.packageZip("kamakura-history", version)
            val sha = sha256(zip)
            files["$base/pkg/$sha.zip"] = zip
            JSONObject()
                .put("id", "kamakura-history").put("version", version).put("publisher", publisher.accountId).put("publisherName", "鎌倉歴史散歩の会")
                .put("name", "鎌倉歴史散歩").put("summary", "寺社と武士の歴史").put("lang", JSONArray(listOf("ja")))
                .put("icon", JSONObject().put("url", "$base/icons/x.png").put("sha256", "0".repeat(64)))
                .put("package", JSONObject().put("url", "$base/pkg/$sha.zip").put("sha256", sha).put("size", zip.size).put("format", 1))
                .put("approvedAt", issuedAt.toString())
                .apply { if (change != null) put("publisherChange", change) }
        }
        files["$base/v1/channels.json"] = provider.list(seq, JSONArray(channels), revoked, issuedAt, issuedAt.plus(Duration.ofDays(14))).toByteArray()
    }

    private fun useKamakura() {
        sync.addProvider(base, provider.id)
        sync.install(provider.id, "kamakura-history")
    }

    @Test
    fun `a channel from an added provider becomes usable once taken onto the device`() {
        publish(1, alice to 1)
        sync.addProvider("$base/", provider.id)
        val view = sync.providers().single()
        assertEquals("検証用の提供元", view.name)
        assertEquals(ListedStatus.AVAILABLE, view.channels.single().status)
        assertTrue(sync.stations(standard).isEmpty())

        sync.install(provider.id, "kamakura-history")
        val station = sync.stations(standard).single()
        assertEquals("${provider.id}/kamakura-history", station.id)
        assertEquals("${provider.id}/kamakura-history@1", station.key)
        assertEquals("鎌倉歴史散歩の会", station.source?.publisherName)
        assertFalse(station.isBuiltIn)
        // Slots the channel leaves empty come from the standard channel.
        assertNotNull(station.companionSystemVars())
        assertEquals(ListedStatus.INSTALLED, sync.providers().single().channels.single().status)

        // Kept across restarts.
        assertEquals(1, newSync().stations(standard).size)
    }

    @Test
    fun `a provider is added only with the id the user got elsewhere, over https`() {
        publish(1, alice to 1)
        assertThrows(ChannelSyncException::class.java) { sync.addProvider(base, "sc1" + "a".repeat(32)) }
        assertThrows(ChannelSyncException::class.java) { sync.addProvider("http://channels.example", provider.id) }
        assertThrows(ChannelSyncException::class.java) { sync.addProvider(base, "not-an-id") }
        assertTrue(sync.state.providers.isEmpty())
        // Debug builds may use the emulator's host.
        files["http://10.0.2.2:8080/.well-known/sanpo-channels"] = provider.discovery().toByteArray()
        val debug = ChannelSync(ChannelStore(folder.newFolder()), http, TinkEd25519, 1, { clock }, allowLocalHttp = true)
        debug.addProvider("http://10.0.2.2:8080", provider.id)
        assertEquals(1, debug.state.providers.size)
    }

    @Test
    fun `a withdrawn channel is removed with its reason, and a bad list leaves the last good one in use`() {
        publish(1, alice to 1)
        useKamakura()

        // A list that fails its check is dropped; the channel stays usable on the last list.
        files["$base/v1/channels.json"] = files.getValue("$base/v1/channels.json").let { String(it).replace("\"payload\":\"ey", "\"payload\":\"ex").toByteArray() }
        clock = clock.plus(Duration.ofDays(1))
        sync.refreshDue(unmetered = true)
        assertTrue(sync.state.providers.single().error!!.startsWith("bad_"))
        assertEquals(1, sync.stations(standard).size)

        publish(2, revoked = JSONArray().put(JSONObject().put("id", "kamakura-history").put("reason", "審査基準に反する内容").put("revokedAt", clock.toString())))
        sync.refreshAll(unmetered = false)
        assertTrue(sync.stations(standard).isEmpty())
        assertTrue(sync.state.installed.isEmpty())
        assertEquals("「鎌倉歴史散歩」はリスト（検証用の提供元）から取り下げられました: 審査基準に反する内容", sync.state.notices.single().text)
    }

    @Test
    fun `an older list is refused as a rollback`() {
        publish(5, alice to 1)
        useKamakura()
        publish(4, alice to 2)
        sync.refreshAll(unmetered = true)
        assertTrue(sync.state.providers.single().error!!.startsWith("rollback"))
        assertEquals(1, sync.state.installed.single().version)
    }

    @Test
    fun `an expired list stops its channels and is said once`() {
        publish(1, alice to 1)
        useKamakura()
        files.remove("$base/v1/channels.json")
        clock = clock.plus(Duration.ofDays(15))
        sync.refreshDue(unmetered = true)
        assertTrue(sync.state.providers.single().error!!.startsWith("fetch_failed"))
        assertTrue(sync.stations(standard).isEmpty())
        assertFalse(sync.providers().single().listValid)
        sync.refreshAll(unmetered = true)
        assertEquals(1, sync.state.notices.size)
        // Back once the provider is reachable again.
        publish(2, alice to 1)
        sync.refreshAll(unmetered = true)
        assertEquals(1, sync.stations(standard).size)
    }

    @Test
    fun `a newer version comes on an unmetered network, or when the user asks`() {
        publish(1, alice to 1)
        useKamakura()
        publish(2, alice to 2)
        sync.refreshAll(unmetered = false)
        assertEquals(ListedStatus.UPDATE_PENDING, sync.providers().single().channels.single().status)
        assertEquals(1, sync.stations(standard).single().manifest.version)
        sync.refreshAll(unmetered = true)
        assertEquals(2, sync.stations(standard).single().manifest.version)

        publish(3, alice to 3)
        sync.refreshAll(unmetered = false)
        sync.install(provider.id, "kamakura-history")
        assertEquals("${provider.id}/kamakura-history@3", sync.stations(standard).single().key)
    }

    @Test
    fun `a new publisher is taken only with publisherChange from the one on the device`() {
        publish(1, alice to 1)
        useKamakura()

        // Without the provider's word for it: the old version stays (publisher_changed).
        publish(2, bob to 2)
        sync.refreshAll(unmetered = true)
        assertThrows(ChannelSyncException::class.java) { sync.install(provider.id, "kamakura-history") }
        assertEquals(ListedStatus.BLOCKED, sync.providers().single().channels.single().status)
        assertEquals(1, sync.stations(standard).single().manifest.version)

        // With it: taken, and the user is told why.
        val change = JSONObject().put("from", alice.accountId).put("at", clock.toString()).put("reason", "配信元の鍵の紛失")
        publish(3, bob to 3, change = change)
        sync.refreshAll(unmetered = false)
        sync.install(provider.id, "kamakura-history")
        assertEquals(bob.accountId, sync.state.installed.single().publisher)
        assertNull(sync.state.installed.single().blocked)
        assertTrue(sync.state.notices.last().text.contains("配信元の鍵の紛失"))
    }

    @Test
    fun `packages that differ from the list, or need a newer app, are not taken`() {
        publish(1, alice to 1)
        sync.addProvider(base, provider.id)
        val url = files.keys.first { it.contains("/pkg/") }
        files[url] = files.getValue(url).copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertTrue(assertThrows(ChannelSyncException::class.java) { sync.install(provider.id, "kamakura-history") }.message!!.contains("hash_mismatch"))

        val old = ChannelSync(ChannelStore(folder.newFolder()), http, TinkEd25519, appVersion = 0, now = { clock })
        publish(2, alice to 1)
        old.addProvider(base, provider.id)
        assertEquals(ListedStatus.NEEDS_APP_UPDATE, old.providers().single().channels.single().status)
        assertThrows(ChannelSyncException::class.java) { old.install(provider.id, "kamakura-history") }
    }

    @Test
    fun `a disabled or removed provider's channels are not used`() {
        publish(1, alice to 1)
        useKamakura()
        sync.setEnabled(provider.id, false)
        assertTrue(sync.stations(standard).isEmpty())
        sync.setEnabled(provider.id, true)
        assertEquals(1, sync.stations(standard).size)
        sync.removeProvider(provider.id)
        assertTrue(sync.stations(standard).isEmpty())
        assertTrue(folder.root.resolve("pkg").listFiles().orEmpty().isEmpty())
    }

    /** A package on the trial path and a ticket for it, as the console issues one (API-002 試用チケット). */
    private fun ticketFor(publisher: TestPublisher, version: Int, channel: String = "kamakura-history", days: Long = 7, ticketPublisher: String = publisher.accountId): String {
        val zip = publisher.packageZip("kamakura-history", version)
        val sha = sha256(zip)
        files["$base/trial/$sha.zip"] = zip
        val pkg = JSONObject().put("url", "$base/trial/$sha.zip").put("sha256", sha).put("size", zip.size).put("format", 1)
        return provider.ticket(channel, ticketPublisher, pkg, clock, days)
    }

    @Test
    fun `a test ticket gives a trial channel in developer mode only, until it expires`() {
        publish(1)
        sync.addProvider(base, provider.id)
        val ticket = ticketFor(alice, 4)
        assertThrows(ChannelSyncException::class.java) { sync.loadTicket(ticket, developerMode = false) }

        val id = sync.loadTicket(ticket, developerMode = true)
        assertEquals("trial:${provider.id}/kamakura-history", id)
        assertTrue(sync.stations(standard, developerMode = false).isEmpty())
        val trial = sync.stations(standard, developerMode = true).single()
        assertEquals(id, trial.id)
        assertTrue(trial.source!!.trial)
        assertEquals("鎌倉歴史散歩（審査前の試用）", trial.displayName)
        assertEquals(4, trial.manifest.version)

        // A newer ticket for the same channel replaces it; an expired one is gone.
        sync.loadTicket(ticketFor(alice, 5), developerMode = true)
        assertEquals(1, sync.state.trials.size)
        assertEquals(5, sync.stations(standard, developerMode = true).single().manifest.version)
        clock = clock.plus(Duration.ofDays(8))
        assertTrue(sync.stations(standard, developerMode = true).isEmpty())
        publish(2, issuedAt = clock)
        sync.refreshDue(unmetered = false)
        assertTrue(sync.state.trials.isEmpty())
        assertTrue(folder.root.resolve("pkg").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a test ticket is refused from an unknown provider, past 7 days, or for another channel or publisher`() {
        val ticket = ticketFor(alice, 1)
        assertTrue(assertThrows(ChannelSyncException::class.java) { sync.loadTicket(ticket, true) }.message!!.contains("先に足して"))
        publish(1)
        sync.addProvider(base, provider.id)
        assertThrows(ChannelSyncException::class.java) { sync.loadTicket(ticketFor(alice, 1, days = 8), true) }
        assertThrows(ChannelSyncException::class.java) { sync.loadTicket(ticketFor(alice, 1, channel = "other-channel"), true) }
        assertThrows(ChannelSyncException::class.java) { sync.loadTicket(ticketFor(alice, 1, ticketPublisher = bob.accountId), true) }
        assertThrows(ChannelSyncException::class.java) { sync.loadTicket("not a ticket", true) }
        assertTrue(sync.state.trials.isEmpty())
        clock = clock.plus(Duration.ofDays(8))
        assertThrows(ChannelSyncException::class.java) { sync.loadTicket(ticket, true) }
    }
}

