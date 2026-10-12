package io.github.duwenji.michizure.core.format

import io.github.duwenji.michizure.core.format.material.BuiltInProducers
import io.github.duwenji.michizure.core.format.material.MaterialType
import io.github.duwenji.michizure.core.format.material.Resources
import org.json.JSONObject

/** A third-party collection a channel uses, under a name of its own (API-003 1.2 `collections`). */
data class CollectionUse(val alias: String, val id: String, val minVersion: Int)

/** Choose material by what it is rather than which it is (API-003 1.2 `selectors`, API-004「基準で選ぶ素材提供者」). */
data class Selector(
    val alias: String,
    val type: MaterialType,
    val tags: List<String>,
    val lang: String?,
    val maxDurationSec: Int?,
    /** True: material about the cue's spot or point; false: material about nothing in particular. */
    val aboutTrigger: Boolean,
    /** Producers' account ids to prefer, in order. */
    val producers: List<String>,
)

/** When a cue fires (API-003「きっかけ」). */
sealed interface Trigger {
    data class Spot(val osm: String, val name: String?) : Trigger
    /** Any spot of these kinds (API-003 1.2, `"any": true`). */
    data class AnySpot(val categories: List<SpotKind>) : Trigger
    data class Point(val lat: Double, val lon: Double, val radiusM: Int) : Trigger
    data class Event(val event: CueEvent) : Trigger
}

enum class CueEvent(override val json: String) : JsonValue { SELECT("select"), START("start"), FINISH("finish") }

/** Whether a spot cue stands in for the AI's narration or follows it (API-003 F-12). */
enum class NarrationMode(override val json: String) : JsonValue { REPLACE("replace"), ADD("add") }

enum class Repeat(override val json: String) : JsonValue { ONCE_PER_WALK("oncePerWalk"), ONCE_PER_DAY("oncePerDay"), ONCE("once") }

/**
 * How material is shown, with what each may show and the app stage that first shows it
 * (API-003「見せ方」, F-8). [stageOf] gives the stage for a type: video, for instance, comes later
 * than images on the same sheet.
 */
enum class PresentAs(override val json: String, val types: Set<MaterialType>) : JsonValue {
    SPEAK("speak", setOf(MaterialType.TEXT)),
    CARD("card", setOf(MaterialType.TEXT, MaterialType.IMAGE)),
    NOTIFY("notify", setOf(MaterialType.TEXT, MaterialType.IMAGE)),
    SHEET("sheet", MaterialType.entries.toSet()),
    PLAY("play", setOf(MaterialType.AUDIO)),
    AMBIENT("ambient", setOf(MaterialType.AUDIO)),
    FULLSCREEN("fullscreen", setOf(MaterialType.IMAGE, MaterialType.VIDEO));

    /** The most characters a text may have here (card 200, notify 100), or null for no limit beyond the resource's. */
    val maxTextChars: Int? get() = when (this) {
        CARD -> 200
        NOTIFY -> 100
        else -> null
    }

    fun stageOf(type: MaterialType): Int = when {
        type == MaterialType.VIDEO -> 3
        type == MaterialType.AUDIO || this == PLAY || this == AMBIENT -> 2
        else -> 1
    }
}

/** A reference to material (API-004「参照の書き方」). */
sealed interface Reference {
    /** The channel's own resource (`self#id`, or the shorthand `resource`). */
    data class Self(val id: String) : Reference
    data class BuiltIn(val id: String) : Reference
    /** A collection by its alias, with a resource id or choosing by the cue's subject; or a selector (never with an id). */
    data class Alias(val alias: String, val id: String?) : Reference
}

/** One way of showing in a cue: candidates in order for each item (a sheet may show several items). */
data class Presentation(val presentAs: PresentAs, val items: List<List<Reference>>)

data class Cue(
    val id: String,
    val trigger: Trigger,
    /** Null when not written: a cue that asks the AI itself mustn't also say how it relates to the AI. */
    val narration: NarrationMode?,
    val repeat: Repeat,
    val present: List<Presentation>,
)

