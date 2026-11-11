package io.github.duwenji.yorisoi.sanpoguide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapUpTest {
    @Test
    fun firstHeadingTurnsTheMap() {
        assertEquals(90f, MapUp.next(null, 90f))
    }

    @Test
    fun unknownHeadingKeepsTheMap() {
        assertNull(MapUp.next(null, null))
        assertEquals(120f, MapUp.next(120f, null))
    }

    @Test
    fun smallChangesDontTurnIt() {
        assertEquals(90f, MapUp.next(90f, 95f))
        assertEquals(90f, MapUp.next(90f, 81f))
    }

    @Test
    fun largerChangesTurnIt() {
        assertEquals(100f, MapUp.next(90f, 100f))
        assertEquals(75f, MapUp.next(90f, 75f))
    }

    @Test
    fun aroundNorth() {
        // 355° and 3° are 8° apart, not 352°.
        assertEquals(355f, MapUp.next(355f, 3f))
        assertEquals(10f, MapUp.next(355f, 10f))
        assertEquals(350f, MapUp.next(5f, -10f))
    }
}
