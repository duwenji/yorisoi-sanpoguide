package io.github.duwenji.michizure.kit.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class VoicesTest {
    private val rate = 22_050

    private fun render(scape: Soundscape, seconds: Int, seed: Long = 42): FloatArray {
        val v = Voices.create(scape, rate, seed)
        return FloatArray(rate * seconds) { v.next() }
    }

    @Test
    fun `every sound stays in range, is audible and isn't stuck to one side`() {
        Soundscape.entries.forEach { scape ->
            // The temple bell's first strike comes after a few seconds.
            val samples = render(scape, seconds = 10)
            // Well under full scale: the final clamp is a guard, not part of the sound.
            assertTrue("$scape clips or is NaN", samples.all { !it.isNaN() && abs(it) < 0.95f })
            val rms = sqrt(samples.map { it * it }.average())
            assertTrue("$scape is silent (rms $rms)", rms > 0.003)
            assertTrue("$scape is too loud (rms $rms)", rms < 0.5)
            val mean = samples.average()
            assertTrue("$scape has a DC offset ($mean)", abs(mean) < 0.05)
        }
    }

    @Test
    fun `the same seed makes the same sound`() {
        assertTrue(render(Soundscape.BIRDS, 2, seed = 7).contentEquals(render(Soundscape.BIRDS, 2, seed = 7)))
    }
}
