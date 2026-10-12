package io.github.duwenji.michizure.core.format

import io.github.duwenji.michizure.core.format.material.CollectionManifest
import io.github.duwenji.michizure.core.format.material.CollectionPackage
import io.github.duwenji.michizure.core.format.material.Grants
import io.github.duwenji.michizure.core.format.material.MaterialType
import io.github.duwenji.michizure.core.format.material.Resource
import io.github.duwenji.michizure.core.format.material.ResourceLocation
import io.github.duwenji.michizure.core.format.material.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** API-004 確かめる手順 3・4 (確認観点 W-07・W-08・W-13) and API-003 1.2's errors for collections. */
class CollectionReferencesTest {
    private val publisher = "sg1" + "p".repeat(32)
    private val formerPublisher = "sg1" + "q".repeat(32)
    private val producer = "sg1" + "r".repeat(32)
    private val at = Instant.parse("2026-10-11T00:00:00Z")

    private fun resource(id: String, type: MaterialType) = Resource(
        id, type, if (type == MaterialType.TEXT) ResourceLocation.File("resources/text/$id.md") else ResourceLocation.External("https://cdn.example.com/$id", "a".repeat(64), 1000, "audio/ogg"),
        durationSec = if (type == MaterialType.AUDIO) 60 else null, alt = null, transcript = null, poster = null, credit = null, about = null, tags = emptyList(),
    )

    private fun pkg(version: Int = 2, grants: Grants = Grants.EVERYONE) = CollectionPackage(
        CollectionManifest(1, "kamakura-audio-guide", version, producer, "鎌倉音声ガイド", "音声ガイド", "ja", grants),
        Resources(listOf(resource("intro", MaterialType.TEXT), resource("audio", MaterialType.AUDIO)), mapOf("intro" to "鶴岡八幡宮の話")),
    )

    private fun listed(version: Int = 2, grants: Grants = Grants.EVERYONE) = ListedCollection(
        "kamakura-audio-guide", version, producer, "鎌倉市観光協会", null, "鎌倉音声ガイド", "音声ガイド",
        listOf("text", "audio"), 1000, listOf("history"), listOf("xn7"), grants,
        PackageRef("https://cdn.example.com/col/k.zip", "b".repeat(64), 900, 1), 14, at,
    )

    private fun channel(publisherId: String, change: PublisherChange? = null) = ListedChannel(
        "kamakura-history", 3, publisherId, "鎌倉歴史散歩の会", change, "鎌倉歴史散歩", "歴史", null, listOf("ja"), emptyList(), emptyList(),
        FileRef("https://cdn.example.com/i.png", "c".repeat(64)), PackageRef("https://cdn.example.com/p.zip", "d".repeat(64), 100, 1), 12, at,
    )

    private fun list(
        collections: List<ListedCollection> = listOf(listed()),
        revoked: List<RevokedCollection> = emptyList(),
        channels: List<ListedChannel> = listOf(channel(publisher)),
    ) = ChannelList("sc1" + "s".repeat(32), 1, at, at.plusSeconds(86_400), "e".repeat(64), channels, emptyList(), collections, revoked)

    private fun show(presentAs: PresentAs, vararg refs: Reference) = Presentation(presentAs, listOf(refs.toList()))

    private val media = StationMedia(
        Resources.NONE,
        listOf(CollectionUse("kamakura", "kamakura-audio-guide", 2)),
        emptyList(),
        listOf(
            Cue("hachimangu", Trigger.Spot("way/1", null), null, Repeat.ONCE_PER_WALK, listOf(
                show(PresentAs.SPEAK, Reference.Alias("kamakura", "intro"), Reference.BuiltIn("builtin:ai-guide")),
                show(PresentAs.PLAY, Reference.Alias("kamakura", "audio")),
            )),
            Cue("temples", Trigger.AnySpot(listOf(SpotKind.TEMPLE)), null, Repeat.ONCE_PER_WALK, listOf(show(PresentAs.SPEAK, Reference.Alias("kamakura", null)))),
        ),
    )

    private fun resolve(list: ChannelList = list(), packages: Map<String, CollectionPackage> = mapOf("kamakura-audio-guide" to pkg()), m: StationMedia = media, pub: String? = publisher) =
        CollectionReferences.resolve(m, pub, list, packages)

    private fun statusCode(r: CollectionResolution) = (r.statuses.getValue("kamakura") as CollectionStatus.Unusable).code

