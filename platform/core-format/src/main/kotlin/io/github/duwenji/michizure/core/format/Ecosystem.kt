package io.github.duwenji.michizure.core.format

/** What a channel is for (API-003 2.0 `activities`, ADR-003 E-24). */
enum class Activity(override val json: String) : JsonValue { WALK("walk"), CYCLE("cycle") }

/** How users look for channels (API-003 2.0 `genres`). */
enum class Genre(override val json: String) : JsonValue {
    GENERAL("general"), HISTORY("history"), NATURE("nature"), FOOD("food"), SHOPPING("shopping"),
}

/**
 * What an app may or may not implement (API-003 2.0 `requires.features`): the prompt slots, the
 * kinds of cue triggers, and the presentations.
 */
enum class Feature(override val json: String) : JsonValue {
    SLOT_GUIDE("slot:guide"),
    SLOT_COMPANION("slot:companion"),
    TRIGGER_SPOT("trigger:spot"),
    TRIGGER_POINT("trigger:point"),
    TRIGGER_EVENT("trigger:event"),
    PRESENT_SPEAK("present:speak"),
    PRESENT_CARD("present:card"),
    PRESENT_NOTIFY("present:notify"),
    PRESENT_SHEET("present:sheet"),
    PRESENT_PLAY("present:play"),
    PRESENT_AMBIENT("present:ambient"),
    PRESENT_FULLSCREEN("present:fullscreen"),
    ;

    companion object {
        fun of(presentAs: PresentAs): Feature = entries.first { it.json == "present:${presentAs.json}" }
    }
}

/**
 * How strictly a package is read (API-003 G-3). [REVIEW], the channel management system's machine
 * review, turns away any value the ecosystem's lists don't hold. [APP] reads a format 2 package the
 * way an app must when newer values may be in it ("アプリでの知らない値"): never looser than the
 * channel meant, but without turning it away for what this app doesn't know yet.
 */
enum class Strictness { REVIEW, APP }

/**
 * The apps of the ecosystem, by the id the list (`minAppVersions`, API-002 P-20) and collections
 * (`grants.apps`, API-004 I-11) name them with.
 */
object AppIds {
    const val SANPOGUIDE = "sanpoguide"
    val KNOWN: Set<String> = setOf(SANPOGUIDE)
}

/**
 * What an app is and does, for choosing channels (API-003 G-4, ADR-003 E-6 ①). Each app has one.
 *
 * @param versionCode this build's version, for `minAppVersions` (API-002 P-20)
 */
data class AppProfile(val id: String, val activity: Activity, val features: Set<Feature>, val versionCode: Int)

/**
 * Whether an app can use a channel (API-003 確認の手順 5a): the channel is for the app's activity,
 * needs nothing the app lacks, and holds no value the app doesn't know where that value would
 * restrict it ([StationManifest.unknownRestrictions]). Not a rejection: such a channel just isn't
 * offered.
 */
object Compatibility {
    /** Why [app] can't use the channel [manifest] describes; empty when it can. */
    fun reasons(manifest: StationManifest, app: AppProfile): List<String> = buildList {
        if (app.activity !in manifest.activities) add("not for ${app.activity.json} (${manifest.activities.joinToString { it.json }})")
        (manifest.requires - app.features).forEach { add("needs ${it.json}") }
        manifest.unknownRestrictions.forEach { add("unknown $it") }
    }

    /**
     * The same from the list, before fetching the package (API-002 P-19). The list carries the
     * values as written, so ones this app's core-format doesn't know are judged here: an unknown
     * activity is ignored, an unknown feature means the channel can't be used.
     */
    fun reasons(listed: ListedChannel, app: AppProfile): List<String> = buildList {
        if (app.activity.json !in listed.activities) add("not for ${app.activity.json} (${listed.activities.joinToString()})")
        val known = app.features.map { it.json }.toSet()
        listed.requires.filter { it !in known }.forEach { add("needs $it") }
    }
}
