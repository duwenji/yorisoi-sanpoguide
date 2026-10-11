package io.github.duwenji.sanpoguide.material.format

import io.github.duwenji.sanpoguide.station.format.HEADING_PATTERN
import io.github.duwenji.sanpoguide.station.format.RejectCode
import io.github.duwenji.sanpoguide.station.format.StationFiles
import io.github.duwenji.sanpoguide.station.format.URL_PATTERN
import io.github.duwenji.sanpoguide.station.format.charCount
import io.github.duwenji.sanpoguide.station.format.checkLength
import io.github.duwenji.sanpoguide.station.format.decodeUtf8
import io.github.duwenji.sanpoguide.station.format.double
import io.github.duwenji.sanpoguide.station.format.int
import io.github.duwenji.sanpoguide.station.format.optInt
import io.github.duwenji.sanpoguide.station.format.optList
import io.github.duwenji.sanpoguide.station.format.optNullable
import io.github.duwenji.sanpoguide.station.format.optString
import io.github.duwenji.sanpoguide.station.format.reject
import io.github.duwenji.sanpoguide.station.format.string
import io.github.duwenji.sanpoguide.station.format.strings
import org.json.JSONObject

/** Who made a resource and under what license (API-003 F-11). */
data class ResourceCredit(val author: String, val license: String, val source: String?)

/** Where a resource's bytes are: in the package, or outside it with a hash (API-003 F-9). */
sealed interface ResourceLocation {
    data class File(val path: String) : ResourceLocation
    data class External(val url: String, val sha256: String, val size: Long, val mime: String) : ResourceLocation
}

/** What a resource is about (API-004 `about`), for choosing by subject. */
sealed interface About {
    /** [osm] is `node/…`, `way/…` or `relation/…`; with [name], a spot of the same name within 200m matches too. */
    data class Spot(val osm: String, val name: String?) : About
    data class Point(val lat: Double, val lon: Double, val radiusM: Int) : About
}

/** One resource (API-003 `resources[]`; in a collection also [about] and [tags], API-004). */
data class Resource(
    val id: String,
    val type: MaterialType,
    val location: ResourceLocation,
    val durationSec: Int?,
    val alt: String?,
    /** The id of the text resource that transcribes this audio or video. */
    val transcript: String?,
    /** The id of the image shown before a video plays. */
    val poster: String?,
    val credit: ResourceCredit?,
    val about: About?,
    /** As written; tags this version doesn't know are kept and simply never match (API-004「タグ」). */
    val tags: List<String>,
)

/** The tags of API-004「タグ」. */
object Tags {
    val KNOWN = setOf("history", "faith", "architecture", "nature", "landscape", "season", "festival", "food", "craft", "art", "folklore")
}

/** The resources of a package, with the text of those inside it. */
data class Resources(val items: List<Resource>, val texts: Map<String, String>) {
    operator fun get(id: String): Resource? = items.firstOrNull { it.id == id }

    /** Bytes fetched ahead when the channel is chosen (API-003: all outside resources but video). */
    val prefetchBytes: Long get() = items.filter { it.type != MaterialType.VIDEO }.sumOf { (it.location as? ResourceLocation.External)?.size ?: 0L }

    val externalBytes: Long get() = items.sumOf { (it.location as? ResourceLocation.External)?.size ?: 0L }

    companion object {
        val NONE = Resources(emptyList(), emptyMap())
    }
}

/**
 * Reads and checks `resources` (API-003「素材（resources）」, 確認の手順 8). Every failure is
 * `bad_resource`. [inCollection] allows `about` and `tags` (API-004), which a channel's own
 * resources don't use (ignored there, like any unknown field).
 */
