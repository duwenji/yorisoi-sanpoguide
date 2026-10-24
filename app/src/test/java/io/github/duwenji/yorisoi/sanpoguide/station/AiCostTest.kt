package io.github.duwenji.yorisoi.sanpoguide.station

import io.github.duwenji.yorisoi.core.format.GuideLength
import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.duwenji.yorisoi.kit.channel.StationOverrides

/** What a channel asks of the AI, shown before it is used (platform ADR-004 H-9, stakeholders.md W-3). */
class AiCostTest {
    private val stations = BuiltInStations.load()
    private val standard = stations.first { it.id == BuiltInStations.STANDARD }

    @Test
    fun `an hour's walk calls the AI at most as often as the talk level lets the companion speak`() {
        assertEquals(10, AiCost.maxCallsPerHour(TalkLevel.QUIET))
        assertEquals(20, AiCost.maxCallsPerHour(TalkLevel.NORMAL))
        assertEquals(30, AiCost.maxCallsPerHour(TalkLevel.CHATTY))
    }

    @Test
    fun `a channel that talks more or narrates longer than the standard one says so`() {
        val same = AiCost.of(standard, standard, operatorAi = false, dailyLimit = 100)
        assertFalse(same.moreThanStandard)
        assertEquals(100, same.dailyLimit)
        val chatty = standard.withOverrides(StationOverrides(talkLevel = TalkLevel.CHATTY.level))
        assertTrue(AiCost.of(chatty, standard, operatorAi = true, dailyLimit = 30).moreThanStandard)
        val long = standard.withOverrides(StationOverrides(guideLength = GuideLength.LONG))
        assertTrue(standard.guideLength < GuideLength.LONG)
        assertTrue(AiCost.of(long, standard, operatorAi = false, dailyLimit = null).moreThanStandard)
        val quiet = standard.withOverrides(StationOverrides(talkLevel = TalkLevel.QUIET.level, guideLength = GuideLength.SHORT))
        assertFalse(AiCost.of(quiet, standard, operatorAi = false, dailyLimit = null).moreThanStandard)
    }
}
