package io.github.duwenji.sanpoguide.material

import io.github.duwenji.sanpoguide.data.Poi
import io.github.duwenji.sanpoguide.material.format.BuiltInProducers
import io.github.duwenji.sanpoguide.material.format.CollectionCheck
import io.github.duwenji.sanpoguide.material.format.CollectionValidator
import io.github.duwenji.sanpoguide.material.format.MaterialType
import io.github.duwenji.sanpoguide.material.format.ProducerDescriptor
import io.github.duwenji.sanpoguide.station.BuiltInStations
import io.github.duwenji.sanpoguide.station.Station
import io.github.duwenji.sanpoguide.station.StationSource
import io.github.duwenji.sanpoguide.station.format.CollectionStatus
import io.github.duwenji.sanpoguide.station.format.CueEvent
import io.github.duwenji.sanpoguide.material.format.Grants
import io.github.duwenji.sanpoguide.station.format.ListedCollection
import io.github.duwenji.sanpoguide.station.format.PackageArchive
import io.github.duwenji.sanpoguide.station.format.PackageRef
import io.github.duwenji.sanpoguide.station.format.PresentAs
import io.github.duwenji.sanpoguide.station.format.RejectCode
import io.github.duwenji.sanpoguide.station.format.StationCheck
import io.github.duwenji.sanpoguide.station.format.StationValidator
import io.github.duwenji.sanpoguide.station.remote.TestPublisher
import io.github.duwenji.sanpoguide.station.remote.TinkEd25519
import io.github.duwenji.sanpoguide.station.remote.sha256
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** A third party's cues on the walk (ADR-002 T-8b): which fire, and what each presentation comes to. */
class CuesTest {
    private val publisher = TestPublisher()
    private val producer = TestPublisher()
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val day1 = java.time.ZonedDateTime.of(2026, 10, 11, 10, 0, 0, 0, tokyo).toInstant().toEpochMilli()
    private val hachimangu = Poi("way/123456", "鶴岡八幡宮", 35.3260, 139.5565, "shrine", emptyMap())

    private val ownImage = png(1)
    private val gate = png(2)
    private val photo = png(3)

    private class Memory : CueMemory {
        val shown = HashMap<String, Long>()
        override fun lastShown(key: String) = shown[key]
        override fun record(key: String, at: Long) {
            shown[key] = at
        }
    }

    /** A built-in producer that answers [text] (or nothing), standing in for the AI and the fixed lines. */
    private class Fixed(override val descriptor: ProducerDescriptor, val text: String?) : Producer {
        override suspend fun produce(request: MaterialRequest): MaterialResult =
            text?.let { MaterialResult.Ready(Material(MaterialType.TEXT, Content.Text(it), descriptor.id)) } ?: MaterialResult.None
    }

    private fun builtIns(ai: String? = "AI の解説です。") = Producers(
        listOf(Fixed(BuiltInProducers.AI_GUIDE, ai), Fixed(BuiltInProducers.FALLBACK_TEXT, "決まった解説です。")),
        log = { _, _ -> },
    )

    private fun ext(id: String, bytes: ByteArray) = JSONObject().put("id", id).put("type", "image").put("url", "https://cdn.example/$id.png")
        .put("sha256", sha256(bytes)).put("size", bytes.size).put("mime", "image/png").put("alt", "代替テキスト $id")
        .put("credit", JSONObject().put("author", "鎌倉市観光協会").put("license", "permission"))

    private val about = JSONObject().put("type", "spot").put("osm", "way/999").put("name", "鶴岡八幡宮").put("lat", 35.3262).put("lon", 139.5566)

    private fun cue(id: String, trigger: JSONObject, vararg present: JSONObject, repeat: String? = null, narration: String? = null) =
        JSONObject().put("id", id).put("trigger", trigger).put("present", JSONArray(present.toList())).putOpt("repeat", repeat).putOpt("narration", narration)

    private fun speak(vararg source: String) = JSONObject().put("as", "speak").put("source", JSONArray(source.toList()))
    private fun card(vararg source: String) = JSONObject().put("as", "card").put("source", JSONArray(source.toList()))

    private val spotTrigger = JSONObject().put("type", "spot").put("osm", "way/123456").put("name", "鶴岡八幡宮")
    private val pointTrigger = JSONObject().put("type", "point").put("lat", 35.3251).put("lon", 139.5562).put("radiusM", 40)

