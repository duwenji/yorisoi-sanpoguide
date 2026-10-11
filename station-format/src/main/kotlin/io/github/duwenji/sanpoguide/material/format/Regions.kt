package io.github.duwenji.sanpoguide.material.format

/**
 * Regions for choosing collections by place (API-002 P-15, API-004 P-6): 3-letter geohashes,
 * about 150km across. The management system lists a collection's regions; the app fetches the
 * collections whose regions meet the one it's in or a neighbour, so only that coarse area is told.
 */
object Regions {
    const val PRECISION = 3
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** The geohash of [lat], [lon] at [precision] letters. */
    fun geohash(lat: Double, lon: Double, precision: Int = PRECISION): String {
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "not on the earth: $lat, $lon" }
        var latRange = -90.0 to 90.0
        var lonRange = -180.0 to 180.0
        var even = true
        var bits = 0
        var value = 0
        return buildString {
            while (length < precision) {
                if (even) {
                    val mid = (lonRange.first + lonRange.second) / 2
                    value = value shl 1
                    if (lon >= mid) { value = value or 1; lonRange = mid to lonRange.second } else lonRange = lonRange.first to mid
                } else {
                    val mid = (latRange.first + latRange.second) / 2
                    value = value shl 1
                    if (lat >= mid) { value = value or 1; latRange = mid to latRange.second } else latRange = latRange.first to mid
                }
                even = !even
                if (++bits == 5) {
                    append(BASE32[value])
                    bits = 0
                    value = 0
                }
            }
        }
    }

    /** A collection's regions: where each resource with a subject is about, without repeats, in order (API-002 P-15). */
    fun of(resources: Resources): List<String> = resources.items.mapNotNull { r ->
        when (val about = r.about) {
            is About.Spot -> geohash(about.lat, about.lon)
            is About.Point -> geohash(about.lat, about.lon)
            null -> null
        }
    }.distinct().sorted()

    /**
     * The region of [lat], [lon] and the eight around it (API-004 P-6), found by stepping one cell
     * each way from the cell's centre; cells past the poles are left out, and longitude wraps.
     */
    fun around(lat: Double, lon: Double): Set<String> {
        val (latStep, lonStep) = cellSize()
        val center = geohash(lat, lon)
        val (cLat, cLon) = centreOf(center)
        return buildSet {
            for (dy in -1..1) for (dx in -1..1) {
                val y = cLat + dy * latStep
                if (y < -90.0 || y > 90.0) continue
                var x = cLon + dx * lonStep
                if (x < -180.0) x += 360.0
                if (x >= 180.0) x -= 360.0
                add(geohash(y, x))
            }
        }
    }

    /** Height and width of a cell in degrees: 15 bits split 8 for longitude, 7 for latitude at 3 letters. */
    private fun cellSize(): Pair<Double, Double> {
        val bits = PRECISION * 5
        val lonBits = (bits + 1) / 2
        val latBits = bits / 2
        return 180.0 / (1 shl latBits) to 360.0 / (1 shl lonBits)
    }

    private fun centreOf(hash: String): Pair<Double, Double> {
        var latRange = -90.0 to 90.0
        var lonRange = -180.0 to 180.0
        var even = true
        for (c in hash) {
            val v = BASE32.indexOf(c)
            for (bit in 4 downTo 0) {
                val on = (v shr bit) and 1 == 1
                if (even) {
                    val mid = (lonRange.first + lonRange.second) / 2
                    lonRange = if (on) mid to lonRange.second else lonRange.first to mid
                } else {
                    val mid = (latRange.first + latRange.second) / 2
                    latRange = if (on) mid to latRange.second else latRange.first to mid
                }
                even = !even
            }
        }
        return (latRange.first + latRange.second) / 2 to (lonRange.first + lonRange.second) / 2
    }
}
