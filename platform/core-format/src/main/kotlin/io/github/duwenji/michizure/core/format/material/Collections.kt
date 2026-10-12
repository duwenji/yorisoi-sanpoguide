package io.github.duwenji.michizure.core.format.material

import io.github.duwenji.michizure.core.format.ACCOUNT_ID_PATTERN
import io.github.duwenji.michizure.core.format.ArchiveException
import io.github.duwenji.michizure.core.format.Ed25519Verifier
import io.github.duwenji.michizure.core.format.ID_PATTERN
import io.github.duwenji.michizure.core.format.PackageArchive
import io.github.duwenji.michizure.core.format.Reject
import io.github.duwenji.michizure.core.format.RejectCode
import io.github.duwenji.michizure.core.format.SignedKind
import io.github.duwenji.michizure.core.format.StationFiles
import io.github.duwenji.michizure.core.format.checkLength
import io.github.duwenji.michizure.core.format.int
import io.github.duwenji.michizure.core.format.obj
import io.github.duwenji.michizure.core.format.readJson
import io.github.duwenji.michizure.core.format.reject
import io.github.duwenji.michizure.core.format.required
import io.github.duwenji.michizure.core.format.string
import io.github.duwenji.michizure.core.format.verifyPackageSignature
import org.json.JSONArray

/** Which publishers may use a collection (API-004 `grants`, ADR-002 D-9). Null [publishers] means anyone. */
data class Grants(val publishers: Set<String>?) {
    fun allows(publisher: String): Boolean = publishers == null || publisher in publishers

    companion object {
        val EVERYONE = Grants(null)
    }
}

/** `collection.json` of a third-party collection (API-004「素材集の形式」). */
data class CollectionManifest(
    val format: Int,
    val id: String,
    val version: Int,
    val producer: String,
    val name: String,
    val summary: String,
    val lang: String,
    val grants: Grants,
)

/** A collection that passed every check: its settings and its resources. */
data class CollectionPackage(val manifest: CollectionManifest, val resources: Resources)

/**
 * What the approved list says about a collection (API-002 1.2 `collections[]`); the package must
 * agree. A version signed before the producer moved to a new key names a former id
 * ([formerProducers], from `producerChange`), and is still the listed version (API-004 1.0 D-17).
 */
data class CollectionListedAs(val id: String, val version: Int, val producer: String, val formerProducers: Set<String> = emptySet())

sealed interface CollectionCheck {
    data class Ok(val collection: CollectionPackage) : CollectionCheck
    /** The codes are API-003's ([RejectCode]); API-004 groups them as `collection_invalid`. */
    data class Rejected(val code: RejectCode, val detail: String) : CollectionCheck
}

/**
 * The checks of a collection package (API-004 確かめる手順 1・2), shared by the app and the
 * review tools: the ZIP, the files, the producer's signature, `collection.json` and the resources.
 * Its size and SHA-256 against the list are the caller's, as for channels.
 */
object CollectionValidator {
    const val FORMAT = 1
    private const val MANIFEST = "collection.json"
    private const val SIGNATURE = "signature.json"

    fun check(files: StationFiles, listedAs: CollectionListedAs?, verifier: Ed25519Verifier): CollectionCheck = try {
        CollectionCheck.Ok(validate(files, listedAs, verifier))
    } catch (e: Reject) {
        CollectionCheck.Rejected(e.code, e.message.orEmpty())
    }

    fun checkArchive(zip: ByteArray, listedAs: CollectionListedAs?, verifier: Ed25519Verifier): CollectionCheck {
        val files = try {
            PackageArchive.open(zip)
        } catch (e: ArchiveException) {
            return CollectionCheck.Rejected(RejectCode.BAD_ARCHIVE, e.message.orEmpty())
        }
        return check(files, listedAs, verifier)
    }

