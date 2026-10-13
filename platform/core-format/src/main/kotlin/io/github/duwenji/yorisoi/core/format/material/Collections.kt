package io.github.duwenji.yorisoi.core.format.material

import io.github.duwenji.yorisoi.core.format.ACCOUNT_ID_PATTERN
import io.github.duwenji.yorisoi.core.format.AppIds
import io.github.duwenji.yorisoi.core.format.ArchiveException
import io.github.duwenji.yorisoi.core.format.Ed25519Verifier
import io.github.duwenji.yorisoi.core.format.ID_PATTERN
import io.github.duwenji.yorisoi.core.format.PackageArchive
import io.github.duwenji.yorisoi.core.format.Reject
import io.github.duwenji.yorisoi.core.format.RejectCode
import io.github.duwenji.yorisoi.core.format.SignedKind
import io.github.duwenji.yorisoi.core.format.StationFiles
import io.github.duwenji.yorisoi.core.format.Strictness
import io.github.duwenji.yorisoi.core.format.checkLength
import io.github.duwenji.yorisoi.core.format.int
import io.github.duwenji.yorisoi.core.format.list
import io.github.duwenji.yorisoi.core.format.obj
import io.github.duwenji.yorisoi.core.format.optNullable
import io.github.duwenji.yorisoi.core.format.readJson
import io.github.duwenji.yorisoi.core.format.reject
import io.github.duwenji.yorisoi.core.format.required
import io.github.duwenji.yorisoi.core.format.string
import io.github.duwenji.yorisoi.core.format.strings
import io.github.duwenji.yorisoi.core.format.verifyPackageSignature
import org.json.JSONArray

/**
 * Which publishers may use a collection (API-004 `grants`, ADR-002 D-9), and in which apps (API-004
 * 1.5 I-11). Null means any.
 */
data class Grants(val publishers: Set<String>?, val apps: Set<String>? = null) {
    fun allows(publisher: String): Boolean = publishers == null || publisher in publishers

    /** Whether the app [appId] may use the collection at all. */
    fun allowsApp(appId: String): Boolean = apps == null || appId in apps

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

    fun check(
        files: StationFiles, listedAs: CollectionListedAs?, verifier: Ed25519Verifier, strictness: Strictness = Strictness.REVIEW,
    ): CollectionCheck = try {
        CollectionCheck.Ok(validate(files, listedAs, verifier, strictness))
    } catch (e: Reject) {
        CollectionCheck.Rejected(e.code, e.message.orEmpty())
    }

    fun checkArchive(
        zip: ByteArray, listedAs: CollectionListedAs?, verifier: Ed25519Verifier, strictness: Strictness = Strictness.REVIEW,
    ): CollectionCheck {
        val files = try {
            PackageArchive.open(zip)
        } catch (e: ArchiveException) {
            return CollectionCheck.Rejected(RejectCode.BAD_ARCHIVE, e.message.orEmpty())
        }
        return check(files, listedAs, verifier, strictness)
    }

    private fun validate(files: StationFiles, listedAs: CollectionListedAs?, verifier: Ed25519Verifier, strictness: Strictness): CollectionPackage {
        val paths = files.list()
        val json = readJson(files, MANIFEST, RejectCode.BAD_MANIFEST)
        paths.firstOrNull { it != MANIFEST && it != SIGNATURE && !it.startsWith(ResourceReader.RESOURCES_DIR) }
            ?.let { reject(RejectCode.UNEXPECTED_FILE, it) }
        if (SIGNATURE !in paths) reject(RejectCode.BAD_SIGNATURE, "no $SIGNATURE")
        val signed = verifyPackageSignature(files, paths, verifier, SignedKind.COLLECTION)

        val manifest = readManifest(json, strictness)
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

    private fun readManifest(json: org.json.JSONObject, strictness: Strictness): CollectionManifest {
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
            grants = readGrants(json.obj("grants"), strictness),
        )
    }

    /** `"*"` for anyone, or 1–50 publishers' account ids; and optionally 1–20 app ids (API-004 1.5 I-11). */
    private fun readGrants(g: org.json.JSONObject, strictness: Strictness): Grants {
        val publishers = when (val publishers = g.required("publishers")) {
            "*" -> null
            is JSONArray -> {
                val ids = (0 until publishers.length()).map { publishers.opt(it) as? String ?: reject(RejectCode.BAD_MANIFEST, "grants.publishers has a non-string item") }
                if (ids.size !in 1..50) reject(RejectCode.BAD_VALUE, "grants.publishers has ${ids.size} ids (1..50)")
                ids.firstOrNull { !ACCOUNT_ID_PATTERN.matches(it) }?.let { reject(RejectCode.BAD_VALUE, "grants.publishers \"$it\"") }
                ids.toSet()
            }
            else -> reject(RejectCode.BAD_MANIFEST, "grants.publishers is neither \"*\" nor a list")
        }
        val apps = g.optNullable("apps")?.let {
            val ids = g.list("apps").strings("grants.apps", RejectCode.BAD_MANIFEST)
            if (ids.size !in 1..20) reject(RejectCode.BAD_VALUE, "grants.apps has ${ids.size} ids (1..20)")
            // Review knows every app; an app needn't know the ones that came after it.
            if (strictness == Strictness.REVIEW) ids.firstOrNull { it !in AppIds.KNOWN }?.let { reject(RejectCode.BAD_VALUE, "grants.apps \"$it\"") }
            ids.toSet()
        }
        return Grants(publishers, apps)
    }
}
