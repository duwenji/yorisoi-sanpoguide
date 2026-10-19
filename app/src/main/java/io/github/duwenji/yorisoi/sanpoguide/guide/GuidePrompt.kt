package io.github.duwenji.yorisoi.sanpoguide.guide

import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.mood.Mood
import io.github.duwenji.yorisoi.sanpoguide.material.Ground
import java.util.Locale

/** Values for the spot-guide prompt files (`guide/user`, `fallback/guide`). The wording lives in the files. */
object GuidePrompt {
    /** OSM tags worth showing the model; the rest (addresses, operators, ids...) is noise. */
    private val USEFUL_TAGS = setOf(
        "historic", "tourism", "amenity", "religion", "denomination", "leisure", "natural",
        "heritage", "start_date", "architect", "description", "inscription", "species",
        "wikipedia", "wikidata", "opening_hours", "website", "ele",
    )

    /**
     * [shareLocation]: the user allows coordinates to go to the AI (off by default). [grounds]: the
     * reviewed texts the AI may take as fact (API-004「AI の根拠」), each with whose it is. [mood]:
     * the tone to speak in, when the channel matches it (`mood.tone`).
     */
    fun spotVars(
        poi: Poi,
        distanceM: Int?,
        shareLocation: Boolean = false,
        grounds: List<Ground> = emptyList(),
        mood: Mood? = null,
    ): Map<String, Any?> = mapOf(
        "name" to poi.name,
        "category" to poi.category,
        "distance_m" to distanceM,
        "inside" to (distanceM != null && poi.isInside(distanceM)),
        "tags" to poi.tags.filterKeys { it in USEFUL_TAGS || it.startsWith("name") }
            .map { (k, v) -> "$k=$v" },
        "coords" to if (shareLocation) formatLatLon(poi.lat, poi.lon) else null,
        "mood" to mood?.let { mapOf("summary" to it.summary) },
        "grounded" to grounds.isNotEmpty(),
        // A ground can't close its own tag and pass for the prompt (API-004 I-15).
        "grounds" to grounds.map { mapOf("text" to fence(it.text), "source" to fence(it.producerName)) }.ifEmpty { null },
    )

    private fun fence(text: String) = text.replace('<', '＜').replace('>', '＞')

    /** About 1m of precision; more digits only add noise to the prompt. */
    fun formatLatLon(lat: Double, lon: Double): String = "%.5f, %.5f".format(Locale.ROOT, lat, lon)

    fun fallbackVars(poi: Poi): Map<String, Any?> = mapOf(
        "name" to poi.name,
        "category" to poi.category,
        "description" to poi.tags["description"],
        "start_date" to poi.tags["start_date"],
    )
}