object ResourceReader {
    const val MAX_RESOURCES = 500
    const val MAX_TEXT_CHARS = 2_000
    const val MAX_IMAGE_IN_PACKAGE = 300 * 1024
    const val MAX_IMAGE_OUTSIDE = 1024 * 1024
    const val MAX_AUDIO = 10L * 1024 * 1024
    const val MAX_AUDIO_SEC = 600
    const val MAX_VIDEO = 50L * 1024 * 1024
    const val MAX_VIDEO_SEC = 300
    const val MAX_IMAGE_SIDE = 1_600
    const val MAX_EXTERNAL_TOTAL = 200L * 1024 * 1024
    const val MAX_PREFETCH_TOTAL = 50L * 1024 * 1024
    const val RESOURCES_DIR = "resources/"

    private val RESOURCE_ID = Regex("[a-z0-9-]{1,40}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val OSM = Regex("(node|way|relation)/[1-9][0-9]*")
    private val MIMES = mapOf(
        MaterialType.IMAGE to setOf("image/jpeg", "image/png", "image/webp"),
        MaterialType.AUDIO to setOf("audio/ogg", "audio/mp4", "audio/aac"),
        MaterialType.VIDEO to setOf("video/mp4"),
    )
    private val CODE = RejectCode.BAD_RESOURCE

    fun read(json: JSONObject, files: StationFiles, inCollection: Boolean): Resources {
        val list = json.optList("resources", CODE)
        if (list.size > MAX_RESOURCES) reject(CODE, "${list.size} resources (max $MAX_RESOURCES)")
        val items = list.map { item ->
            val r = item as? JSONObject ?: reject(CODE, "resources has an item that is not an object")
            readOne(r, inCollection)
        }
        items.groupBy { it.id }.values.firstOrNull { it.size > 1 }?.let { reject(CODE, "resource id \"${it[0].id}\" twice") }
        val byId = items.associateBy { it.id }
        val texts = LinkedHashMap<String, String>()
        for (r in items) {
            r.transcript?.let { if (byId[it]?.type != MaterialType.TEXT) reject(CODE, "${r.id}: transcript \"$it\" is not a text resource") }
            r.poster?.let { if (byId[it]?.type != MaterialType.IMAGE) reject(CODE, "${r.id}: poster \"$it\" is not an image resource") }
            val file = r.location as? ResourceLocation.File ?: continue
            val bytes = files.read(file.path) ?: reject(CODE, "${r.id}: no file ${file.path}")
            when (r.type) {
                MaterialType.TEXT -> texts[r.id] = checkText(r.id, file.path, bytes)
                MaterialType.IMAGE -> checkImage(r.id, file.path, bytes)
                else -> reject(CODE, "${r.id}: only text and image can be in the package")
            }
        }
        val resources = Resources(items, texts)
        if (resources.externalBytes > MAX_EXTERNAL_TOTAL) reject(CODE, "outside resources total ${resources.externalBytes} bytes (max $MAX_EXTERNAL_TOTAL)")
        if (resources.prefetchBytes > MAX_PREFETCH_TOTAL) reject(CODE, "outside resources but video total ${resources.prefetchBytes} bytes (max $MAX_PREFETCH_TOTAL)")
        return resources
    }

    /** Paths under `resources/` that some resource points at; any other file there is unexpected (API-003). */
    fun files(resources: Resources): Set<String> =
        resources.items.mapNotNull { (it.location as? ResourceLocation.File)?.path }.toSet()

    private fun readOne(r: JSONObject, inCollection: Boolean): Resource {
        val id = r.string("id", CODE)
        if (!RESOURCE_ID.matches(id)) reject(CODE, "resource id \"$id\"")
        val typeName = r.string("type", CODE)
        val type = MaterialType.entries.firstOrNull { it.json == typeName } ?: reject(CODE, "$id: type \"$typeName\"")
        val file = r.optString("file", CODE)
        val url = r.optString("url", CODE)
        val location = when {
            file != null && url != null -> reject(CODE, "$id: both file and url")
            file != null -> readFile(id, type, file)
            url != null -> readExternal(id, type, url, r)
            else -> reject(CODE, "$id: neither file nor url")
        }
        val duration = r.optInt("durationSec", CODE)
        when (type) {
            MaterialType.AUDIO, MaterialType.VIDEO -> {
                val max = if (type == MaterialType.AUDIO) MAX_AUDIO_SEC else MAX_VIDEO_SEC
                if (duration == null || duration < 1 || duration > max) reject(CODE, "$id: durationSec $duration (1..$max)")
            }
            else -> if (duration != null) reject(CODE, "$id: durationSec is for audio and video")
        }
        val alt = r.optString("alt", CODE)
        if (type == MaterialType.IMAGE) checkLength("$id alt", alt ?: reject(CODE, "$id: an image needs alt"), 1, 120, CODE)
        val transcript = r.optString("transcript", CODE)
        if ((type == MaterialType.AUDIO || type == MaterialType.VIDEO) && transcript == null) reject(CODE, "$id: ${type.json} needs a transcript")
        val poster = r.optString("poster", CODE)
        if (poster != null && type != MaterialType.VIDEO) reject(CODE, "$id: poster is for video")
        val credit = r.optNullable("credit")?.let { readCredit(id, it as? JSONObject ?: reject(CODE, "$id: credit is not an object")) }
        if (type != MaterialType.TEXT && credit == null) reject(CODE, "$id: ${type.json} needs a credit")
        return Resource(
            id, type, location, duration, alt, transcript, poster, credit,
            about = if (inCollection) r.optNullable("about")?.let { readAbout(id, it as? JSONObject ?: reject(CODE, "$id: about is not an object")) } else null,
            tags = if (inCollection) readTags(id, r) else emptyList(),
        )
    }

    private fun readFile(id: String, type: MaterialType, path: String): ResourceLocation.File {
        val ok = when (type) {
            MaterialType.TEXT -> path.startsWith("${RESOURCES_DIR}text/") && path.endsWith(".md")
            MaterialType.IMAGE -> path.startsWith("${RESOURCES_DIR}img/") && IMAGE_EXT.any { path.endsWith(it) }
            else -> reject(CODE, "$id: ${type.json} can't be in the package")
        }
        if (!ok || path.count { it == '/' } != 2) reject(CODE, "$id: file \"$path\"")
        return ResourceLocation.File(path)
    }

    private fun readExternal(id: String, type: MaterialType, url: String, r: JSONObject): ResourceLocation.External {
        if (type == MaterialType.TEXT) reject(CODE, "$id: text must be in the package")
        if (!url.startsWith("https://")) reject(CODE, "$id: url is not HTTPS")
        val sha = r.string("sha256", CODE)
        if (!SHA256.matches(sha)) reject(CODE, "$id: sha256 \"$sha\"")
        val size = (r.optNullable("size") as? Number)?.toLong() ?: reject(CODE, "$id: no size")
        val max = when (type) {
            MaterialType.IMAGE -> MAX_IMAGE_OUTSIDE.toLong()
            MaterialType.AUDIO -> MAX_AUDIO
            else -> MAX_VIDEO
        }
        if (size < 1 || size > max) reject(CODE, "$id: size $size (1..$max)")
        val mime = r.string("mime", CODE)
        if (mime !in MIMES.getValue(type)) reject(CODE, "$id: mime \"$mime\"")
        return ResourceLocation.External(url, sha, size, mime)
    }

    private fun readCredit(id: String, c: JSONObject): ResourceCredit {
        val author = c.string("author", CODE).also { checkLength("$id credit.author", it, 1, 80, CODE) }
        val license = c.string("license", CODE).also { checkLength("$id credit.license", it, 1, 40, CODE) }
        val source = c.optString("source", CODE)
        if (source != null && !source.startsWith("https://")) reject(CODE, "$id: credit.source is not HTTPS")
        return ResourceCredit(author, license, source)
    }

    private fun readAbout(id: String, a: JSONObject): About = when (val type = a.string("type", CODE)) {
        "spot" -> {
            val osm = a.string("osm", CODE)
            if (!OSM.matches(osm)) reject(CODE, "$id: about.osm \"$osm\"")
            About.Spot(osm, a.optString("name", CODE)?.also { checkLength("$id about.name", it, 1, 60, CODE) })
        }
        "point" -> {
            val lat = a.double("lat", CODE)
            val lon = a.double("lon", CODE)
            val radius = a.int("radiusM", CODE)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) reject(CODE, "$id: about is not on the earth")
            if (radius !in 20..300) reject(CODE, "$id: about.radiusM $radius (20..300)")
            About.Point(lat, lon, radius)
        }
        else -> reject(CODE, "$id: about.type \"$type\"")
    }

