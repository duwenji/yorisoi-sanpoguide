package io.github.duwenji.yorisoi.sanpoguide.companion

import android.location.Location
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.NearbyHighlight
import io.github.duwenji.yorisoi.kit.geo.NearbyHighlights
import io.github.duwenji.yorisoi.kit.geo.Poi

/**
 * The spots around the user that the AI is told of, so that it can talk of what is near
 * (platform REQ-NEARBY-TALK F-6〜F-9): picked from the spots found, leaving out those talked about
 * on this walk, by the channel's kinds.
 */
class Surroundings(
    private val spots: () -> List<Poi>,
    private val location: () -> Location?,
    private val talkedAbout: () -> Set<String>,
    private val station: () -> Station,
) {
    /** Around the user now, leaving out [except] too (the spot being told about); empty before a location. */
    fun now(except: String? = null): List<NearbyHighlight> {
        val here = location() ?: return emptyList()
        val st = station()
        return NearbyHighlights.pick(
            spots(),
            LatLon(here.latitude, here.longitude),
            headingDeg = heading(here),
            exclude = talkedAbout() + listOfNotNull(except),
            prefer = st.prefer,
            skip = st.skip,
        )
    }

    companion object {
        /** Walking speed, below which the bearing says nothing (as for the amenities' direction). */
        private const val MIN_HEADING_SPEED = 0.5f

        internal fun heading(location: Location): Double? =
            location.bearing.toDouble().takeIf { location.hasBearing() && location.speed >= MIN_HEADING_SPEED }

        /**
         * For the spot guide (`guide/user` の `nearby`): names and kinds only. The guide is kept and
         * told again from wherever one comes, so where they are from here isn't in it (D-6).
         */
        fun guideVars(list: List<NearbyHighlight>): Map<String, Any?>? =
            list.map { mapOf("name" to it.poi.name, "category" to it.poi.category) }.ifEmpty { null }?.let { mapOf("spots" to it) }

        /** For the companion (`companion/situation` の `nearby`): with how far and which way. */
        fun companionVars(list: List<NearbyHighlight>): Map<String, Any?>? =
            list.map {
                mapOf(
                    "name" to it.poi.name,
                    "category" to it.poi.category,
                    "distance_m" to it.distanceM,
                    "compass" to it.compass,
                    "side" to it.side,
                )
            }.ifEmpty { null }?.let { mapOf("spots" to it) }
    }
}