    private fun station(cues: JSONArray, stored: Map<String, ByteArray> = mapOf(sha256(photo) to photo, sha256(gate) to gate), usable: Boolean = true): Station {
        val manifest = JSONObject()
            .put("resources", JSONArray()
                .put(JSONObject().put("id", "intro").put("type", "text").put("file", "resources/text/intro.md"))
                .put(JSONObject().put("id", "old-photo").put("type", "image").put("file", "resources/img/old.png").put("alt", "明治時代の本宮")
                    .put("credit", JSONObject().put("author", "不明").put("license", "PD")))
                .put(ext("gate", gate)))
            .put("cues", cues)
        // A collection no cue uses is turned away (bad_cue), so it's declared only when used.
        if ("kamakura" in cues.toString()) manifest.put("collections", JSONArray().put(JSONObject().put("as", "kamakura").put("id", "kamakura-guide").put("minVersion", 1)))
        val zip = publisher.packageZip(
            "kamakura-history", 1, manifest,
            mapOf("resources/text/intro.md" to "源頼朝が今の場所に移しました。\n".toByteArray(), "resources/img/old.png" to ownImage),
        )
        val pkg = (StationValidator.checkArchive(zip, null, TinkEd25519, mediaStage = 1) as StationCheck.Ok).station
        val col = producer.collectionZip(
            "kamakura-guide", 1,
            JSONArray()
                .put(JSONObject().put("id", "long").put("type", "text").put("file", "resources/text/long.md").put("about", about))
                .put(JSONObject().put("id", "short").put("type", "text").put("file", "resources/text/short.md").put("about", about))
                .put(ext("photo", photo).put("about", about)),
            mapOf("resources/text/long.md" to "長".repeat(300).plus("\n").toByteArray(), "resources/text/short.md" to "本宮は石段の上にあります。\n".toByteArray()),
        )
        val colPkg = (CollectionValidator.checkArchive(col, null, TinkEd25519) as CollectionCheck.Ok).collection
        val listed = ListedCollection(
            "kamakura-guide", 1, producer.accountId, "鎌倉市観光協会", null, "鎌倉音声ガイド", "寺社の解説と写真",
            listOf("text", "image"), photo.size.toLong(), listOf("history"), listOf("xn7"), Grants.EVERYONE,
            PackageRef("https://x/col.zip", sha256(col), col.size, 1), 1, java.time.Instant.EPOCH,
        )
        val status = if (usable) CollectionStatus.Usable(listed, colPkg) else CollectionStatus.Unusable(RejectCode.COLLECTION_NOT_LISTED, "revoked")
        val materials = ChannelMaterials(
            pkg.media, mapOf("kamakura" to status), PackageArchive.open(zip),
            if (usable) mapOf("kamakura" to PackageArchive.open(col)) else emptyMap(), stored::get,
        )
        return Station(pkg, BuiltInStations.standardPackage(), "p/kamakura-history@1", id = "p/kamakura-history",
            source = StationSource("p", "提供元", "鎌倉歴史散歩の会"), materials = materials)
    }

    private fun present(station: Station, fire: CueFire, ai: String? = "AI の解説です。") = runBlocking {
        CuePresenter(builtIns(ai), stage = 1, log = { _, _ -> }).present(fire, station, MaterialContext(station))
    }

    private fun text(m: Material) = (m.content as Content.Text).text

    @Test
    fun `a spot cue fires by the spot's id or name, as often as its repeat allows`() {
        val station = station(JSONArray()
            .put(cue("walk", spotTrigger, speak("self#intro")))
            .put(cue("day", JSONObject().put("type", "spot").put("osm", "node/1").put("name", "高徳院"), speak("self#intro"), repeat = "oncePerDay"))
            .put(cue("ever", pointTrigger, speak("self#intro"), repeat = "once")))
        val media = station.materials!!.media
        val memory = Memory()
        val finder = CueFinder(memory) { tokyo }
        val walk = mutableSetOf<String>()

        val fire = finder.spot(station.id, media, hachimangu, 20, walk, day1)!!
        assertEquals("walk", fire.cue.id)
        // The area and the node of one place have different ids; the name finds it.
        assertEquals("walk", finder.spot(station.id, media, hachimangu.copy(id = "node/42"), 20, walk, day1)?.cue?.id)
        assertNull(finder.spot(station.id, media, hachimangu.copy(id = "node/42", name = "白旗神社"), 20, walk, day1))
        finder.record(fire, walk, day1)
        assertNull(finder.spot(station.id, media, hachimangu, 20, walk, day1))
        assertNotNull(finder.spot(station.id, media, hachimangu, 20, mutableSetOf(), day1))
        assertTrue(memory.shown.isEmpty())

        val kotokuin = Poi("node/1", "高徳院", 35.3167, 139.5359, "temple", emptyMap())
        val daily = finder.spot(station.id, media, kotokuin, 10, walk, day1)!!
        finder.record(daily, walk, day1)
        assertNull(finder.spot(station.id, media, kotokuin, 10, mutableSetOf(), day1 + 3_600_000))
        assertNotNull(finder.spot(station.id, media, kotokuin, 10, mutableSetOf(), day1 + 86_400_000))

        // A point fires inside its radius, and once means once on this device.
        assertNull(finder.point(station.id, media, 35.3260, 139.5565, walk, day1))
        val inside = finder.point(station.id, media, 35.3252, 139.5562, walk, day1)!!
        finder.record(inside, walk, day1)
        assertNull(finder.point(station.id, media, 35.3252, 139.5562, mutableSetOf(), day1 + 30 * 86_400_000L))
    }

