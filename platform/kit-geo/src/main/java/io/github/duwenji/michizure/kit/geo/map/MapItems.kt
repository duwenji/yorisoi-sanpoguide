package io.github.duwenji.michizure.kit.geo.map

import io.github.duwenji.michizure.kit.geo.Facility
import io.github.duwenji.michizure.kit.geo.LatLon
import io.github.duwenji.michizure.kit.geo.Poi
import io.github.duwenji.michizure.kit.geo.WalkRoute

/** A nearby spot; [visitCount] is how many walks have stopped there. */
data class SpotItem(val poi: Poi, val distanceM: Int?, val visitCount: Int)

/** A nearby toilet, drinking fountain or bench. */
data class FacilityItem(val facility: Facility, val distanceM: Int?)

/**
 * The way to [spot] drawn on the map; [askedFrom] is where the route was last looked up.
 * While [loading], [route] is a straight placeholder.
 */
data class RouteToSpot(val spot: Poi, val route: WalkRoute, val askedFrom: LatLon, val loading: Boolean = false)
