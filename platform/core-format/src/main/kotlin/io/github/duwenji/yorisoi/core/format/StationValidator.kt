package io.github.duwenji.yorisoi.core.format

import io.github.duwenji.yorisoi.core.format.material.ResourceReader
import org.json.JSONException
import org.json.JSONObject

/** A channel that passed every check: its settings, the text of the slots it fills, and its material. */
data class StationPackage(val manifest: StationManifest, val slots: Map<Slot, String>, val media: StationMedia = StationMedia.NONE)

/** Why a package was turned away; the codes are the ones in API-003's error table. */
enum class RejectCode(val json: String) {
    BAD_ARCHIVE("bad_archive"),
    UNEXPECTED_FILE("unexpected_file"),
    BAD_SIGNATURE("bad_signature"),
    PUBLISHER_MISMATCH("publisher_mismatch"),
    UNSUPPORTED_FORMAT("unsupported_format"),
    BAD_MANIFEST("bad_manifest"),
    BAD_VALUE("bad_value"),
    SLOT_TOO_LONG("slot_too_long"),
    FORBIDDEN_TEXT("forbidden_text"),
    RENDER_FAILED("render_failed"),
    BAD_RESOURCE("bad_resource"),
    BAD_CUE("bad_cue"),
    UNSUPPORTED_RESOURCE("unsupported_resource"),
    RESOURCE_MISMATCH("resource_mismatch"),
    /** API-003 1.2 / API-004: a reference that names nothing (an undeclared alias, an unknown producer, a missing resource). */
    UNRESOLVED_REFERENCE("unresolved_reference"),
    /** API-003 1.2: a collection's grants leave out this channel's publisher. */
    NOT_GRANTED("not_granted"),
    /** API-003 1.2: a declared collection isn't on the provider's list, or is older than `minVersion`. */
    COLLECTION_NOT_LISTED("collection_not_listed"),
}

sealed interface StationCheck {
    data class Ok(val station: StationPackage) : StationCheck
    data class Rejected(val code: RejectCode, val detail: String) : StationCheck
}

enum class StationOrigin { BUILT_IN, THIRD_PARTY }

/**
 * What the approved channel list says about a package; the package must agree. [activities],
 * [genres] and [requires] are the list's values as written (API-002 1.4 P-19); null skips the check
 * (an older list, or a caller that doesn't have them).
 */
data class ListedAs(
    val id: String,
    val version: Int,
    val publisher: String,
    val activities: List<String>? = null,
    val genres: List<String>? = null,
    val requires: List<String>? = null,
)

/**
 * The checks of API-003 ("確認の手順"), shared by the app and the review tools.
 *
 * Covers unpacking the ZIP ([checkArchive]), the files, the publisher's signature, `channel.json`,
 * the prompt slots, and the material (resources, collections, selectors and cues; 手順 8・9 and the
 * part of 9a inside the package). Not here: the package's size and SHA-256 against the list (the
 * caller has the list), rendering the prompts ([io.github.duwenji.sanpoguide.prompt.StationPrompts]),
 * and checking the collections a channel uses against the list (API-004 手順 3・4).
 *
 * `mediaStage` is the app stage the caller can show (API-003 M-1, [MediaStage]); a channel needing
 * more is turned away as [RejectCode.UNSUPPORTED_RESOURCE]. At 0 (the app today) any material at all
 * is turned away without reading it. The review tools pass [ALL_STAGES] to check everything.
 */
object StationValidator {
    /** The package formats this core-format reads (API-003 1.x and 2.0). */
    val FORMATS = 1..2
    const val LATEST_FORMAT = 2
    const val MAX_FILES = 100
    const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
    /** Every stage of API-003 M-1; what the review tools check with. */
    const val ALL_STAGES = 3
    private const val MANIFEST = "channel.json"
    private const val SIGNATURE = "signature.json"
    private const val PROMPTS_DIR = "prompts/"
    private val MEDIA_FIELDS = listOf("resources", "collections", "selectors", "cues")

