package io.github.duwenji.yorisoi.sanpoguide.material

import android.graphics.Bitmap
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.channel.material.Content
import io.github.duwenji.yorisoi.kit.channel.material.MaterialContext
import io.github.duwenji.yorisoi.kit.channel.material.Situation
import io.github.duwenji.yorisoi.kit.channel.material.Subject
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.mood.Mood
import io.github.duwenji.yorisoi.kit.voice.Soundscape
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkEvent
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkSession

// What this app adds to kit-channel's material (platform ADR-ECOSYSTEM-PLATFORM E-16): the spot
// with its tags, the walk's events, the forms of material it makes, and the walk its built-in
// producers read.

/** A spot the walk found. Cues and material match it by its id, name and position; the built-in producers read [poi]. */
data class PoiSubject(val poi: Poi, override val distanceM: Int?) : Subject.Spot {
    override val id: String get() = poi.id
    override val name: String get() = poi.name
    override val lat: Double get() = poi.lat
    override val lon: Double get() = poi.lon
    override val category: String get() = poi.category
}

/** An event of the walk (API-003 `trigger.type: event`): the companion's lines are about it. */
data class EventSubject(val event: TalkEvent) : Subject

/** A picture the app fetched (a Commons photo). */
data class BitmapContent(val bitmap: Bitmap) : Content

/** A background sound the app synthesizes. */
data class AmbientContent(val sound: Soundscape) : Content

/** A picture the app draws (API-004 `content=render`). */
data class SceneContent(val mood: Mood) : Content

/** The walk as the built-in producers read it. */
data class WalkSituation(val session: WalkSession? = null, val weather: Weather? = null, val mood: Mood? = null) : Situation

val MaterialContext.walk: WalkSituation? get() = situation as? WalkSituation

fun walkContext(station: Station, session: WalkSession? = null, weather: Weather? = null, mood: Mood? = null) =
    MaterialContext(station, WalkSituation(session, weather, mood))