/** A channel's material: its own resources, the collections and selectors it uses, and its cues. */
data class StationMedia(
    val resources: Resources,
    val collections: List<CollectionUse>,
    val selectors: List<Selector>,
    val cues: List<Cue>,
) {
    val isEmpty: Boolean get() = resources.items.isEmpty() && collections.isEmpty() && selectors.isEmpty() && cues.isEmpty()

    companion object {
        val NONE = StationMedia(Resources.NONE, emptyList(), emptyList(), emptyList())
    }
}

/**
 * Reads and checks `collections`, `selectors` and `cues` (API-003 確認の手順 9 and the part of
 * 9a that needs nothing outside the package). A collection's resource ids can only be checked
 * against the collection itself, which is the list's job (API-004 手順 3・4).
 */
internal object CueReader {
    const val MAX_CUES = 500
    const val MAX_ANY_SPOT_CUES = 5
    const val MAX_COLLECTIONS = 5
    const val MAX_SELECTORS = 5

    private val CUE_ID = Regex("[a-z0-9-]{1,40}")
    private val ALIAS = Regex("[a-z0-9-]{1,20}")
    private val OSM = Regex("(node|way|relation)/[1-9][0-9]*")

    /**
     * @param lenient an app reading a format 2 package (API-003 G-3): a cue with a trigger it doesn't
     *   know is left out, and so is a presentation it doesn't know (and the cue, if none is left)
     */
    fun read(json: JSONObject, resources: Resources, lenient: Boolean = false): StationMedia {
        val collections = readCollections(json)
        val selectors = readSelectors(json)
        val aliases = collections.map { it.alias } + selectors.map { it.alias }
        aliases.groupBy { it }.values.firstOrNull { it.size > 1 }?.let { reject(RejectCode.BAD_CUE, "alias \"${it[0]}\" twice") }

        val cueItems = json.optList("cues", RejectCode.BAD_CUE).objects("cues", RejectCode.BAD_CUE)
        if (cueItems.size > MAX_CUES) reject(RejectCode.BAD_CUE, "${cueItems.size} cues (max $MAX_CUES)")
        val cues = cueItems.mapNotNull { c ->
            try {
                readCue(c, resources, collections, selectors, lenient)
            } catch (e: UnknownValue) {
                if (lenient) null else throw e
            }
        }
        cues.groupBy { it.id }.values.firstOrNull { it.size > 1 }?.let { reject(RejectCode.BAD_CUE, "cue id \"${it[0].id}\" twice") }
        val anySpot = cues.count { it.trigger is Trigger.AnySpot }
        if (anySpot > MAX_ANY_SPOT_CUES) reject(RejectCode.BAD_CUE, "$anySpot cues for any spot (max $MAX_ANY_SPOT_CUES)")

        // Every declared collection and selector must be used by some cue (once the cues an app
        // left out are gone, that may no longer hold, and needn't).
        if (lenient) return StationMedia(resources, collections, selectors, cues)
        val used = cues.flatMap { c -> c.present.flatMap { p -> p.items.flatten() } }.filterIsInstance<Reference.Alias>().map { it.alias }.toSet()
        (aliases - used).firstOrNull()?.let { reject(RejectCode.BAD_CUE, "\"$it\" is declared but no cue uses it") }
        return StationMedia(resources, collections, selectors, cues)
    }

    private fun readCollections(json: JSONObject): List<CollectionUse> {
        val items = json.optList("collections", RejectCode.BAD_CUE).objects("collections", RejectCode.BAD_CUE)
        if (items.size > MAX_COLLECTIONS) reject(RejectCode.BAD_CUE, "${items.size} collections (max $MAX_COLLECTIONS)")
        return items.map { c ->
            val use = CollectionUse(alias(c), c.string("id", RejectCode.BAD_CUE), c.int("minVersion", RejectCode.BAD_CUE))
            if (!ID_PATTERN.matches(use.id)) reject(RejectCode.BAD_CUE, "collection id \"${use.id}\"")
            if (use.minVersion < 1) reject(RejectCode.BAD_CUE, "${use.alias}: minVersion ${use.minVersion}")
            use
        }
    }

