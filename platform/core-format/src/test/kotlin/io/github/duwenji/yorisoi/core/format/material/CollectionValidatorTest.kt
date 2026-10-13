package io.github.duwenji.yorisoi.core.format.material

import io.github.duwenji.yorisoi.core.format.JcaEd25519
import io.github.duwenji.yorisoi.core.format.RejectCode
import io.github.duwenji.yorisoi.core.format.Strictness
import io.github.duwenji.yorisoi.core.format.TestPublisher
import io.github.duwenji.yorisoi.core.format.zip
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** API-004 確かめる手順 1・2 and 確認観点 W-10, on collections built and signed as a producer would. */
class CollectionValidatorTest {
    private val producer = TestPublisher()
    private val listed = CollectionListedAs("kamakura-audio-guide", 2, producer.accountId)

    private fun manifest(change: JSONObject.() -> Unit = {}) = JSONObject(
        """
        {
          "format": 1, "id": "kamakura-audio-guide", "version": 2, "producer": "${producer.accountId}",
          "name": "鎌倉音声ガイド", "summary": "鎌倉市観光協会による寺社の音声ガイド", "lang": "ja",
          "grants": { "publishers": "*" },
          "resources": [
            { "id": "hachimangu-intro", "type": "text", "file": "resources/text/hachimangu.md", "tags": ["history", "faith"],
              "about": { "type": "spot", "osm": "way/123456", "name": "鶴岡八幡宮", "lat": 35.3260, "lon": 139.5565 } },
            { "id": "hachimangu-audio", "type": "audio",
              "url": "https://cdn.example.com/kamakura/hachimangu.ogg", "sha256": "${"b".repeat(64)}", "size": 2480000,
              "durationSec": 154, "mime": "audio/ogg", "transcript": "hachimangu-intro",
              "credit": { "author": "鎌倉市観光協会", "license": "permission" },
              "about": { "type": "point", "lat": 35.3258, "lon": 139.5564, "radiusM": 60 } }
          ]
        }
        """.trimIndent(),
    ).apply(change)

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The files signed as a producer does, with the payload overridable to build bad ones. */
    private fun signed(files: Map<String, ByteArray>, change: JSONObject.() -> Unit = {}): Map<String, ByteArray> {
        val m = JSONObject(String(files.getValue("collection.json")))
        val payload = JSONObject()
            .put("type", "collection-package")
            .put("collection", m.optString("id"))
            .put("version", m.optInt("version"))
            .put("producer", producer.accountId)
            .put("files", JSONObject(files.mapValues { (_, b) -> sha(b) }))
            .apply(change)
            .toString().toByteArray()
        val signature = JSONObject().put("payload", b64(payload)).put("publisherKey", b64(producer.publicKey)).put("sig", b64(producer.sign(payload)))
        return files + ("signature.json" to signature.toString().toByteArray())
    }

    private fun files(manifest: JSONObject = manifest()) = mapOf(
        "collection.json" to manifest.toString().toByteArray(),
        "resources/text/hachimangu.md" to "鶴岡八幡宮の本宮は、石段を上った先にあります。\n".toByteArray(),
    )

    private fun check(files: Map<String, ByteArray>, listedAs: CollectionListedAs? = listed) =
        CollectionValidator.checkArchive(zip(files.toList()), listedAs, JcaEd25519)

    private fun code(result: CollectionCheck): RejectCode {
        assertTrue("expected a rejection, got $result", result is CollectionCheck.Rejected)
        return (result as CollectionCheck.Rejected).code
    }

    @Test
    fun `a signed collection is read with its subjects, tags and grants`() {
        val result = check(signed(files()))
        assertTrue("$result", result is CollectionCheck.Ok)
        val c = (result as CollectionCheck.Ok).collection
        assertEquals(Grants.EVERYONE, c.manifest.grants)
        val intro = c.resources["hachimangu-intro"]!!
        assertEquals(About.Spot("way/123456", "鶴岡八幡宮", 35.3260, 139.5565), intro.about)
        assertEquals(listOf("xn7"), Regions.of(c.resources))
        assertEquals(listOf("history", "faith"), intro.tags)
        assertEquals(About.Point(35.3258, 139.5564, 60), c.resources["hachimangu-audio"]!!.about)
        assertEquals("鶴岡八幡宮の本宮は、石段を上った先にあります。", c.resources.texts["hachimangu-intro"])
    }

    @Test
    fun `grants name publishers or everyone`() {
        val one = "sg1" + "c".repeat(32)
        val c = (check(signed(files(manifest { put("grants", JSONObject().put("publishers", JSONArray().put(one))) }))) as CollectionCheck.Ok).collection
        assertTrue(c.manifest.grants.allows(one))
        assertFalse(c.manifest.grants.allows("sg1" + "d".repeat(32)))
        assertEquals(RejectCode.BAD_VALUE, code(check(signed(files(manifest { put("grants", JSONObject().put("publishers", JSONArray())) })))))
        assertEquals(RejectCode.BAD_VALUE, code(check(signed(files(manifest { put("grants", JSONObject().put("publishers", JSONArray().put("someone"))) })))))
        assertEquals(RejectCode.BAD_MANIFEST, code(check(signed(files(manifest { remove("grants") })))))
    }

