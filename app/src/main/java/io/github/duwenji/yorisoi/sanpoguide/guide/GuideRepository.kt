package io.github.duwenji.yorisoi.sanpoguide.guide

import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.kit.llm.LlmClient
import io.github.duwenji.yorisoi.kit.llm.ClaudeClient
import io.github.duwenji.yorisoi.kit.llm.OpenAiCompatibleClient
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.mood.Mood
import io.github.duwenji.yorisoi.core.prompt.PromptTemplates
import io.github.duwenji.yorisoi.sanpoguide.material.Ground
import io.github.duwenji.yorisoi.sanpoguide.prompt.Prompts
import io.github.duwenji.yorisoi.sanpoguide.settings.AiUsage
import io.github.duwenji.yorisoi.sanpoguide.settings.GuideSettings
import io.github.duwenji.yorisoi.sanpoguide.settings.SettingsRepository
import io.github.duwenji.yorisoi.sanpoguide.station.Station
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * One call to the AI service, for the developer's record of a walk: what was asked, what came
 * back (or why nothing did) and how long it took. [violation] is what [OutputCheck] found.
 */
data class AiCall(
    val kind: String,
    val model: String,
    val system: String,
    val user: String,
    val reply: String?,
    val error: String?,
    val violation: String?,
    val ms: Long,
)

/**
 * The AI service: narrations for spots, cached so each spot is generated (and billed) once, and
 * free-form lines for the companion. The producers in `material/` decide what stands in without it.
 */
class GuideRepository(
    private val settingsRepo: SettingsRepository,
    private val prompts: PromptTemplates,
    private val usage: AiUsage,
) {
    /** Whether today's calls reached the user's limit (platform ADR-004 H-9); cached narrations still come. */
    fun limitReached(): Boolean = usage.reached(settingsRepo.settings.value.dailyAiLimit)

    private val cache = ConcurrentHashMap<String, String>()
    private var client: Pair<GuideSettings, LlmClient>? = null

    /** Hears of every call made, for the developer's record of a walk. */
    var onCall: (AiCall) -> Unit = {}

    /**
     * The AI's narration of [poi] on [channel], whose slots and guide length shape it, written from
     * [grounds] when there are any (API-004「AI の根拠」), in the tone of [mood] when given. Needs
     * an AI set up.
     */
    suspend fun guideFor(poi: Poi, distanceM: Int?, channel: Station, grounds: List<Ground> = emptyList(), mood: Mood? = null): String {
        val settings = settingsRepo.settings.value
        check(settings.isConfigured) { "No AI service is set up" }
        val share = settings.shareLocationWithAi
        // Each channel narrates in its own way, so a narration made for one isn't reused by another;
        // nor is one written before a collection's new version gave other grounds. The mood only
        // sets the tone, so a narration isn't made (and billed) again when it changes.
        val from = grounds.joinToString(",") { "${it.collection}@${it.version}#${it.resource}" }
        val cacheKey = "${settings.provider}/${settings.model()}/${channel.key}/${channel.guideLength}/${poi.id}/$share/$from"
        cache[cacheKey]?.let { return it }
        check(usage.tryUse(settings.dailyAiLimit)) { "The daily limit of AI calls is reached" }
        val text = generate(
            "guide", settings,
            prompts.render(Prompts.Guide.SYSTEM, channel.guideSystemVars()),
            prompts.render(Prompts.Guide.USER, GuidePrompt.spotVars(poi, distanceM, share, grounds, mood)),
        )
        OutputCheck.violation(text)?.let { error("The narration has $it") }
        cache[cacheKey] = text
        return text
    }

    /**
     * Free-form generation with the configured service; null when no AI is configured or today's
     * limit is reached, and empty when the reply can't be read out (platform ADR-004 H-6 ③).
     */
    suspend fun chat(system: String, user: String): String? {
        val settings = settingsRepo.settings.value
        if (!settings.isConfigured || !usage.tryUse(settings.dailyAiLimit)) return null
        val text = generate("chat", settings, system, user)
        return if (OutputCheck.violation(text) != null) "" else text
    }

    private suspend fun generate(kind: String, settings: GuideSettings, system: String, user: String): String {
        val startedAt = System.currentTimeMillis()
        fun report(reply: String?, error: String?) = onCall(
            AiCall(kind, settings.model(), system, user, reply, error, reply?.let(OutputCheck::violation), System.currentTimeMillis() - startedAt),
        )
        val text = try {
            clientFor(settings).generate(system, user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(null, "${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        report(text, null)
        return text
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
