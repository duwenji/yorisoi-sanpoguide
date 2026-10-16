package io.github.duwenji.yorisoi.sanpoguide.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The user's daily limit of AI calls (platform ADR-004 H-9). */
class AiUsageTest {
    private class Memory : UsageStore {
        var saved: Pair<String, Int>? = null
        override fun read() = saved
        override fun write(day: String, count: Int) {
            saved = day to count
        }
    }

    @Test
    fun `calls count up to the limit, and start again the next day`() {
        var day = LocalDate.of(2026, 10, 16)
        val store = Memory()
        val usage = AiUsage(store) { day }
        assertTrue(usage.tryUse(2))
        assertTrue(usage.tryUse(2))
        assertTrue(usage.reached(2))
        assertFalse(usage.tryUse(2))
        assertEquals(2, usage.count.value)
        // No limit counts on.
        assertTrue(usage.tryUse(null))
        assertFalse(usage.reached(null))

        day = day.plusDays(1)
        assertFalse(usage.reached(2))
        assertTrue(usage.tryUse(2))
        assertEquals("2026-10-17" to 1, store.saved)
        // A new instance reads today's count back.
        assertTrue(AiUsage(store) { day }.reached(1))
    }
}
