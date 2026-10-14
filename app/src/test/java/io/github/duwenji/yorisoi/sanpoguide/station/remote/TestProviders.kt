package io.github.duwenji.yorisoi.sanpoguide.station.remote

import io.github.duwenji.yorisoi.core.format.AccountIds
import io.github.duwenji.yorisoi.core.format.ProviderIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.time.Duration
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Signers for the tests, as the channel management system, its publishers and producers sign.

internal val b64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun KeyPair.raw(): ByteArray = public.encoded.takeLast(32).toByteArray()

private fun KeyPair.sign(message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
    initSign(private)
    update(message)
    sign()
}

private fun ed25519() = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

private fun signed(content: JSONObject, keyId: String, key: KeyPair): JSONObject {
    val bytes = content.toString().toByteArray()
    return JSONObject().put("payload", b64.encodeToString(bytes)).put("keyId", keyId).put("sig", b64.encodeToString(key.sign(bytes)))
}

private fun zip(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in files) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/** A channel provider as the channel management system signs (API-002): a root key and one signing key. */
internal class TestProvider {
    private val root = ed25519()
    private val signing = ed25519()
    val id: String = ProviderIds.of(root.raw())

    fun discovery(): String {
        val keyset = JSONObject().put("type", "keyset").put("provider", id).put("seq", 1).put("issuedAt", "2026-10-01T00:00:00Z")
            .put("keys", JSONArray().put(JSONObject().put("keyId", "k-1").put("publicKey", b64.encodeToString(signing.raw())).put("notBefore", "2026-10-01T00:00:00Z").put("notAfter", "2027-04-01T00:00:00Z")))
            .put("revokedKeys", JSONArray())
        return JSONObject().put("provider", id).put("name", "検証用の提供元").put("versions", JSONArray(listOf("v1"))).put("list", "/v1/channels.json")
            .put("rootKey", b64.encodeToString(root.raw())).put("keyset", signed(keyset, id, root)).toString()
    }

    fun ticket(channel: String, publisher: String, pkg: JSONObject, issuedAt: Instant, days: Long = 7, provider: String = id): String =
        signed(
            JSONObject().put("type", "test-ticket").put("provider", provider).put("channel", channel).put("publisher", publisher).put("package", pkg)
                .put("issuedAt", issuedAt.toString()).put("expiresAt", issuedAt.plus(Duration.ofDays(days)).toString()),
            "k-1", signing,
        ).toString()

    fun list(
        seq: Int, channels: JSONArray, revoked: JSONArray, issuedAt: Instant, expiresAt: Instant,
        collections: JSONArray? = null, revokedCollections: JSONArray? = null,
    ): String {
        // What every entry must carry (API-002), unless the test says otherwise.
        for (i in 0 until channels.length()) channels.getJSONObject(i).apply {
            if (!has("media")) put("media", JSONArray())
            if (!has("resourcesSize")) put("resourcesSize", 0)
            if (!has("activities")) put("activities", JSONArray(listOf("walk")))
            if (!has("genres")) put("genres", JSONArray())
            if (!has("requires")) put("requires", JSONArray())
            if (!has("minAppVersions")) put("minAppVersions", JSONObject().put("sanpoguide", 1))
        }
        val listed = collections ?: JSONArray()
        for (i in 0 until listed.length()) listed.getJSONObject(i).apply {
            if (!has("minAppVersions")) put("minAppVersions", JSONObject().put("sanpoguide", 1))
        }
        val body = JSONObject().put("type", "channel-list").put("format", 1).put("provider", id).put("seq", seq)
            .put("issuedAt", issuedAt.toString()).put("expiresAt", expiresAt.toString()).put("channels", channels).put("revoked", revoked)
            .put("collections", listed).put("revokedCollections", revokedCollections ?: JSONArray())
        val bytes = body.toString().toByteArray()
        val digest = JSONObject().put("type", "channel-list-digest").put("provider", id).put("seq", seq)
            .put("issuedAt", issuedAt.toString()).put("expiresAt", expiresAt.toString()).put("sha256", sha256(bytes)).put("size", bytes.size)
        return JSONObject().put("payload", b64.encodeToString(bytes)).put("digest", signed(digest, "k-1", signing)).toString()
    }
}

/** A publisher signing its package (API-003 F-6), or a producer its collection (API-004). */
internal class TestPublisher {
    private val key = ed25519()
    val accountId: String = AccountIds.of(key.raw())

    /** A channel; [extra] is merged into `channel.json` and [files] added (material, for one). */
    fun packageZip(channel: String, version: Int, extra: JSONObject? = null, files: Map<String, ByteArray> = emptyMap()): ByteArray {
        val manifest = JSONObject(
            """
            { "format": 1, "id": "$channel", "version": $version, "publisher": "$accountId",
              "name": "鎌倉歴史散歩", "summary": "寺社と武士の歴史", "lang": "ja", "activities": ["walk"], "greeting": "歴史をたどりながら歩きましょう。",
              "talk": { "level": "normal", "events": { "spot": true, "revisit": true, "milestone": true, "rest": false, "start": true, "finish": true } },
              "spots": { "prefer": ["temple"], "skip": [] }, "guide": { "length": "long" }, "mood": { "tone": true, "sound": "temple" } }
            """.trimIndent(),
        )
        extra?.keys()?.forEach { manifest.put(it, extra.get(it)) }
        val all = mapOf("channel.json" to manifest.toString().toByteArray(), "prompts/guide/focus.md" to "- 由来を中心に話す\n".toByteArray()) + files
        val payload = JSONObject().put("type", "channel-package").put("channel", channel).put("version", version).put("publisher", accountId)
            .put("files", JSONObject(all.mapValues { (_, b) -> sha256(b) })).toString().toByteArray()
        return zip(all + ("signature.json" to signature(payload)))
    }

    /** A collection of [resources], with the files they point at. */
    fun collectionZip(id: String, version: Int, resources: JSONArray, files: Map<String, ByteArray> = emptyMap(), grants: Any = "*"): ByteArray {
        val manifest = JSONObject().put("format", 1).put("id", id).put("version", version).put("producer", accountId)
            .put("name", "鎌倉音声ガイド").put("summary", "寺社の解説と写真").put("lang", "ja")
            .put("grants", JSONObject().put("publishers", grants)).put("resources", resources)
        val all = mapOf("collection.json" to manifest.toString().toByteArray()) + files
        val payload = JSONObject().put("type", "collection-package").put("collection", id).put("version", version).put("producer", accountId)
            .put("files", JSONObject(all.mapValues { (_, b) -> sha256(b) })).toString().toByteArray()
        return zip(all + ("signature.json" to signature(payload)))
    }

    private fun signature(payload: ByteArray) = JSONObject().put("payload", b64.encodeToString(payload)).put("publisherKey", b64.encodeToString(key.raw()))
        .put("sig", b64.encodeToString(key.sign(payload))).toString().toByteArray()
}
