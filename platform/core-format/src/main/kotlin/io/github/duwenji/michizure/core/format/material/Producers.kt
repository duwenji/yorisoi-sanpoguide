package io.github.duwenji.michizure.core.format.material

/** The kinds of material (API-004「素材」, API-003 `resources[].type`). */
enum class MaterialType(val json: String) { TEXT("text"), IMAGE("image"), AUDIO("audio"), VIDEO("video") }

/** What a producer can be asked about: a spot, a point, an event of the walk, or nothing in particular. */
enum class SubjectKind(val json: String) { SPOT("spot"), POINT("point"), EVENT("event"), NONE("none") }

/** Who answers for a producer: the app's own code, the operator's review, or a test ticket (before review). */
enum class Trust(val json: String) { BUILTIN("builtin"), REVIEWED("reviewed"), TRIAL("trial") }

/** When a producer gets its material: made on the phone, fetched when the channel is chosen, or when shown. */
enum class Fetch(val json: String) { LOCAL("local"), PREFETCH("prefetch"), ON_DEMAND("onDemand") }

/** Whether producing material tells an outside service what it's about (for example, which spot). */
enum class Exposure(val json: String) { NONE("none"), SUBJECT("subject") }

/**
 * What a producer makes and how (API-004「素材提供者の記述」). Every producer — built in, a third
 * party's collection, or a channel's own resources — is described the same way; what the app
 * allows follows from these values ([ProducerPolicy]), not from what kind of producer it is.
 *
 * @param params the instructions a channel may give it (API-004 I-8).
 */
data class ProducerDescriptor(
    val id: String,
    val version: Int,
    val types: Set<MaterialType>,
    val subjects: Set<SubjectKind>,
    val trust: Trust,
    val fetch: Fetch,
    val exposure: Exposure,
    val generated: Boolean,
    val params: Set<String> = emptySet(),
)

/** The app's rules for producers by their description (API-004 方針 P-1・P-2). Channels and producers can't change them. */
object ProducerPolicy {
    /** The rule [descriptor] breaks, or null when it may be used. */
    fun violation(descriptor: ProducerDescriptor): String? = with(descriptor) {
        when {
            // P-1: only the app's own code may fetch when shown, reveal the subject, or generate.
            trust != Trust.BUILTIN && fetch != Fetch.PREFETCH -> "P-1: a ${trust.json} producer must prefetch ($id)"
            trust != Trust.BUILTIN && exposure != Exposure.NONE -> "P-1: a ${trust.json} producer must not expose the subject ($id)"
            trust != Trust.BUILTIN && generated -> "P-1: a ${trust.json} producer must not generate ($id)"
            // P-2 follows: fetching on demand while revealing the subject is left to built-in producers.
            else -> null
        }
    }
}

/**
 * The producers built into the app (API-004「組み込みの素材提供者」). Adding one takes an app update
 * (ADR-002 D-6) and a new row in that table. The management system's checks read this list to
 * know which `builtin:` references a channel may use.
 */
object BuiltInProducers {
    const val PREFIX = "builtin:"

    /** Bumped when a built-in producer changes what it makes. */
    private const val VERSION = 1

    /** Spot narration by the AI (`GuideRepository`). */
    val AI_GUIDE = ProducerDescriptor(
        "${PREFIX}ai-guide", VERSION, setOf(MaterialType.TEXT), setOf(SubjectKind.SPOT),
        Trust.BUILTIN, Fetch.ON_DEMAND, Exposure.SUBJECT, generated = true, params = setOf("persona", "focus", "length"),
    )

    /** The walking companion's lines by the AI (`WalkCompanion`). */
    val AI_COMPANION = ProducerDescriptor(
        "${PREFIX}ai-companion", VERSION, setOf(MaterialType.TEXT), setOf(SubjectKind.EVENT),
        Trust.BUILTIN, Fetch.ON_DEMAND, Exposure.SUBJECT, generated = true, params = setOf("persona", "topics", "events", "tone"),
    )

    /** The fixed lines used without the AI (`prompts/fallback/`). */
    val FALLBACK_TEXT = ProducerDescriptor(
        "${PREFIX}fallback-text", VERSION, setOf(MaterialType.TEXT), setOf(SubjectKind.SPOT, SubjectKind.EVENT),
        Trust.BUILTIN, Fetch.LOCAL, Exposure.NONE, generated = false,
    )

    /** Spot photos from Wikimedia Commons (`SpotPhotos`). */
    val COMMONS_PHOTO = ProducerDescriptor(
        "${PREFIX}commons-photo", VERSION, setOf(MaterialType.IMAGE), setOf(SubjectKind.SPOT),
        Trust.BUILTIN, Fetch.ON_DEMAND, Exposure.SUBJECT, generated = false,
    )

    /** Background sounds synthesized on the phone (`Soundscape`). */
    val AMBIENT = ProducerDescriptor(
        "${PREFIX}ambient", VERSION, setOf(MaterialType.AUDIO), setOf(SubjectKind.NONE),
        Trust.BUILTIN, Fetch.LOCAL, Exposure.NONE, generated = false, params = setOf("sound"),
    )

    /** The mood picture drawn by the app (`MoodScene`). */
    val MOOD_SCENE = ProducerDescriptor(
        "${PREFIX}mood-scene", VERSION, setOf(MaterialType.IMAGE), setOf(SubjectKind.NONE),
        Trust.BUILTIN, Fetch.LOCAL, Exposure.NONE, generated = false,
    )

    val all: List<ProducerDescriptor> = listOf(AI_GUIDE, AI_COMPANION, FALLBACK_TEXT, COMMONS_PHOTO, AMBIENT, MOOD_SCENE)

    fun byId(id: String): ProducerDescriptor? = all.firstOrNull { it.id == id }
}