    private fun validate(files: StationFiles, listedAs: CollectionListedAs?, verifier: Ed25519Verifier): CollectionPackage {
        val paths = files.list()
        val json = readJson(files, MANIFEST, RejectCode.BAD_MANIFEST)
        paths.firstOrNull { it != MANIFEST && it != SIGNATURE && !it.startsWith(ResourceReader.RESOURCES_DIR) }
            ?.let { reject(RejectCode.UNEXPECTED_FILE, it) }
        if (SIGNATURE !in paths) reject(RejectCode.BAD_SIGNATURE, "no $SIGNATURE")
        val signed = verifyPackageSignature(files, paths, verifier, SignedKind.COLLECTION)

        val manifest = readManifest(json)
        if (signed.subject != manifest.id || signed.version != manifest.version) {
            reject(RejectCode.BAD_SIGNATURE, "signed for ${signed.subject}@${signed.version}, not ${manifest.id}@${manifest.version}")
        }
        // The key that signed must be the producer the package and the list name (as API-003 F-6).
        if (signed.accountId != signed.owner || signed.accountId != manifest.producer) {
            reject(RejectCode.PUBLISHER_MISMATCH, "signed by ${signed.accountId}, package says ${manifest.producer}")
        }
        if (listedAs != null) {
            if (manifest.id != listedAs.id || manifest.version != listedAs.version) {
                reject(RejectCode.BAD_MANIFEST, "${manifest.id}@${manifest.version} is listed as ${listedAs.id}@${listedAs.version}")
            }
            if (manifest.producer != listedAs.producer && manifest.producer !in listedAs.formerProducers) {
                reject(RejectCode.PUBLISHER_MISMATCH, "producer ${manifest.producer}")
            }
        }

        val resources = ResourceReader.read(json, files, inCollection = true)
        if (resources.items.isEmpty()) reject(RejectCode.BAD_RESOURCE, "a collection has no resources")
        val pointed = ResourceReader.files(resources)
        paths.firstOrNull { it.startsWith(ResourceReader.RESOURCES_DIR) && it !in pointed }?.let { reject(RejectCode.UNEXPECTED_FILE, it) }
        return CollectionPackage(manifest, resources)
    }

    private fun readManifest(json: org.json.JSONObject): CollectionManifest {
        val format = json.int("format")
        if (format != FORMAT) reject(RejectCode.UNSUPPORTED_FORMAT, "format $format")
        val id = json.string("id")
        if (!ID_PATTERN.matches(id)) reject(RejectCode.BAD_VALUE, "id \"$id\"")
        val version = json.int("version")
        if (version < 1) reject(RejectCode.BAD_VALUE, "version $version")
        val producer = json.string("producer")
        if (!ACCOUNT_ID_PATTERN.matches(producer)) reject(RejectCode.BAD_MANIFEST, "producer \"$producer\"")
        val lang = json.string("lang")
        if (lang != "ja") reject(RejectCode.BAD_VALUE, "lang \"$lang\"")
        return CollectionManifest(
            format, id, version, producer,
            name = json.string("name").also { checkLength("name", it, 1, 20) },
            summary = json.string("summary").also { checkLength("summary", it, 1, 60) },
            lang = lang,
            grants = readGrants(json.obj("grants")),
        )
    }

    /** `"*"` for anyone, or 1–50 publishers' account ids. */
    private fun readGrants(g: org.json.JSONObject): Grants = when (val publishers = g.required("publishers")) {
        "*" -> Grants.EVERYONE
        is JSONArray -> {
            val ids = (0 until publishers.length()).map { publishers.opt(it) as? String ?: reject(RejectCode.BAD_MANIFEST, "grants.publishers has a non-string item") }
            if (ids.size !in 1..50) reject(RejectCode.BAD_VALUE, "grants.publishers has ${ids.size} ids (1..50)")
            ids.firstOrNull { !ACCOUNT_ID_PATTERN.matches(it) }?.let { reject(RejectCode.BAD_VALUE, "grants.publishers \"$it\"") }
            Grants(ids.toSet())
        }
        else -> reject(RejectCode.BAD_MANIFEST, "grants.publishers is neither \"*\" nor a list")
    }
}