    private fun readSelectors(json: JSONObject): List<Selector> {
        val items = json.optList("selectors", RejectCode.BAD_CUE).objects("selectors", RejectCode.BAD_CUE)
        if (items.size > MAX_SELECTORS) reject(RejectCode.BAD_CUE, "${items.size} selectors (max $MAX_SELECTORS)")
        return items.map { s ->
            val alias = alias(s)
            val match = s.obj("match", RejectCode.BAD_CUE)
            val typeName = match.string("type", RejectCode.BAD_CUE)
            val type = MaterialType.entries.firstOrNull { it.json == typeName } ?: reject(RejectCode.BAD_CUE, "$alias: type \"$typeName\"")
            val tags = match.list("tags", RejectCode.BAD_CUE).strings("$alias tags", RejectCode.BAD_CUE)
            if (tags.size !in 1..5) reject(RejectCode.BAD_CUE, "$alias: ${tags.size} tags (1..5)")
            val lang = match.optString("lang", RejectCode.BAD_CUE)
            if (lang != null && lang != "ja") reject(RejectCode.BAD_CUE, "$alias: lang \"$lang\"")
            val maxDuration = match.optInt("maxDurationSec", RejectCode.BAD_CUE)
            if (maxDuration != null && (maxDuration < 1 || type !in setOf(MaterialType.AUDIO, MaterialType.VIDEO))) {
                reject(RejectCode.BAD_CUE, "$alias: maxDurationSec $maxDuration")
            }
            val subject = match.optString("subject", RejectCode.BAD_CUE) ?: "trigger"
            if (subject != "trigger" && subject != "none") reject(RejectCode.BAD_CUE, "$alias: subject \"$subject\"")
            val producers = s.optList("producers", RejectCode.BAD_CUE).strings("$alias producers", RejectCode.BAD_CUE)
            if (producers.size > 10) reject(RejectCode.BAD_CUE, "$alias: ${producers.size} producers (max 10)")
            producers.firstOrNull { !ACCOUNT_ID_PATTERN.matches(it) }?.let { reject(RejectCode.BAD_CUE, "$alias: producer \"$it\"") }
            Selector(alias, type, tags, lang, maxDuration, subject == "trigger", producers)
        }
    }

    private fun alias(o: JSONObject): String {
        val alias = o.string("as", RejectCode.BAD_CUE)
        if (!ALIAS.matches(alias) || alias == "self" || alias == "builtin") reject(RejectCode.BAD_CUE, "alias \"$alias\"")
        return alias
    }

    private fun readCue(c: JSONObject, resources: Resources, collections: List<CollectionUse>, selectors: List<Selector>, lenient: Boolean): Cue {
        val id = c.string("id", RejectCode.BAD_CUE)
        if (!CUE_ID.matches(id)) reject(RejectCode.BAD_CUE, "cue id \"$id\"")
        val trigger = readTrigger(id, c.obj("trigger", RejectCode.BAD_CUE))
        val narration = c.optString("narration", RejectCode.BAD_CUE)?.let { parseEnum<NarrationMode>(it) ?: reject(RejectCode.BAD_CUE, "$id: narration \"$it\"") }
        if (narration != null && trigger !is Trigger.Spot && trigger !is Trigger.AnySpot) reject(RejectCode.BAD_CUE, "$id: narration is for spot cues")
        val repeat = c.optString("repeat", RejectCode.BAD_CUE)?.let { parseEnum<Repeat>(it) ?: reject(RejectCode.BAD_CUE, "$id: repeat \"$it\"") }
            ?: Repeat.ONCE_PER_WALK
        val present = c.list("present", RejectCode.BAD_CUE).objects("$id present", RejectCode.BAD_CUE)
        if (present.size !in 1..4) reject(RejectCode.BAD_CUE, "$id: ${present.size} presentations (1..4)")
        val presentations = present.mapNotNull { p ->
            try {
                readPresentation(id, p, resources, collections, selectors)
            } catch (e: UnknownValue) {
                if (lenient) null else throw e
            }
        }
        if (presentations.isEmpty()) unknownValue(RejectCode.BAD_CUE, "$id: no presentation this app knows")
        val asksAi = presentations.any { p -> p.items.flatten().contains(Reference.BuiltIn(BuiltInProducers.AI_GUIDE.id)) }
        if (asksAi && narration != null) reject(RejectCode.BAD_CUE, "$id: a cue that asks builtin:ai-guide can't also set narration")
        return Cue(id, trigger, narration, repeat, presentations)
    }

