package io.github.duwenji.sanpoguide.material

import android.graphics.Bitmap
import android.util.Log
import io.github.duwenji.sanpoguide.companion.TalkEvent
import io.github.duwenji.sanpoguide.companion.WalkSession
import io.github.duwenji.sanpoguide.companion.Weather
import io.github.duwenji.sanpoguide.data.Poi
import io.github.duwenji.michizure.core.format.material.MaterialType
import io.github.duwenji.michizure.core.format.material.ProducerDescriptor
import io.github.duwenji.michizure.core.format.material.ProducerPolicy
import io.github.duwenji.sanpoguide.mood.Mood
import io.github.duwenji.sanpoguide.sound.Soundscape
import io.github.duwenji.sanpoguide.station.Station
import kotlin.coroutines.cancellation.CancellationException

/** What material is about (API-004「題材」). */
sealed interface Subject {
    data class Spot(val poi: Poi, val distanceM: Int?) : Subject
    /** A place a channel's cue names by position (API-003 `trigger.type: point`). */
    data class Point(val lat: Double, val lon: Double, val radiusM: Int) : Subject
    data class Event(val event: TalkEvent) : Subject
    data object None : Subject
}

/**
 * The situation a producer may read (API-004「素材の要求」`context`). [station] is the channel in
 * use: built-in producers take their instructions from its settings and slots, which is how the
 * channel's shorthand is expanded (API-004「省略形の展開」). [after] is why the previous
 * candidate couldn't produce, when this one is a fallback.
 */
data class MaterialContext(
    val station: Station,
    val session: WalkSession? = null,
    val weather: Weather? = null,
    val mood: Mood? = null,
    val after: MaterialResult.Unavailable? = null,
)

/**
 * A request for one piece of material (API-004 I-4): by [id] when a cue names the resource,
 * otherwise by [subject]. Built-in producers make [type]; resources may be any of [accepts]
 * (what the presentation can show), a text no longer than [maxTextChars].
 */
data class MaterialRequest(
    val subject: Subject,
    val type: MaterialType,
    val context: MaterialContext,
    val id: String? = null,
    val accepts: Set<MaterialType> = setOf(type),
    val maxTextChars: Int? = null,
)

/** The material itself, in the forms the app shows. */
sealed interface Content {
    data class Text(val text: String) : Content
    data class Image(val bitmap: Bitmap) : Content
    /** An image as stored (JPEG, PNG or WebP), decoded when shown: a channel's or a collection's. */
    class EncodedImage(val bytes: ByteArray) : Content
    /** A background sound the app synthesizes. */
    data class Ambient(val sound: Soundscape) : Content
    /** A picture the app draws (API-004 `content=render`). */
    data class Scene(val mood: Mood) : Content
}

/** Who made it and under what license (API-003 F-11). [via] names the service it came through. */
data class Credit(val author: String?, val license: String?, val source: String? = null, val via: String? = null)

/** One piece of material with where it came from (API-004「素材」); [alt] describes an image in words (API-003 F-11). */
data class Material(val type: MaterialType, val content: Content, val producer: String, val credit: Credit? = null, val alt: String? = null)

/** What a producer gave back (API-004「結果」). Producers return these instead of throwing. */
sealed interface MaterialResult {
    data class Ready(val material: Material) : MaterialResult
    /** The producer has nothing for this request. */
    data object None : MaterialResult
    /** The producer can't produce now; [detail] is for the log or an error message. */
    data class Unavailable(val reason: Reason, val detail: String? = null) : MaterialResult

    enum class Reason { OFFLINE, METERED, NO_KEY, REVOKED, NOT_PREPARED, FAILED }
}

/**
 * Something that makes material (API-004「操作」). Built-in producers, a third party's collections
 * and a channel's own resources all take this shape; how far each may go is decided from
 * [descriptor] by [ProducerPolicy], not by its class.
 */
interface Producer {
    val descriptor: ProducerDescriptor

    /** Called when a channel is chosen, to fetch everything ahead (API-004 I-6). Built-in producers need nothing. */
    suspend fun prepare() {}

    /**
     * The result when it's already known without producing (no network, no waiting), or null.
     * Lets a screen show "waiting for Wi-Fi" at once instead of a loading state.
     */
    fun peek(request: MaterialRequest): MaterialResult? = null

    suspend fun produce(request: MaterialRequest): MaterialResult
}

/** The producers the app knows, by id, and the rule for trying candidates in order (API-004 I-7). */
class Producers(
    producers: List<Producer>,
    /** Where failures go; replaceable so the rule can be tested off the device. */
    private val log: (String, Throwable?) -> Unit = { message, e -> Log.w(TAG, message, e) },
) {
    private val byId = producers.associateBy { it.descriptor.id }

    init {
        require(byId.size == producers.size) { "Duplicate producer ids" }
        producers.forEach { p -> ProducerPolicy.violation(p.descriptor)?.let { error(it) } }
    }

    operator fun get(id: String): Producer? = byId[id]

    /**
     * Asks [sources] in order and returns the first [MaterialResult.Ready]; otherwise the last
     * result (None when nothing answered). An unknown id counts as None, so a newer channel
     * naming a producer this app lacks falls through to its next candidate (API-004 互換性).
     * Each fallback hears why the one before it couldn't produce ([MaterialContext.after]).
     */
    suspend fun first(sources: List<String>, request: MaterialRequest): MaterialResult {
        var last: MaterialResult = MaterialResult.None
        var after = request.context.after
        for (id in sources) {
            val producer = byId[id] ?: continue
            val result = try {
                producer.produce(request.copy(context = request.context.copy(after = after)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Producer $id failed", e)
                MaterialResult.Unavailable(MaterialResult.Reason.FAILED, e.message)
            }
            if (result is MaterialResult.Ready) return result
            if (result is MaterialResult.Unavailable) {
                log("$id unavailable (${result.reason}): ${result.detail ?: ""}", null)
                after = result
            }
            last = result
        }
        return last
    }

    private companion object {
        const val TAG = "Producers"
    }
}