    /**
     * Checks a channel's files. A third-party package also needs [verifier] for its publisher's
     * signature; built-in channels have none.
     */
    fun check(
        files: StationFiles, origin: StationOrigin, listedAs: ListedAs? = null, verifier: Ed25519Verifier? = null, mediaStage: Int = 0,
        strictness: Strictness = Strictness.REVIEW,
    ): StationCheck = try {
        StationCheck.Ok(validate(files, origin, listedAs, verifier, mediaStage, strictness))
    } catch (e: Reject) {
        StationCheck.Rejected(e.code, e.message.orEmpty())
    }

    /** Unpacks a third-party package (API-003 確認の手順 2) and checks it like [check]. */
    fun checkArchive(
        zip: ByteArray, listedAs: ListedAs? = null, verifier: Ed25519Verifier, mediaStage: Int = 0, strictness: Strictness = Strictness.REVIEW,
    ): StationCheck {
        val files = try {
            PackageArchive.open(zip)
        } catch (e: ArchiveException) {
            return StationCheck.Rejected(RejectCode.BAD_ARCHIVE, e.message.orEmpty())
        }
        return check(files, StationOrigin.THIRD_PARTY, listedAs, verifier, mediaStage, strictness)
    }

    private fun validate(
        files: StationFiles, origin: StationOrigin, listedAs: ListedAs?, verifier: Ed25519Verifier?, mediaStage: Int, strictness: Strictness,
    ): StationPackage {
        val paths = files.list()
        if (paths.size > MAX_FILES) reject(RejectCode.BAD_ARCHIVE, "${paths.size} files (max $MAX_FILES)")
        val totalBytes = paths.sumOf { files.read(it)?.size ?: 0 }
        if (totalBytes > MAX_TOTAL_BYTES) reject(RejectCode.BAD_ARCHIVE, "$totalBytes bytes (max $MAX_TOTAL_BYTES)")

        val json = readJson(files, MANIFEST, RejectCode.BAD_MANIFEST)
        val format = json.int("format")
        if (format !in FORMATS) reject(RejectCode.UNSUPPORTED_FORMAT, "format $format")
        // API-003 G-3: an app reads a format 2 package expecting values newer than it knows.
        val lenient = strictness == Strictness.APP && format >= 2
        val allowed = buildSet {
            add(MANIFEST)
            Slot.entries.forEach { add(it.path) }
            if (origin == StationOrigin.THIRD_PARTY) add(SIGNATURE)
        }
        // Files under resources/ are allowed when a resource points at them; checked with the material below.
        // A slot this app doesn't have is left unused (API-003 2.0); any other unknown file is not allowed.
        paths.firstOrNull { it !in allowed && !it.startsWith(ResourceReader.RESOURCES_DIR) && !(lenient && it.startsWith(PROMPTS_DIR)) }
            ?.let { reject(RejectCode.UNEXPECTED_FILE, it) }
        if (origin == StationOrigin.THIRD_PARTY && SIGNATURE !in paths) reject(RejectCode.BAD_SIGNATURE, "no $SIGNATURE")
        val signed = if (origin == StationOrigin.THIRD_PARTY) {
            requireNotNull(verifier) { "a third-party package needs a signature verifier" }
            verifyPackageSignature(files, paths, verifier, SignedKind.CHANNEL)
        } else {
            null
        }

        val manifest = readManifest(json, origin, format, lenient)
        if (signed != null) {
            if (signed.subject != manifest.id || signed.version != manifest.version) {
                reject(RejectCode.BAD_SIGNATURE, "signed for ${signed.subject}@${signed.version}, not ${manifest.id}@${manifest.version}")
            }
            // The key that signed must be the publisher the package and the list name (API-003 F-6).
            if (signed.accountId != signed.owner || signed.accountId != manifest.publisher) {
                reject(RejectCode.PUBLISHER_MISMATCH, "signed by ${signed.accountId}, package says ${manifest.publisher}")
            }
        }
        if (listedAs != null) {
            if (manifest.id != listedAs.id || manifest.version != listedAs.version) {
                reject(RejectCode.BAD_MANIFEST, "${manifest.id}@${manifest.version} is listed as ${listedAs.id}@${listedAs.version}")
            }
            if (manifest.publisher != listedAs.publisher) reject(RejectCode.PUBLISHER_MISMATCH, "publisher ${manifest.publisher}")
            // API-002 P-19: the list's values are copied from the package, as written.
            val written = writtenEcosystemValues(json, format)
            listOf("activities" to listedAs.activities, "genres" to listedAs.genres, "requires" to listedAs.requires).forEach { (field, listed) ->
                if (listed != null && listed.toSet() != written.getValue(field).toSet()) {
                    reject(RejectCode.BAD_MANIFEST, "$field ${written.getValue(field)} is listed as $listed")
                }
            }
        }
        val media = readMedia(json, files, paths, mediaStage, lenient)
        return StationPackage(manifest, readSlots(files, paths), media)
    }

