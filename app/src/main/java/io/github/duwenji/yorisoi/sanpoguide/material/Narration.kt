package io.github.duwenji.yorisoi.sanpoguide.material

import io.github.duwenji.yorisoi.sanpoguide.companion.TalkEvent
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkSession
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.core.format.material.BuiltInProducers
import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.kit.mood.Mood
import io.github.duwenji.yorisoi.kit.voice.Soundscape
import io.github.duwenji.yorisoi.sanpoguide.station.Station

/**
 * What the app asks producers for today, written as candidates in order: the expansion of a
 * channel's shorthand (API-004「省略形の展開」). A channel's own cues (API-003 `source`) will be
 * asked the same way.
 */
object Shorthand {
    /** A spot's narration on a walk: the AI, else a fixed line. */
    val SPOT_GUIDE = listOf(BuiltInProducers.AI_GUIDE.id, BuiltInProducers.FALLBACK_TEXT.id)

    /** The companion's line for an event: the AI, else a fixed line. */
    val COMPANION = listOf(BuiltInProducers.AI_COMPANION.id, BuiltInProducers.FALLBACK_TEXT.id)

    /** The photo on a spot's sheet. */
    val SHEET_PHOTO = listOf(BuiltInProducers.COMMONS_PHOTO.id)

    val AMBIENT = listOf(BuiltInProducers.AMBIENT.id)
}

/** Asks [producers] for the material the walk and the screens use, on the channel [station] returns. */
class Narration(private val producers: Producers, private val station: () -> Station) {
    /** The companion's line for [event]; always a line, since the fixed lines can't fail. */
    suspend fun line(event: TalkEvent, session: WalkSession?, weather: Weather?): String =
        text(Shorthand.COMPANION, request(Subject.Event(event), MaterialType.TEXT, session, weather))

    /** A spot's narration when the walk reaches it, with its credit to show. */
    suspend fun spotGuide(poi: Poi, distanceM: Int?): Material =
        when (val result = producers.first(Shorthand.SPOT_GUIDE, request(Subject.Spot(poi, distanceM), MaterialType.TEXT))) {
            is MaterialResult.Ready -> result.material
            else -> error("No text from ${Shorthand.SPOT_GUIDE}: $result")
        }

    /**
     * A spot's narration for its sheet. Unlike on a walk, an AI failure is shown as one (the user
     * is looking and can try again), so the fixed line stands in only when no AI is set up.
     */
    suspend fun sheetGuide(poi: Poi, distanceM: Int?): MaterialResult {
        val request = request(Subject.Spot(poi, distanceM), MaterialType.TEXT)
        val ai = producers.first(listOf(BuiltInProducers.AI_GUIDE.id), request)
        if (ai !is MaterialResult.Unavailable || ai.reason != MaterialResult.Reason.NO_KEY) return ai
        return producers.first(listOf(BuiltInProducers.FALLBACK_TEXT.id), request.copy(context = request.context.copy(after = ai)))
    }

    /** The photo request for a spot's sheet, to [peek][Producer.peek] before [sheetPhoto]. */
    fun peekSheetPhoto(poi: Poi): MaterialResult? =
        producers[Shorthand.SHEET_PHOTO.first()]?.peek(request(Subject.Spot(poi, null), MaterialType.IMAGE))

    suspend fun sheetPhoto(poi: Poi): MaterialResult =
        producers.first(Shorthand.SHEET_PHOTO, request(Subject.Spot(poi, null), MaterialType.IMAGE))

    /** The background sound for [mood] on [channel]. */
    suspend fun ambient(channel: Station, mood: Mood): Soundscape? {
        val request = MaterialRequest(Subject.None, MaterialType.AUDIO, MaterialContext(channel, mood = mood))
        val ready = producers.first(Shorthand.AMBIENT, request) as? MaterialResult.Ready
        return (ready?.material?.content as? Content.Ambient)?.sound
    }

    private fun request(subject: Subject, type: MaterialType, session: WalkSession? = null, weather: Weather? = null) =
        MaterialRequest(subject, type, MaterialContext(station(), session, weather))

    private suspend fun text(sources: List<String>, request: MaterialRequest): String =
        when (val result = producers.first(sources, request)) {
            is MaterialResult.Ready -> (result.material.content as Content.Text).text
            else -> error("No text from $sources: $result")
        }
}