    private fun readTrigger(id: String, t: JSONObject): Trigger = when (val type = t.string("type", RejectCode.BAD_CUE)) {
        "spot" -> if (t.optNullable("any") == true) {
            if (t.has("osm")) reject(RejectCode.BAD_CUE, "$id: osm and any together")
            val kinds = t.list("categories", RejectCode.BAD_CUE).strings("$id categories", RejectCode.BAD_CUE)
                .map { parseEnum<SpotKind>(it) ?: unknownValue(RejectCode.BAD_CUE, "$id: category \"$it\"") }
            if (kinds.size !in 1..8 || kinds.toSet().size != kinds.size) reject(RejectCode.BAD_CUE, "$id: categories must be 1..8 different kinds")
            Trigger.AnySpot(kinds)
        } else {
            if (t.has("any")) reject(RejectCode.BAD_CUE, "$id: any must be true when written")
            val osm = t.string("osm", RejectCode.BAD_CUE)
            if (!OSM.matches(osm)) reject(RejectCode.BAD_CUE, "$id: osm \"$osm\"")
            Trigger.Spot(osm, t.optString("name", RejectCode.BAD_CUE)?.also { checkLength("$id name", it, 1, 60, RejectCode.BAD_CUE) })
        }
        "point" -> {
            val lat = t.double("lat", RejectCode.BAD_CUE)
            val lon = t.double("lon", RejectCode.BAD_CUE)
            val radius = t.int("radiusM", RejectCode.BAD_CUE)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) reject(RejectCode.BAD_CUE, "$id: the point is not on the earth")
            if (radius !in 20..300) reject(RejectCode.BAD_CUE, "$id: radiusM $radius (20..300)")
            Trigger.Point(lat, lon, radius)
        }
        "event" -> t.string("event", RejectCode.BAD_CUE).let { e ->
            Trigger.Event(parseEnum<CueEvent>(e) ?: unknownValue(RejectCode.BAD_CUE, "event \"$e\""))
        }
        else -> unknownValue(RejectCode.BAD_CUE, "$id: trigger type \"$type\"")
    }

    private fun readPresentation(
        cue: String, p: JSONObject, resources: Resources, collections: List<CollectionUse>, selectors: List<Selector>,
    ): Presentation {
        val presentAs = p.string("as", RejectCode.BAD_CUE).let { parseEnum<PresentAs>(it) ?: unknownValue(RejectCode.BAD_CUE, "as \"$it\"") }
        val written = listOf("resource", "resources", "source", "sources").filter { p.optNullable(it) != null }
        if (written.size != 1) reject(RejectCode.BAD_CUE, "$cue: ${presentAs.json} needs one of resource, resources, source or sources")
        val many = written[0] == "resources" || written[0] == "sources"
        if (many && presentAs != PresentAs.SHEET) reject(RejectCode.BAD_CUE, "$cue: only a sheet shows several items")
        val items: List<List<Reference>> = when (written[0]) {
            "resource" -> listOf(listOf(Reference.Self(p.string("resource", RejectCode.BAD_CUE))))
            "resources" -> p.list("resources", RejectCode.BAD_CUE).strings("$cue resources", RejectCode.BAD_CUE).map { listOf(Reference.Self(it)) }
            "source" -> listOf(candidates(cue, p.required("source", RejectCode.BAD_CUE)))
            else -> p.list("sources", RejectCode.BAD_CUE).map { candidates(cue, it) }
        }
        if (items.isEmpty() || items.size > 10) reject(RejectCode.BAD_CUE, "$cue: ${items.size} items on the sheet (1..10)")
        items.flatten().forEach { check(cue, presentAs, it, resources, collections, selectors) }
        return Presentation(presentAs, items)
    }

    /** One source: a reference, or 1–3 of them in order. */
    private fun candidates(cue: String, value: Any): List<Reference> {
        val texts = when (value) {
            is String -> listOf(value)
            is org.json.JSONArray -> (0 until value.length()).map { value.opt(it) as? String ?: reject(RejectCode.BAD_CUE, "$cue: a source is not a string") }
            else -> reject(RejectCode.BAD_CUE, "$cue: source is neither a string nor a list")
        }
        if (texts.size !in 1..3) reject(RejectCode.BAD_CUE, "$cue: ${texts.size} candidates (1..3)")
        return texts.map { parseReference(cue, it) }
    }

    private fun parseReference(cue: String, text: String): Reference {
        if (text.startsWith(BuiltInProducers.PREFIX)) return Reference.BuiltIn(text)
        val alias = text.substringBefore('#')
        val id = text.substringAfter('#', "").takeIf { '#' in text }
        if (id != null && id.isEmpty()) reject(RejectCode.UNRESOLVED_REFERENCE, "$cue: \"$text\"")
        return if (alias == "self") Reference.Self(id ?: reject(RejectCode.UNRESOLVED_REFERENCE, "$cue: \"self\" needs an id")) else Reference.Alias(alias, id)
    }

    private fun check(
        cue: String, presentAs: PresentAs, ref: Reference, resources: Resources, collections: List<CollectionUse>, selectors: List<Selector>,
    ) {
        fun mismatch(what: String): Nothing = reject(RejectCode.BAD_CUE, "$cue: ${presentAs.json} can't show $what")
        when (ref) {
            is Reference.Self -> {
                val r = resources[ref.id] ?: reject(RejectCode.UNRESOLVED_REFERENCE, "$cue: no resource \"${ref.id}\"")
                if (r.type !in presentAs.types) mismatch("${r.type.json} \"${r.id}\"")
                val max = presentAs.maxTextChars
                val text = resources.texts[r.id]
                if (max != null && text != null && text.charCount() > max) reject(RejectCode.BAD_CUE, "$cue: ${presentAs.json} text \"${r.id}\" over $max chars")
            }
            is Reference.BuiltIn -> {
                val d = BuiltInProducers.byId(ref.id) ?: reject(RejectCode.UNRESOLVED_REFERENCE, "$cue: unknown producer \"${ref.id}\"")
                if (d.types.none { it in presentAs.types }) mismatch(d.id)
            }
            is Reference.Alias -> {
                val selector = selectors.firstOrNull { it.alias == ref.alias }
                when {
                    selector != null -> {
                        if (ref.id != null) reject(RejectCode.UNRESOLVED_REFERENCE, "$cue: selector \"${ref.alias}\" can't name an id")
                        if (selector.type !in presentAs.types) mismatch("${selector.type.json} (selector \"${selector.alias}\")")
                    }
                    collections.none { it.alias == ref.alias } -> reject(RejectCode.UNRESOLVED_REFERENCE, "$cue: \"${ref.alias}\" is not declared")
                }
            }
        }
    }
}

