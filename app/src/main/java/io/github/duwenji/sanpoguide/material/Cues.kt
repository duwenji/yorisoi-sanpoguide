package io.github.duwenji.sanpoguide.material

import android.util.Log
import io.github.duwenji.sanpoguide.data.Poi
import io.github.duwenji.sanpoguide.material.format.BuiltInProducers
import io.github.duwenji.sanpoguide.material.format.MaterialType
import io.github.duwenji.sanpoguide.station.Station
import io.github.duwenji.sanpoguide.station.format.Cue
import io.github.duwenji.sanpoguide.station.format.CueEvent
import io.github.duwenji.sanpoguide.station.format.NarrationMode
import io.github.duwenji.sanpoguide.station.format.PresentAs
import io.github.duwenji.sanpoguide.station.format.Presentation
import io.github.duwenji.sanpoguide.station.format.Reference
import io.github.duwenji.sanpoguide.station.format.Repeat
import io.github.duwenji.sanpoguide.station.format.StationMedia
import io.github.duwenji.sanpoguide.station.format.Trigger
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** When each cue was last shown on this device, for `oncePerDay` and `once` (API-003 `repeat`). */
interface CueMemory {
    fun lastShown(key: String): Long?
    fun record(key: String, at: Long)
}

/** [CueMemory] in a small JSON file: cue key → when it was last shown (epoch milliseconds). */
class FileCueMemory(private val file: File) : CueMemory {
    private val shown: MutableMap<String, Long> by lazy {
        runCatching { JSONObject(file.readText()).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }
            .getOrDefault(emptyMap()).toMutableMap()
    }

    @Synchronized
    override fun lastShown(key: String): Long? = shown[key]

    @Synchronized
    override fun record(key: String, at: Long) {
        shown[key] = at
        runCatching { file.writeText(JSONObject(shown as Map<*, *>).toString()) }
    }
}

/** A cue that fires now: what it's about, and the key its `repeat` counts by. */
data class CueFire(val cue: Cue, val subject: Subject, val key: String)

/**
 * Finds the cues of a channel that fire now (API-003「きっかけ」) and counts them for `repeat`:
 * once a walk in the walk's own set, once a day or once ever in [memory].
 */
class CueFinder(private val memory: CueMemory, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    /**
     * The first cue on [poi], a spot within reach: by its OSM id, or by its name (the area and
     * the node of one place have different ids, and a spot of that name within reach is the one meant).
     * A cue on any spot of its kind comes only when no cue names the spot, and counts per spot
     * (API-003 1.2 題材を問わないきっかけ).
     */
    fun spot(stationId: String, media: StationMedia, poi: Poi, distanceM: Int?, shownThisWalk: Set<String>, now: Long): CueFire? {
        val subject = Subject.Spot(poi, distanceM)
        val named = media.cues.filter { cue ->
            val t = cue.trigger as? Trigger.Spot ?: return@filter false
            t.osm == poi.id || (t.name != null && t.name == poi.name)
        }
        if (named.isNotEmpty()) return named.firstNotNullOfOrNull { fire(stationId, it, subject, shownThisWalk, now) }
        return media.cues.firstNotNullOfOrNull { cue ->
            val t = cue.trigger as? Trigger.AnySpot ?: return@firstNotNullOfOrNull null
            if (t.categories.none { it.category == poi.category }) return@firstNotNullOfOrNull null
            fire(stationId, cue, subject, shownThisWalk, now, perSubject = poi.id)
        }
    }

    /** The first cue whose point the user is inside. */
    fun point(stationId: String, media: StationMedia, lat: Double, lon: Double, shownThisWalk: Set<String>, now: Long): CueFire? =
        media.cues.firstNotNullOfOrNull { cue ->
            val t = cue.trigger as? Trigger.Point ?: return@firstNotNullOfOrNull null
            if (SubjectMatch.distance(t.lat, t.lon, lat, lon) > t.radiusM) return@firstNotNullOfOrNull null
            fire(stationId, cue, Subject.Point(t.lat, t.lon, t.radiusM), shownThisWalk, now)
        }

    /** Every cue on [event], in order; [subject] is the event's, for producers that make material from it. */
    fun events(stationId: String, media: StationMedia, event: CueEvent, subject: Subject, shownThisWalk: Set<String>, now: Long): List<CueFire> =
        media.cues.mapNotNull { cue ->
            if ((cue.trigger as? Trigger.Event)?.event != event) return@mapNotNull null
            fire(stationId, cue, subject, shownThisWalk, now)
        }

    /** Counts [fire] as shown. */
    fun record(fire: CueFire, shownThisWalk: MutableSet<String>, now: Long) {
        shownThisWalk += fire.key
        if (fire.cue.repeat != Repeat.ONCE_PER_WALK) memory.record(fire.key, now)
    }

    private fun fire(stationId: String, cue: Cue, subject: Subject, shownThisWalk: Set<String>, now: Long, perSubject: String? = null): CueFire? {
        val key = "$stationId#${cue.id}" + (perSubject?.let { "@$it" } ?: "")
        val allowed = when (cue.repeat) {
            Repeat.ONCE_PER_WALK -> key !in shownThisWalk
            Repeat.ONCE_PER_DAY -> key !in shownThisWalk && memory.lastShown(key)?.let { day(it) == day(now) } != true
            Repeat.ONCE -> memory.lastShown(key) == null
        }
        return if (allowed) CueFire(cue, subject, key) else null
    }

    private fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate()
}