    /** The material (手順 8・9・10). Without any stage, a channel that has some can't be shown and isn't read. */
    private fun readMedia(json: JSONObject, files: StationFiles, paths: List<String>, mediaStage: Int, lenient: Boolean): StationMedia {
        if (mediaStage <= 0) {
            MEDIA_FIELDS.firstOrNull { json.optList(it).isNotEmpty() }?.let { reject(RejectCode.UNSUPPORTED_RESOURCE, "$it are not supported yet") }
            paths.firstOrNull { it.startsWith(ResourceReader.RESOURCES_DIR) }?.let { reject(RejectCode.UNEXPECTED_FILE, it) }
            return StationMedia.NONE
        }
        val resources = ResourceReader.read(json, files, inCollection = false)
        val pointed = ResourceReader.files(resources)
        paths.firstOrNull { it.startsWith(ResourceReader.RESOURCES_DIR) && it !in pointed }?.let { reject(RejectCode.UNEXPECTED_FILE, it) }
        val media = CueReader.read(json, resources, lenient)
        // Collections are known only from the list, so what they bring is counted when they are (API-004 手順 3・4).
        val needed = MediaStage.required(media)
        if (needed > mediaStage) reject(RejectCode.UNSUPPORTED_RESOURCE, "needs app stage $needed (this one shows up to $mediaStage)")
        return media
    }

