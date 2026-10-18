package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkDecision
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkSituation
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkGate
import io.github.duwenji.yorisoi.sanpoguide.settings.Threshold
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import org.json.JSONArray
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
}
