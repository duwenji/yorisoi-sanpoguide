package io.github.duwenji.sanpoguide.station.format

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// The pieces every package check shares (channels, API-003; collections, API-004): turning a broken
// rule into a rejection, and reading JSON fields strictly.

/** A broken rule, caught at the top of a check and returned as its result. */
internal class Reject(val code: RejectCode, detail: String) : Exception(detail)

internal fun reject(code: RejectCode, detail: String): Nothing = throw Reject(code, detail)

internal val ID_PATTERN = Regex("[a-z0-9-]{3,40}")
internal val ACCOUNT_ID_PATTERN = Regex("sg1[a-z2-7]{32}")
internal val HEADING_PATTERN = Regex("(?m)^\\s*#")
internal val URL_PATTERN = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

/** Strict UTF-8, or a rejection with [code]. */
internal fun decodeUtf8(bytes: ByteArray, path: String, code: RejectCode): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: CharacterCodingException) {
    reject(code, "$path is not UTF-8")
}

/** A JSON object read from [path] of [files], or a rejection with [code]. */
internal fun readJson(files: StationFiles, path: String, code: RejectCode): JSONObject {
    val bytes = files.read(path) ?: reject(code, "no $path")
    return try {
        JSONObject(decodeUtf8(bytes, path, code))
    } catch (e: JSONException) {
        reject(code, "$path: ${e.message}")
    }
}

/** Characters as a reader counts them (one per code point, so emoji and rare kanji count once). */
internal fun String.charCount(): Int = codePointCount(0, length)

internal fun checkLength(field: String, value: String, min: Int, max: Int, code: RejectCode = RejectCode.BAD_VALUE) {
    val chars = value.charCount()
    if (chars < min || chars > max) reject(code, "$field has $chars chars ($min..$max)")
}

// Field readers. A missing or mistyped field is [code] (by default `bad_manifest`); a value outside
// what the format allows is `bad_value` or the caller's own code.

internal fun JSONObject.optNullable(key: String): Any? = opt(key)?.takeIf { it != JSONObject.NULL }

internal fun JSONObject.required(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): Any =
    optNullable(key) ?: reject(code, "no \"$key\"")

internal fun JSONObject.string(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): String =
    required(key, code) as? String ?: reject(code, "\"$key\" is not a string")

internal fun JSONObject.optString(key: String, code: RejectCode): String? =
    optNullable(key)?.let { it as? String ?: reject(code, "\"$key\" is not a string") }

internal fun JSONObject.int(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): Int =
    required(key, code) as? Int ?: reject(code, "\"$key\" is not an integer")

internal fun JSONObject.optInt(key: String, code: RejectCode): Int? =
    optNullable(key)?.let { it as? Int ?: reject(code, "\"$key\" is not an integer") }

/** A number that may have a fraction (coordinates). */
internal fun JSONObject.double(key: String, code: RejectCode): Double =
    (required(key, code) as? Number)?.toDouble() ?: reject(code, "\"$key\" is not a number")

internal fun JSONObject.bool(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): Boolean =
    required(key, code) as? Boolean ?: reject(code, "\"$key\" is not true or false")

internal fun JSONObject.obj(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): JSONObject =
    required(key, code) as? JSONObject ?: reject(code, "\"$key\" is not an object")

/** A list that may be left out (empty then). */
internal fun JSONObject.optList(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): List<Any> {
    val value = optNullable(key) ?: return emptyList()
    val array = value as? JSONArray ?: reject(code, "\"$key\" is not a list")
    return (0 until array.length()).map { array.opt(it) ?: JSONObject.NULL }
}

internal fun JSONObject.list(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): List<Any> {
    required(key, code) as? JSONArray ?: reject(code, "\"$key\" is not a list")
    return optList(key, code)
}

internal fun List<Any>.objects(field: String, code: RejectCode): List<JSONObject> =
    map { it as? JSONObject ?: reject(code, "$field has an item that is not an object") }

internal fun List<Any>.strings(field: String, code: RejectCode): List<String> =
    map { it as? String ?: reject(code, "$field has an item that is not a string") }

internal inline fun <reified E> JSONObject.enum(key: String, code: RejectCode = RejectCode.BAD_MANIFEST): E where E : Enum<E>, E : JsonValue {
    val value = string(key, code)
    return parseEnum<E>(value) ?: reject(if (code == RejectCode.BAD_MANIFEST) RejectCode.BAD_VALUE else code, "$key \"$value\"")
}