    @Test
    fun `event cues fire on their event`() {
        val station = station(JSONArray()
            .put(cue("hello", JSONObject().put("type", "event").put("event", "start"), speak("self#intro")))
            .put(cue("bye", JSONObject().put("type", "event").put("event", "finish"), speak("self#intro"))))
        val finder = CueFinder(Memory()) { tokyo }
        val fires = finder.events(station.id, station.materials!!.media, CueEvent.START, Subject.None, emptySet(), day1)
        assertEquals(listOf("hello"), fires.map { it.cue.id })
    }

    @Test
    fun `each presentation takes the first candidate that gives what it can show`() {
        val station = station(JSONArray().put(cue(
            "hachimangu", spotTrigger,
            speak("self#intro"),
            card("kamakura#photo", "self#old-photo"),
            JSONObject().put("as", "sheet").put("sources", JSONArray().put("self#gate").put("kamakura")),
        )))
        val fire = CueFinder(Memory()).spot(station.id, station.materials!!.media, hachimangu, 20, emptySet(), day1)!!
        val shown = present(station, fire)

        assertEquals(listOf(PresentAs.SPEAK, PresentAs.CARD, PresentAs.SHEET), shown.map { it.presentAs })
        assertEquals("源頼朝が今の場所に移しました。", text(shown[0].items.single()))
        val card = shown[1].items.single()
        assertArrayEquals(photo, (card.content as Content.EncodedImage).bytes)
        assertEquals("鎌倉市観光協会", card.credit?.via)
        assertEquals("代替テキスト photo", card.alt)
        // By subject: the collection's first resource about this spot (matched by name nearby) that a sheet can show.
        assertArrayEquals(gate, (shown[2].items[0].content as Content.EncodedImage).bytes)
        assertEquals("長".repeat(300), text(shown[2].items[1]))
    }

    @Test
    fun `material not on the device, an unusable collection or too long a text falls to the next candidate`() {
        val cues = JSONArray().put(cue("hachimangu", spotTrigger, card("kamakura#photo", "self#old-photo"), card("kamakura", "builtin:ai-guide", "builtin:fallback-text")))
        // The photo wasn't fetched (a metered network): the channel's own photo stands in.
        val notFetched = station(cues, stored = emptyMap())
        val fire = CueFinder(Memory()).spot(notFetched.id, notFetched.materials!!.media, hachimangu, 20, emptySet(), day1)!!
        val shown = present(notFetched, fire)
        assertArrayEquals(ownImage, (shown[0].items.single().content as Content.EncodedImage).bytes)
        // A card takes 200 characters: the long text is passed over for the short one.
        assertEquals("本宮は石段の上にあります。", text(shown[1].items.single()))

        // The collection was revoked, and the AI writes too long for a card: the fixed line.
        val revoked = station(cues, usable = false)
        val again = present(revoked, fire, ai = "長".repeat(250))
        assertArrayEquals(ownImage, (again[0].items.single().content as Content.EncodedImage).bytes)
        assertEquals("決まった解説です。", text(again[1].items.single()))
    }

    @Test
    fun `narration add puts the AI first, and what this app can't show yet is left out`() {
        val station = station(JSONArray().put(cue(
            "hachimangu", spotTrigger, speak("self#intro"),
            JSONObject().put("as", "notify").put("source", JSONArray(listOf("kamakura#long"))),
            narration = "add",
        )))
        val fire = CueFinder(Memory()).spot(station.id, station.materials!!.media, hachimangu, 20, emptySet(), day1)!!
        val shown = present(station, fire)
        assertEquals(listOf("AI の解説です。", "源頼朝が今の場所に移しました。"), shown.map { text(it.items.single()) })
    }

    @Test
    fun `material about a point is chosen by where it is`() {
        val station = station(JSONArray().put(cue("near", JSONObject().put("type", "point").put("lat", 35.3263).put("lon", 139.5566).put("radiusM", 50), card("kamakura"))))
        val fire = CueFinder(Memory()).point(station.id, station.materials!!.media, 35.3263, 139.5566, emptySet(), day1)!!
        assertEquals("本宮は石段の上にあります。", text(present(station, fire).single().items.single()))
        assertTrue(SubjectMatch.distance(35.3263, 139.5566, 35.3262, 139.5566) < 50)
    }
}

private fun png(seed: Int): ByteArray {
    fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
        be32(13) + "IHDR".toByteArray() + be32(640) + be32(480) + byteArrayOf(8, 6, 0, 0, 0, seed.toByte(), 0, 0, 0)
}
