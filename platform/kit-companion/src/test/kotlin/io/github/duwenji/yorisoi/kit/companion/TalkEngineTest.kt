package io.github.duwenji.yorisoi.kit.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TalkEngineTest {
    private enum class Gate { GAP, MOVING }

    private data class Scene(val open: Set<Gate>, val due: Set<String>) : TalkGates<Gate> {
        override fun isOpen(gate: Gate) = gate in open
    }

    private fun rule(name: String, vararg gates: Gate) =
        TalkRule<Scene, String, Gate>(name, gates.toSet()) { s -> name.takeIf { it in s.due } }

    private val engine = TalkEngine(listOf(rule("weather"), rule("spot", Gate.GAP, Gate.MOVING), rule("chat", Gate.GAP)), nothing = "-")

    @Test
    fun `the first rule that speaks wins`() {
        assertEquals("weather", engine.decide(Scene(Gate.entries.toSet(), setOf("weather", "spot", "chat"))))
        assertEquals("spot", engine.decide(Scene(Gate.entries.toSet(), setOf("spot", "chat"))))
    }

    @Test
    fun `a rule behind a closed gate is skipped, the next one may speak`() {
        assertEquals("chat", engine.decide(Scene(setOf(Gate.GAP), setOf("spot", "chat"))))
        assertEquals("-", engine.decide(Scene(emptySet(), setOf("spot", "chat"))))
        assertEquals("weather", engine.decide(Scene(emptySet(), setOf("weather"))))
    }

    @Test
    fun `a closed gate means the rule isn't even asked`() {
        var asked = false
        val e = TalkEngine(listOf(TalkRule<Scene, String, Gate>("spot", setOf(Gate.GAP)) { asked = true; null }), "-")
        e.decide(Scene(emptySet(), emptySet()))
        assertEquals(false, asked)
    }

    @Test
    fun `nothing when no rule speaks`() {
        assertEquals("-", engine.decide(Scene(Gate.entries.toSet(), emptySet())))
    }

    @Test
    fun `rule names are unique`() {
        assertThrows(IllegalArgumentException::class.java) { TalkEngine(listOf(rule("a"), rule("a")), "-") }
    }
}
