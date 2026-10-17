package io.github.duwenji.yorisoi.sanpoguide.companion

import org.json.JSONArray
import org.json.JSONObject

data class PlannedStop(
    val poiId: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
)

data class WalkPlan(
    val themeCategory: String? = null,
    val durationMinutes: Int? = null,
    val distanceKm: Int? = null,
    val note: String? = null,
    val stops: List<PlannedStop> = emptyList(),
) {
    fun promptSummary(): String? = buildList {
        themeCategory?.takeIf { it.isNotBlank() }?.let { add("テーマ: $it") }
        durationMinutes?.let { add("時間: ${it}分") }
        distanceKm?.let { add("距離: ${it}km") }
    }.takeIf { it.isNotEmpty() }?.joinToString("・")

    fun toggleStop(stop: PlannedStop): WalkPlan =
        if (stops.any { it.poiId == stop.poiId }) {
            copy(stops = stops.filterNot { it.poiId == stop.poiId })
        } else {
            copy(stops = stops + stop)
        }
}

internal object WalkPlanJson {
    fun toJson(plan: WalkPlan): JSONObject = JSONObject()
        .put("themeCategory", plan.themeCategory ?: JSONObject.NULL)
        .put("durationMinutes", plan.durationMinutes ?: JSONObject.NULL)
        .put("distanceKm", plan.distanceKm ?: JSONObject.NULL)
        .put("note", plan.note ?: JSONObject.NULL)
        .put("stops", JSONArray().apply {
            plan.stops.forEach { stop ->
                put(
                    JSONObject()
                        .put("poiId", stop.poiId)
                        .put("name", stop.name)
                        .put("category", stop.category)
                        .put("lat", stop.lat)
                        .put("lon", stop.lon)
                )
            }
        })

    fun fromJson(json: JSONObject): WalkPlan = WalkPlan(
        themeCategory = json.optionalString("themeCategory"),
        durationMinutes = json.optionalInt("durationMinutes"),
        distanceKm = json.optionalInt("distanceKm"),
        note = json.optionalString("note"),
        stops = json.getJSONArray("stops").let { stops ->
            (0 until stops.length()).map { index ->
                val stop = stops.getJSONObject(index)
                PlannedStop(
                    poiId = stop.getString("poiId"),
                    name = stop.getString("name"),
                    category = stop.getString("category"),
                    lat = stop.getDouble("lat"),
                    lon = stop.getDouble("lon"),
                )
            }
        },
    )

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.optionalInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)
}