    @Test
    fun `a listed, granted collection resolves and counts toward the stage`() {
        val r = resolve()
        assertTrue(r.statuses.getValue("kamakura") is CollectionStatus.Usable)
        assertTrue(r.problems.isEmpty())
        assertNull(r.rejection())
        assertEquals(setOf(MaterialType.AUDIO), r.types("kamakura", "audio"))
        assertEquals(setOf(MaterialType.TEXT, MaterialType.AUDIO), r.types("kamakura", null))
        // Without the collection the least stage is 1; with it, the audio it plays makes it 2.
        assertEquals(1, MediaStage.required(media))
        assertEquals(2, MediaStage.required(media, r::types))
    }

    @Test
    fun `a collection off the list, revoked or too old can't be used`() {
        assertEquals(RejectCode.COLLECTION_NOT_LISTED, statusCode(resolve(list(collections = emptyList()))))
        assertEquals(RejectCode.COLLECTION_NOT_LISTED, statusCode(resolve(list(revoked = listOf(RevokedCollection("kamakura-audio-guide", null, "申し立て", at))))))
        assertTrue(resolve(list(revoked = listOf(RevokedCollection("kamakura-audio-guide", listOf(1), "古い版", at)))).statuses.getValue("kamakura") is CollectionStatus.Usable)
        assertEquals(RejectCode.COLLECTION_NOT_LISTED, statusCode(resolve(list(collections = listOf(listed(version = 1))), mapOf("kamakura-audio-guide" to pkg(version = 1)))))
    }

    @Test
    fun `grants decide who may use it, across a publisher's key transfer`() {
        val onlyOther = Grants(setOf("sg1" + "z".repeat(32)))
        val r = resolve(list(collections = listOf(listed(grants = onlyOther))), mapOf("kamakura-audio-guide" to pkg(grants = onlyOther)))
        assertEquals(RejectCode.NOT_GRANTED, statusCode(r))
        assertEquals(RejectCode.NOT_GRANTED, r.rejection()?.first)
        // W-13: granted to the publisher's former id, before it moved to a new key.
        val toFormer = Grants(setOf(formerPublisher))
        val moved = list(collections = listOf(listed(grants = toFormer)), channels = listOf(channel(publisher, PublisherChange(formerPublisher, at, "鍵の紛失"))))
        assertTrue(resolve(moved, mapOf("kamakura-audio-guide" to pkg(grants = toFormer))).statuses.getValue("kamakura") is CollectionStatus.Usable)
        // A built-in channel has no publisher: only collections open to everyone.
        assertEquals(RejectCode.NOT_GRANTED, statusCode(resolve(list(collections = listOf(listed(grants = toFormer))), pub = null)))
        assertTrue(resolve(pub = null).statuses.getValue("kamakura") is CollectionStatus.Usable)
    }

    @Test
    fun `a collection waits to be fetched, and must match the list once it is`() {
        val r = resolve(packages = emptyMap())
        assertTrue(r.statuses.getValue("kamakura") is CollectionStatus.NotFetched)
        assertNull(r.rejection())
        assertEquals(emptySet<MaterialType>(), r.types("kamakura", "audio"))
        assertEquals(RejectCode.BAD_MANIFEST, statusCode(resolve(packages = mapOf("kamakura-audio-guide" to pkg(grants = Grants(setOf(publisher)))))))
        assertEquals(RejectCode.BAD_MANIFEST, statusCode(resolve(packages = mapOf("kamakura-audio-guide" to pkg(version = 3)))))
    }

    @Test
    fun `references must name a resource the presentation can show`() {
        val bad = media.copy(cues = media.cues + Cue("x", Trigger.Spot("way/2", null), null, Repeat.ONCE_PER_WALK, listOf(
            show(PresentAs.SPEAK, Reference.Alias("kamakura", "missing")),
            show(PresentAs.PLAY, Reference.Alias("kamakura", "intro")),
        )))
        val r = resolve(m = bad)
        assertEquals(listOf(RejectCode.UNRESOLVED_REFERENCE, RejectCode.BAD_CUE), r.problems.map { it.code })
        assertEquals(RejectCode.UNRESOLVED_REFERENCE, r.rejection()?.first)
    }

    @Test
    fun `the resources a listed channel relies on are known by collection`() {
        assertEquals(mapOf("kamakura-audio-guide" to setOf("intro", "audio")), CollectionReferences.usedResources(media))
    }
}
