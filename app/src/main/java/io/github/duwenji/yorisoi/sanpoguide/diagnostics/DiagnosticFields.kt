package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import io.github.duwenji.yorisoi.kit.geo.Facility
import io.github.duwenji.yorisoi.kit.geo.FacilityKind
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkDecision
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkSituation
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkGate
import io.github.duwenji.yorisoi.sanpoguide.material.SpotMaterials
import io.github.duwenji.yorisoi.sanpoguide.settings.Threshold
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import io.github.duwenji.yorisoi.sanpoguide.station.Station
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** How the walk's decisions, situations and settings are written into [WalkDiagnostics]. */
object DiagnosticFields {
    /** What was decided, by kind, with what it was about (not the whole spot with its tags). */
    fun decision(d: TalkDecision): List<Pair<String, Any?>> = when (d) {
        is TalkDecision.WeatherTurn -> listOf(
            "kind" to "weather_turn", "change" to d.change.kind.name,
            "inMinutes" to d.change.minutes, "description" to d.change.description,
        )
        is TalkDecision.Greeting -> listOf("kind" to "greeting", "select" to d.select)
        is TalkDecision.SpotCue -> listOf(
            "kind" to "spot_cue", "poiId" to d.poi.id, "name" to d.poi.name,
            "category" to d.poi.category, "distanceM" to d.distanceM, "cue" to d.fire.key,
        )
        is TalkDecision.Spot -> listOf(
            "kind" to "spot", "poiId" to d.poi.id, "name" to d.poi.name,
            "category" to d.poi.category, "distanceM" to d.distanceM,
        )
        is TalkDecision.PointCue -> listOf("kind" to "point_cue", "cue" to d.fire.key)
        is TalkDecision.Sunset -> listOf("kind" to "sunset", "minutesLeft" to d.minutesLeft)
        is TalkDecision.NearFacility -> listOf(
            "kind" to "near_facility", "need" to d.advice.need.name, "facility" to d.advice.facility.kind.name,
            "facilityName" to d.advice.facility.name, "distanceM" to d.advice.distanceM, "forecast" to d.advice.forecast,
        )
        is TalkDecision.Rest -> listOf("kind" to "rest", "minutes" to d.minutes)
        TalkDecision.Milestone -> listOf("kind" to "milestone")
        TalkDecision.Checkpoint -> listOf("kind" to "checkpoint")
        TalkDecision.Nothing -> listOf("kind" to "nothing")
    }

    /** The figures the decision was made from, to check the thresholds and spacing against. */
    fun situation(s: TalkSituation): List<Pair<String, Any?>> = listOf(
        "elapsedMs" to s.elapsedMs,
        "walkedM" to s.distanceM.toInt(),
        "restMinutes" to s.restMinutes,
        "sinceSpokeMs" to s.now - s.lastSpokeAt,
        "sinceSpotTalkMs" to if (s.lastSpotTalkAt == 0L) null else s.now - s.lastSpotTalkAt,
        "spotsInReach" to s.spots.size,
        "newSpotsInReach" to s.spots.count { !it.alreadyThisWalk },
        "level" to s.level.name,
        "openGates" to WalkGate.entries.filter(s::isOpen).joinToString(",") { it.name },
    )

    fun weather(w: Weather): List<Pair<String, Any?>> = listOf(
        "description" to w.description,
        "temperatureC" to w.temperatureC,
        "precipitationMm" to w.precipitationMm,
        "code" to w.code,
        "sunsetAt" to w.sunsetAt,
        "forecast" to JSONArray().apply {
            w.forecast.forEach { put(JSONObject().put("at", it.at).put("code", it.code).put("precipitationMm", it.precipitationMm)) }
        },
    )

    /** Every threshold in effect, by name, defaults included. */
    fun thresholds(t: Thresholds): JSONObject = JSONObject().apply { Threshold.entries.forEach { put(it.name, t[it]) } }

    /**
     * Where an Overpass query searched: the centre and radius of its first `around` (the spots;
     * benches, vending machines and shops are searched closer), the radius of the shops when
     * they were asked for, and the query's length.
     */
    fun overpassQuery(request: Request): List<Pair<String, Any?>> {
        val form = request.body as? FormBody ?: return emptyList()
        val query = (0 until form.size).firstOrNull { form.name(it) == "data" }?.let(form::value) ?: return emptyList()
        val around = AROUND.find(query)?.destructured
        return listOf(
            "lat" to around?.component2()?.toDoubleOrNull(),
            "lon" to around?.component3()?.toDoubleOrNull(),
            "radiusM" to around?.component1()?.toIntOrNull(),
            "shopRadiusM" to SHOP_AROUND.find(query)?.groupValues?.get(1)?.toIntOrNull(),
            "queryChars" to query.length,
        )
    }

    /** How big an Overpass answer was: bytes, and the elements in it (null when it isn't JSON). */
    fun overpassAnswer(body: String): List<Pair<String, Any?>> = listOf(
        "bytes" to body.encodeToByteArray().size,
        "elements" to try {
            JSONObject(body).optJSONArray("elements")?.length()
        } catch (e: JSONException) {
            null
        },
    )

    /**
     * What a spot search found, as the walk sees it: the channel in use and the shops it asked
     * for, every spot nearest first with how far it is and what each channel on the device holds
     * about it ([SpotMaterials]), and the amenities counted by kind.
     */
    fun spotSearch(
        spots: List<Pair<Poi, Float>>,
        facilities: List<Facility>,
        channel: Station,
        stations: List<Station>,
    ): List<Pair<String, Any?>> = listOf(
        "channel" to JSONObject().put("key", channel.key).put("id", channel.id).put("name", channel.name)
            .put("trial", channel.source?.trial == true),
        "shops" to channel.searchedKinds.joinToString(",") { it.name },
        "spotCount" to spots.size,
        "facilityCount" to facilities.size,
        "facilities" to JSONObject().apply {
            FacilityKind.entries.forEach { kind -> put(kind.name, facilities.count { it.kind == kind }) }
        },
        "spots" to JSONArray().apply {
            spots.sortedBy { it.second }.forEach { (poi, distanceM) ->
                val o = JSONObject().put("poiId", poi.id).put("name", poi.name)
                    .put("category", poi.category).put("distanceM", distanceM.toInt())
                val related = stations.mapNotNull { station -> SpotMaterials.of(station, poi)?.let { channelAbout(station, it) } }
                if (related.isNotEmpty()) o.put("channels", JSONArray(related))
                put(o)
            }
        },
    )

    private fun channelAbout(station: Station, m: SpotMaterials): JSONObject = JSONObject()
        .put("key", station.key)
        .put("name", station.name)
        .put("cues", JSONArray().apply { m.cues.forEach { put(JSONObject().put("id", it.id).put("trigger", it.trigger)) } })
        .put(
            "resources",
            JSONArray().apply {
                m.resources.forEach { r ->
                    put(
                        JSONObject().put("source", r.source).put("alias", r.alias ?: JSONObject.NULL)
                            .put("collection", r.collection?.id ?: JSONObject.NULL)
                            .put("version", r.collection?.version ?: JSONObject.NULL)
                            .put("producer", r.collection?.producerName ?: JSONObject.NULL)
                            .put("id", r.id).put("type", r.type.name),
                    )
                }
            },
        )

    private val AROUND = Regex("""around:(\d+),(-?[\d.]+),(-?[\d.]+)""")

    // The shops' part comes last, right after the amenities' `out center 40;`.
    private val SHOP_AROUND = Regex("""out center \d+;\s*\(\s*nwr\(around:(\d+),""")
}