    private fun readTags(id: String, r: JSONObject): List<String> {
        val tags = r.optList("tags", CODE).strings("$id tags", CODE)
        if (tags.size > 3) reject(CODE, "$id: ${tags.size} tags (max 3)")
        if (tags.toSet().size != tags.size) reject(CODE, "$id: tags has duplicates")
        tags.firstOrNull { !TAG.matches(it) }?.let { reject(CODE, "$id: tag \"$it\"") }
        return tags
    }

    /** Plain text only: no headings, links, images or URLs, read out or shown as is (API-003). */
    private fun checkText(id: String, path: String, bytes: ByteArray): String {
        val text = decodeUtf8(bytes, path, CODE)
        if (text.startsWith('﻿') || '\r' in text) reject(CODE, "$id: $path must be UTF-8 without BOM, with LF")
        if (HEADING_PATTERN.containsMatchIn(text) || MARKUP.containsMatchIn(text)) reject(CODE, "$id: $path has Markdown headings, links or images")
        URL_PATTERN.find(text)?.let { reject(CODE, "$id: $path has a URL \"${it.value}\"") }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) reject(CODE, "$id: $path is empty")
        if (trimmed.charCount() > MAX_TEXT_CHARS) reject(CODE, "$id: $path has ${trimmed.charCount()} chars (max $MAX_TEXT_CHARS)")
        return trimmed
    }

    private fun checkImage(id: String, path: String, bytes: ByteArray) {
        if (bytes.size > MAX_IMAGE_IN_PACKAGE) reject(CODE, "$id: $path is ${bytes.size} bytes (max $MAX_IMAGE_IN_PACKAGE)")
        val size = ImageSize.of(bytes) ?: reject(CODE, "$id: $path is not a JPEG, PNG or WebP image")
        if (maxOf(size.first, size.second) > MAX_IMAGE_SIDE) reject(CODE, "$id: $path is ${size.first}x${size.second} (longest side max $MAX_IMAGE_SIDE)")
    }

    private val IMAGE_EXT = listOf(".jpg", ".png", ".webp")
    private val TAG = Regex("[a-z][a-z0-9-]{0,29}")
    private val MARKUP = Regex("!?\\[[^\\]]*]\\(")
}