    private fun readManifest(json: JSONObject, origin: StationOrigin, format: Int, lenient: Boolean): StationManifest {
        // API-003 2.0「アプリでの知らない値」: restricting values the app doesn't know are noted, and the
        // channel isn't used (Compatibility); other unknown values are left out. Without leniency they're rejected.
        val unknownRestrictions = mutableListOf<String>()
        fun unknown(field: String, value: String, restricts: Boolean): Nothing? {
            if (!lenient) reject(RejectCode.BAD_VALUE, "$field \"$value\"")
            if (restricts) unknownRestrictions += "$field \"$value\""
            return null
        }
        val id = json.string("id")
        if (!ID_PATTERN.matches(id)) reject(RejectCode.BAD_VALUE, "id \"$id\"")
        val version = json.int("version")
        if (version < 1) reject(RejectCode.BAD_VALUE, "version $version")
        val publisher = json.optNullable("publisher")?.let { it as? String ?: reject(RejectCode.BAD_MANIFEST, "publisher is not a string") }
        when (origin) {
            StationOrigin.BUILT_IN -> if (publisher != null) reject(RejectCode.BAD_MANIFEST, "a built-in channel has no publisher")
            StationOrigin.THIRD_PARTY -> if (publisher == null || !ACCOUNT_ID_PATTERN.matches(publisher)) {
                reject(RejectCode.BAD_MANIFEST, "publisher \"$publisher\"")
            }
        }
        val lang = json.string("lang")
        if (lang != "ja") reject(RejectCode.BAD_VALUE, "lang \"$lang\"")

        val talk = json.obj("talk")
        val events = talk.obj("events")
        val spots = json.obj("spots")
        val prefer = spots.kinds("prefer") { unknown("spots.prefer", it, restricts = false) }
        val skip = spots.kinds("skip") { unknown("spots.skip", it, restricts = true) }
        if (prefer.size > 8) reject(RejectCode.BAD_VALUE, "spots.prefer has ${prefer.size} kinds (max 8)")
        (prefer intersect skip.toSet()).firstOrNull()?.let { reject(RejectCode.BAD_VALUE, "${it.json} is both preferred and skipped") }
        val mood = json.obj("mood")
        val activities: Set<Activity>
        val genres: Set<Genre>
        val requires: Set<Feature>
        if (format >= 2) {
            val written = json.list("activities").strings("activities", RejectCode.BAD_MANIFEST)
            if (written.size !in 1..4) reject(RejectCode.BAD_VALUE, "activities has ${written.size} values (1..4)")
            activities = written.distinctOrReject("activities").mapNotNull { parseEnum<Activity>(it) ?: unknown("activities", it, restricts = false) }.toSet()
            val g = json.optList("genres").strings("genres", RejectCode.BAD_MANIFEST)
            if (g.size > 3) reject(RejectCode.BAD_VALUE, "genres has ${g.size} values (max 3)")
            genres = g.distinctOrReject("genres").mapNotNull { parseEnum<Genre>(it) ?: unknown("genres", it, restricts = false) }.toSet()
            val f = (json.optNullable("requires")?.let { json.obj("requires").optList("features") } ?: emptyList()).strings("requires.features", RejectCode.BAD_MANIFEST)
            if (f.size > 20) reject(RejectCode.BAD_VALUE, "requires.features has ${f.size} values (max 20)")
            requires = f.distinctOrReject("requires.features").mapNotNull { parseEnum<Feature>(it) ?: unknown("requires.features", it, restricts = true) }.toSet()
        } else {
            activities = setOf(Activity.WALK)
            genres = emptySet()
            requires = emptySet()
        }
        val talkLevel = talk.string("level").let { parseEnum<TalkLevel>(it) ?: unknown("talk.level", it, restricts = true) ?: TalkLevel.QUIET }
        val guideLength = json.obj("guide").string("length").let { parseEnum<GuideLength>(it) ?: unknown("guide.length", it, restricts = true) ?: GuideLength.SHORT }
        val sound = mood.string("sound").let { parseEnum<SoundChoice>(it) ?: unknown("mood.sound", it, restricts = false) ?: SoundChoice.AUTO }

        return StationManifest(
            format = format,
            id = id,
            version = version,
            publisher = publisher,
            name = json.string("name").also { checkLength("name", it, 1, 20) },
            summary = json.string("summary").also { checkLength("summary", it, 1, 60) },
            lang = lang,
            greeting = json.string("greeting").also { checkLength("greeting", it, 1, 60) },
            talkLevel = talkLevel,
            events = TalkEventKind.entries.filter { events.bool(it.json) }.toSet(),
            prefer = prefer,
            skip = skip,
            guideLength = guideLength,
            moodTone = mood.bool("tone"),
            sound = sound,
            activities = activities,
            genres = genres,
            requires = requires,
            unknownRestrictions = unknownRestrictions,
        )
    }

    private fun readSlots(files: StationFiles, paths: List<String>): Map<Slot, String> {
        val slots = Slot.entries.filter { it.path in paths }.associateWith { slot ->
            val text = decodeUtf8(files.read(slot.path)!!, slot.path, RejectCode.FORBIDDEN_TEXT)
            if (text.startsWith('﻿')) reject(RejectCode.FORBIDDEN_TEXT, "${slot.path}: byte order mark")
            if ('\r' in text) reject(RejectCode.FORBIDDEN_TEXT, "${slot.path}: CR line breaks")
            if ("{{" in text) reject(RejectCode.FORBIDDEN_TEXT, "${slot.path}: \"{{\"")
            URL_PATTERN.find(text)?.let { reject(RejectCode.FORBIDDEN_TEXT, "${slot.path}: URL \"${it.value}\"") }
            if (HEADING_PATTERN.containsMatchIn(text)) reject(RejectCode.FORBIDDEN_TEXT, "${slot.path}: heading")
            val trimmed = text.trim()
            if (trimmed.isEmpty()) reject(RejectCode.BAD_VALUE, "${slot.path} is empty")
            val chars = trimmed.charCount()
            if (chars > slot.maxChars) reject(RejectCode.SLOT_TOO_LONG, "${slot.path}: $chars chars (max ${slot.maxChars})")
            trimmed
        }
        val total = slots.values.sumOf { it.charCount() }
        if (total > Slot.MAX_TOTAL_CHARS) reject(RejectCode.SLOT_TOO_LONG, "slots total $total chars (max ${Slot.MAX_TOTAL_CHARS})")
        return slots
    }