/** One presentation of a cue as it came out: how it's shown, and its material (a sheet may hold several). */
data class Shown(val presentAs: PresentAs, val items: List<Material>)

/**
 * Turns a cue into material (API-003「きっかけ」`present`): each item's candidates are asked in
 * order and the first that gives something the presentation can show is used (API-004 I-7); a
 * presentation with nothing is left out. `narration: add` puts the AI's narration first
 * (API-004「省略形の展開」). Presentations of a later app [stage] are left out too.
 */
class CuePresenter(
    private val builtIns: Producers,
    private val stage: Int,
    private val log: (String, Throwable?) -> Unit = { message, e -> Log.w(TAG, message, e) },
) {
    suspend fun present(fire: CueFire, station: Station, context: MaterialContext): List<Shown> {
        val materials = station.materials ?: return emptyList()
        val own = producersOf(station, materials)
        return expand(fire.cue).mapNotNull { p ->
            val accepts = p.presentAs.types.filter { p.presentAs.stageOf(it) <= stage }.toSet()
            if (accepts.isEmpty()) return@mapNotNull null
            val items = p.items.mapNotNull { candidates -> first(candidates, p.presentAs, accepts, fire.subject, context, own) }
            if (items.isEmpty()) null else Shown(p.presentAs, items)
        }
    }

    private fun expand(cue: Cue): List<Presentation> =
        if (cue.narration == NarrationMode.ADD) listOf(AI_NARRATION) + cue.present else cue.present

    private suspend fun first(
        candidates: List<Reference>, presentAs: PresentAs, accepts: Set<MaterialType>, subject: Subject,
        context: MaterialContext, own: Map<String, Producer>,
    ): Material? {
        var after = context.after
        for (ref in candidates) {
            val (producer, id) = when (ref) {
                is Reference.Self -> own[SELF] to ref.id
                is Reference.BuiltIn -> builtIns[ref.id] to null
                // A collection that isn't usable gives nothing; a selector is named by its alias alone.
                is Reference.Alias -> own[ref.alias] to ref.id
            }
            if (producer == null) continue
            val type = producer.descriptor.types.firstOrNull { it in accepts } ?: continue
            val request = MaterialRequest(subject, type, context.copy(after = after), id, accepts, presentAs.maxTextChars)
            val result = try {
                producer.produce(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Producer ${producer.descriptor.id} failed", e)
                MaterialResult.Unavailable(MaterialResult.Reason.FAILED, e.message)
            }
            when (result) {
                is MaterialResult.Ready -> if (fits(result.material, accepts, presentAs)) return result.material
                is MaterialResult.Unavailable -> after = result
                MaterialResult.None -> Unit
            }
        }
        return null
    }

    /** The AI writes as long as it likes; a card or a notification takes only so much. */
    private fun fits(m: Material, accepts: Set<MaterialType>, presentAs: PresentAs): Boolean {
        if (m.type !in accepts) return false
        val max = presentAs.maxTextChars ?: return true
        val text = (m.content as? Content.Text)?.text ?: return true
        return text.codePointCount(0, text.length) <= max
    }

    /** The channel's own resources as `self`, each usable collection by its alias, and each selector's producer (T-8d). */
    private fun producersOf(station: Station, materials: ChannelMaterials): Map<String, Producer> {
        val trial = station.source?.trial == true
        val own = ResourceProducer(SELF, materials.media.resources, { materials.text(it, null) }, { materials.bytes(it, null) }, trial, stage)
        val collections = materials.media.collections.mapNotNull { use ->
            val usable = materials.collection(use.alias) ?: return@mapNotNull null
            use.alias to ResourceProducer(
                use.alias, usable.pkg.resources, { materials.text(it, use.alias) }, { materials.bytes(it, use.alias) },
                trial, stage, producerName = usable.listed.producerName,
            )
        }
        val selectors = materials.media.selectors.map { selector ->
            selector.alias to SelectorProducer(
                selector, materials.selected[selector.alias].orEmpty(), materials.lang, materials::bytes, trial, stage,
            )
        }
        return mapOf(SELF to own) + collections + selectors
    }

    private companion object {
        const val TAG = "CuePresenter"
        const val SELF = "self"
        val AI_NARRATION = Presentation(
            PresentAs.SPEAK, listOf(listOf(Reference.BuiltIn(BuiltInProducers.AI_GUIDE.id), Reference.BuiltIn(BuiltInProducers.FALLBACK_TEXT.id))),
        )
    }
}