    @Test
    fun `grants may name the apps that may use the collection (API-004 1_5 I-11, W-20)`() {
        fun grants(vararg apps: String) = JSONObject().put("publishers", "*").put("apps", JSONArray().apply { apps.forEach { put(it) } })
        val forSanpo = (check(signed(files(manifest { put("grants", grants("sanpoguide")) }))) as CollectionCheck.Ok).collection.manifest.grants
        assertEquals(setOf("sanpoguide"), forSanpo.apps)
        assertTrue(forSanpo.allowsApp("sanpoguide"))
        assertFalse(forSanpo.allowsApp("cycle"))
        assertTrue((check(signed(files())) as CollectionCheck.Ok).collection.manifest.grants.allowsApp("cycle"))
        assertEquals(RejectCode.BAD_VALUE, code(check(signed(files(manifest { put("grants", grants()) })))))
        // Review knows every app; an app reads ids of apps newer than itself.
        val newer = signed(files(manifest { put("grants", grants("sanpoguide", "boat")) }))
        assertEquals(RejectCode.BAD_VALUE, code(check(newer)))
        val read = CollectionValidator.checkArchive(zip(newer.toList()), listed, JcaEd25519, Strictness.APP)
        assertEquals(setOf("sanpoguide", "boat"), (read as CollectionCheck.Ok).collection.manifest.grants.apps)
    }

    @Test
    fun `the producer's signature must hold`() {
        // A channel's signature doesn't sign a collection.
        assertEquals(RejectCode.BAD_SIGNATURE, code(check(signed(files()) { put("type", "channel-package") })))
        assertEquals(RejectCode.BAD_SIGNATURE, code(check(signed(files()) { put("collection", "another") })))
        assertEquals(RejectCode.BAD_SIGNATURE, code(check(signed(files()) + ("resources/text/hachimangu.md" to "書き換え\n".toByteArray()))))
        assertEquals(RejectCode.BAD_SIGNATURE, code(check(files())))
        // W-10: the signer must be the producer the package and the list name.
        val other = "sg1" + "e".repeat(32)
        assertEquals(RejectCode.PUBLISHER_MISMATCH, code(check(signed(files(manifest { put("producer", other) })))))
        assertEquals(RejectCode.PUBLISHER_MISMATCH, code(check(signed(files()), listed.copy(producer = other))))
        assertEquals(RejectCode.BAD_MANIFEST, code(check(signed(files()), listed.copy(version = 3))))
        // After a key transfer the list names the new id; the version signed with the old key still counts.
        assertTrue(check(signed(files()), listed.copy(producer = other, formerProducers = setOf(producer.accountId))) is CollectionCheck.Ok)
    }

    @Test
    fun `resources keep to their rules`() {
        fun withFirst(change: JSONObject.() -> Unit) = manifest { getJSONArray("resources").getJSONObject(0).apply(change) }
        assertEquals(RejectCode.BAD_RESOURCE, code(check(signed(files(withFirst { put("tags", JSONArray(listOf("a", "b", "c", "d"))) })))))
        assertEquals(RejectCode.BAD_RESOURCE, code(check(signed(files(withFirst { put("tags", JSONArray().put("Not A Tag")) })))))
        assertEquals(RejectCode.BAD_RESOURCE, code(check(signed(files(withFirst { put("about", JSONObject().put("type", "spot").put("osm", "street/1")) })))))
        // A spot subject carries its approximate position (API-004 1.3).
        assertEquals(RejectCode.BAD_RESOURCE, code(check(signed(files(withFirst { getJSONObject("about").remove("lat") })))))
        assertEquals(RejectCode.BAD_RESOURCE, code(check(signed(files(withFirst { getJSONObject("about").put("lon", 190) })))))
        assertEquals(RejectCode.BAD_RESOURCE, code(check(signed(files(manifest { put("resources", JSONArray()) })))))
        // A tag this version doesn't know is kept, not refused (API-004「タグ」).
        assertTrue(check(signed(files(withFirst { put("tags", JSONArray().put("pilgrimage")) }))) is CollectionCheck.Ok)
        // Nothing but collection.json, signature.json and resources/.
        assertEquals(RejectCode.UNEXPECTED_FILE, code(check(signed(files() + ("channel.json" to "{}".toByteArray())))))
        assertEquals(RejectCode.UNEXPECTED_FILE, code(check(signed(files() + ("resources/text/spare.md" to "予備\n".toByteArray())))))
    }
}