    private fun JSONObject.kinds(key: String, unknown: (String) -> Nothing?): List<SpotKind> {
        val written = list(key).strings(key, RejectCode.BAD_MANIFEST).distinctOrReject(key)
        return written.mapNotNull { value -> parseEnum<SpotKind>(value) ?: unknown(value) }
    }

    private fun List<String>.distinctOrReject(field: String): List<String> =
        also { if (toSet().size != size) reject(RejectCode.BAD_VALUE, "$field has duplicates") }

    /** `activities`, `genres` and `requires.features` as written; what format 1 means by them (API-003 G-6). */
    private fun writtenEcosystemValues(json: JSONObject, format: Int): Map<String, List<String>> = if (format >= 2) {
        mapOf(
            "activities" to json.list("activities").strings("activities", RejectCode.BAD_MANIFEST),
            "genres" to json.optList("genres").strings("genres", RejectCode.BAD_MANIFEST),
            "requires" to (json.optNullable("requires")?.let { json.obj("requires").optList("features") } ?: emptyList())
                .strings("requires.features", RejectCode.BAD_MANIFEST),
        )
    } else {
        mapOf("activities" to listOf(Activity.WALK.json), "genres" to emptyList(), "requires" to emptyList())
    }
}

/** Which kind of package a `signature.json` signs: its payload `type`, and the fields naming it and its owner. */
internal enum class SignedKind(val type: String, val subjectKey: String, val ownerKey: String) {
    CHANNEL("channel-package", "channel", "publisher"),
    COLLECTION("collection-package", "collection", "producer"),
}

/**
 * `signature.json` (API-003 F-6; for collections, API-004): the owner's Ed25519 signature over the
 * SHA-256 of every other file. Every file must be listed and match, and nothing else may be listed.
 */
internal fun verifyPackageSignature(files: StationFiles, paths: List<String>, verifier: Ed25519Verifier, kind: SignedKind): SignedFiles {
    val name = "signature.json"
    val doc = try {
        JSONObject(decodeUtf8(files.read(name)!!, name, RejectCode.BAD_SIGNATURE))
    } catch (e: JSONException) {
        reject(RejectCode.BAD_SIGNATURE, "$name: ${e.message}")
    }
    fun bytes(key: String): ByteArray =
        decodeBase64Url(doc.optString(key, "")) ?: reject(RejectCode.BAD_SIGNATURE, "$name: $key is not base64url")
    val payloadBytes = bytes("payload")
    val publicKey = bytes("publisherKey")
    if (publicKey.size != 32) reject(RejectCode.BAD_SIGNATURE, "publisherKey is not 32 bytes")
    if (!verifier.verify(publicKey, payloadBytes, bytes("sig"))) reject(RejectCode.BAD_SIGNATURE, "the signature does not verify")

    val payload = try {
        JSONObject(decodeUtf8(payloadBytes, "$name payload", RejectCode.BAD_SIGNATURE))
    } catch (e: JSONException) {
        reject(RejectCode.BAD_SIGNATURE, "payload: ${e.message}")
    }
    if (payload.optString("type") != kind.type) reject(RejectCode.BAD_SIGNATURE, "payload type ${payload.optString("type")}")
    val listed = payload.optJSONObject("files") ?: reject(RejectCode.BAD_SIGNATURE, "payload has no files")
    val signedPaths = listed.keys().asSequence().toSet() // keys(): Android's org.json has no keySet()
    val present = paths.filter { it != name }.toSet()
    (present - signedPaths).firstOrNull()?.let { reject(RejectCode.BAD_SIGNATURE, "$it is not signed") }
    (signedPaths - present).firstOrNull()?.let { reject(RejectCode.BAD_SIGNATURE, "$it is signed but missing") }
    for (path in present) {
        if (sha256(files.read(path)!!).hex() != listed.optString(path)) reject(RejectCode.BAD_SIGNATURE, "$path differs from what was signed")
    }
    val version = payload.opt("version")
    return SignedFiles(
        subject = payload.optString(kind.subjectKey),
        version = if (version is Int) version else reject(RejectCode.BAD_SIGNATURE, "payload version is not an integer"),
        owner = payload.optString(kind.ownerKey),
        accountId = AccountIds.of(publicKey),
    )
}
