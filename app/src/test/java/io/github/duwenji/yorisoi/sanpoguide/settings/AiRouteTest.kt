package io.github.duwenji.yorisoi.sanpoguide.settings

import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.sanpoguide.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.duwenji.yorisoi.kit.llm.AiRoute

/** The operator's AI and the user's own key (platform ADR-AI-RELAY R-1, R-4). */
class AiRouteTest {
    @Test
    fun `someone with a key keeps it after the update, everyone else starts on the operator's AI`() {
        assertEquals(AiRoute.OWN_KEY, GuideSettings.defaultRoute(hasOwnKey = true, operatorAvailable = true))
        assertEquals(AiRoute.OPERATOR, GuideSettings.defaultRoute(hasOwnKey = false, operatorAvailable = true))
        // A build without a relay (release, until production's is up) offers only the own key.
        assertEquals(AiRoute.OWN_KEY, GuideSettings.defaultRoute(hasOwnKey = false, operatorAvailable = false))
    }

    @Test
    fun `the operator's AI needs no key, the own key does`() {
        // Debug builds have the development relay; release builds none until production's is up.
        assertEquals(BuildConfig.AI_RELAY_URL.isNotEmpty(), GuideSettings.operatorAiAvailable)
        assertEquals(GuideSettings.operatorAiAvailable, GuideSettings(aiRoute = AiRoute.OPERATOR).isConfigured)
        assertFalse(GuideSettings(aiRoute = AiRoute.OWN_KEY).isConfigured)
        assertTrue(GuideSettings(aiRoute = AiRoute.OWN_KEY, apiKeys = mapOf(Provider.CLAUDE to "sk-test")).isConfigured)
    }

    @Test
    fun `what the AI said is credited to the route it came by`() {
        assertEquals("運営の AI", GuideSettings(aiRoute = AiRoute.OPERATOR).aiName)
        assertEquals("deepseek-chat", GuideSettings(aiRoute = AiRoute.OWN_KEY, provider = Provider.DEEPSEEK).aiName)
    }
}
