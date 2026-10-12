package io.github.duwenji.sanpoguide.guide

import io.github.duwenji.michizure.kit.llm.Provider
import io.github.duwenji.michizure.kit.llm.LlmClient
import io.github.duwenji.michizure.kit.llm.ClaudeClient
import io.github.duwenji.michizure.kit.llm.OpenAiCompatibleClient
import io.github.duwenji.michizure.kit.geo.Poi
import io.github.duwenji.michizure.core.prompt.PromptTemplates
import io.github.duwenji.sanpoguide.prompt.Prompts
import io.github.duwenji.sanpoguide.settings.GuideSettings
import io.github.duwenji.sanpoguide.settings.SettingsRepository
import io.github.duwenji.sanpoguide.station.Station
import java.util.concurrent.ConcurrentHashMap

/**
 * The AI service: narrations for spots, cached so each spot is generated (and billed) once, and
 * free-form lines for the companion. The producers in `material/` decide what stands in without it.
 */
class GuideRepository(
    private val settingsRepo: SettingsRepository,
    private val prompts: PromptTemplates,
) {
    private val cache = ConcurrentHashMap<String, String>()
    private var client: Pair<GuideSettings, LlmClient>? = null

    /** The AI's narration of [poi] on [channel], whose slots and guide length shape it. Needs an AI set up. */
    suspend fun guideFor(poi: Poi, distanceM: Int?, channel: Station): String {
        val settings = settingsRepo.settings.value
        check(settings.isConfigured) { "No AI service is set up" }
        val share = settings.shareLocationWithAi
        // Each channel narrates in its own way, so a narration made for one isn't reused by another.
        val cacheKey = "${settings.provider}/${settings.model()}/${channel.key}/${channel.guideLength}/${poi.id}/$share"
        cache[cacheKey]?.let { return it }
        val text = clientFor(settings).generate(
            prompts.render(Prompts.Guide.SYSTEM, channel.guideSystemVars()),
            prompts.render(Prompts.Guide.USER, GuidePrompt.spotVars(poi, distanceM, share)),
        )
        cache[cacheKey] = text
        return text
    }

    /** Free-form generation with the configured service; null when no AI is configured. */
    suspend fun chat(system: String, user: String): String? {
        val settings = settingsRepo.settings.value
        if (!settings.isConfigured) return null
        return clientFor(settings).generate(system, user)
    }

    @Synchronized
    private fun clientFor(settings: GuideSettings): LlmClient {
        client?.let { (s, c) -> if (s == settings) return c }
        return createClient(settings).also { client = settings to it }
    }

    companion object {
        fun createClient(settings: GuideSettings): LlmClient = when (settings.provider) {
            Provider.CLAUDE -> ClaudeClient(settings.apiKey(), settings.model())
            else -> OpenAiCompatibleClient(settings.baseUrl(), settings.apiKey(), settings.model())
        }
    }
}