/** An image's width and height from its header, for JPEG, PNG and WebP; null for anything else. */
internal object ImageSize {
    fun of(b: ByteArray): Pair<Int, Int>? = png(b) ?: jpeg(b) ?: webp(b)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (be16(b, i) shl 16) or be16(b, i + 2)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + 2) shl 16)
    private fun ascii(b: ByteArray, i: Int, s: String) = b.size >= i + s.length && s.indices.all { b[i + it].toInt() == s[it].code }

    private fun png(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || u8(b, 0) != 0x89 || !ascii(b, 1, "PNG") || !ascii(b, 12, "IHDR")) return null
        return be32(b, 16) to be32(b, 20)
    }

    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        while (i + 9 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            if (marker == 0xFF) { i++; continue }
            val length = be16(b, i + 2)
            // Start-of-frame markers carry the size; C4, C8 and CC are other tables.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return be16(b, i + 7) to be16(b, i + 5)
            i += 2 + length
        }
        return null
    }

    private fun webp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WEBP")) return null
        return when {
            ascii(b, 12, "VP8 ") -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
            ascii(b, 12, "VP8L") -> {
                val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            ascii(b, 12, "VP8X") -> (le24(b, 24) + 1) to (le24(b, 27) + 1)
            else -> null
        }
    }
}