/**
 * The app stage a channel's material needs (API-003 M-1: 1 text and images, 2 audio, 3 video).
 * 0 means no material at all. The management system sets `minAppVersion` from it; the app turns
 * away a channel it can't show yet (`unsupported_resource`).
 *
 * What a collection holds is known only once it's read from the list: pass its resources' types
 * by alias as [collectionTypes]. Without them, collections aren't counted, so the result is the
 * least the channel needs.
 */
object MediaStage {
    fun required(media: StationMedia, collectionTypes: (alias: String, id: String?) -> Set<MaterialType> = { _, _ -> emptySet() }): Int {
        if (media.isEmpty) return 0
        val resourceTypes = media.resources.items.associate { it.id to it.type }
        var stage = 1
        for (p in media.cues.flatMap { it.present }) {
            for (ref in p.items.flatten()) {
                val types = when (ref) {
                    is Reference.Self -> listOfNotNull(resourceTypes[ref.id])
                    is Reference.BuiltIn -> BuiltInProducers.byId(ref.id)?.types?.filter { it in p.presentAs.types }.orEmpty()
                    // A selector says its type; a collection, what it holds.
                    is Reference.Alias -> media.selectors.firstOrNull { it.alias == ref.alias }?.let { listOf(it.type) }
                        ?: collectionTypes(ref.alias, ref.id).filter { it in p.presentAs.types }
                }
                types.forEach { stage = maxOf(stage, p.presentAs.stageOf(it)) }
            }
        }
        // Resources a cue doesn't name directly (a transcript, a poster) still count by their type.
        media.resources.items.forEach { stage = maxOf(stage, if (it.type == MaterialType.VIDEO) 3 else if (it.type == MaterialType.AUDIO) 2 else 1) }
        return stage
    }
}
