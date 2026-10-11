package io.github.duwenji.sanpoguide.material.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** API-002 P-15 and API-004 P-6: regions as 3-letter geohashes, and the one around a place. */
class RegionsTest {
    @Test
    fun `geohashes match the reference encoding`() {
        // The worked example of the geohash's description.
        assertEquals("u4pruydqqvj", Regions.geohash(57.64911, 10.40744, precision = 11))
        assertEquals("xn7", Regions.geohash(35.3258, 139.5564)) // Kamakura
        assertEquals("xn7", Regions.geohash(35.6812, 139.7671)) // Tokyo station: the same 150km cell
    }

    @Test
    fun `the region around a place is it and its eight neighbours`() {
        val around = Regions.around(35.3258, 139.5564)
        assertEquals(9, around.size)
        assertTrue("xn7" in around)
        assertTrue(around.all { it.length == Regions.PRECISION })
        // Neighbours across the date line wrap; past a pole there are none.
        val dateLine = Regions.around(0.0, 179.9)
        assertEquals(9, dateLine.size)
        assertTrue(dateLine.any { Regions.geohash(0.0, -179.9) == it })
        assertEquals(6, Regions.around(89.9, 0.0).size)
    }
}
