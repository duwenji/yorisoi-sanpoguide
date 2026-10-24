package io.github.duwenji.yorisoi.sanpoguide.guide

import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.kit.llm.LlmClient
import io.github.duwenji.yorisoi.kit.llm.ClaudeClient
import io.github.duwenji.yorisoi.kit.llm.OpenAiCompatibleClient
import io.github.duwenji.yorisoi.kit.llm.OutputCheck
import io.github.duwenji.yorisoi.kit.llm.Purpose
import io.github.duwenji.yorisoi.kit.llm.RelayClient
import io.github.duwenji.yorisoi.kit.llm.RelayException
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.mood.Mood
import io.github.duwenji.yorisoi.core.prompt.PromptTemplates
import io.github.duwenji.yorisoi.sanpoguide.material.Ground
import io.github.duwenji.yorisoi.sanpoguide.prompt.Prompts
import io.github.duwenji.yorisoi.sanpoguide.settings.AiRoute
import io.github.duwenji.yorisoi.kit.llm.AiUsage
import io.github.duwenji.yorisoi.sanpoguide.settings.GuideSettings
import io.github.duwenji.yorisoi.sanpoguide.settings.SettingsRepository
import io.github.duwenji.yorisoi.kit.channel.Station
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import io.github.duwenji.yorisoi.sanpoguide.station.guideSystemVars

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
 * The operator's AI goes through [relay] (null in a build without one; platform ADR-AI-RELAY).
 */
class GuideRepository(
    private val settingsRepo: SettingsRepository,
    private val prompts: PromptTemplates,
    private val usage: AiUsage,
    private val relay: RelayClient?,
) {
    /**
     * Whether today's calls reached the limit: the user's on their own key (platform ADR-004 H-9), the
     * relay's on the operator's AI (ADR-AI-RELAY R-2); cached narrations still come.
     */
    fun limitReached(): Boolean {
        val settings = settingsRepo.settings.value
        return when (settings.aiRoute) {
            AiRoute.OPERATOR -> relay?.quota?.value?.exhausted() == true
            AiRoute.OWN_KEY -> usage.reached(settings.dailyAiLimit)
        }
    }

    /** Counts a call on the user's own key against their limit; the relay counts the operator's AI. */
    private fun takeCall(settings: GuideSettings): Boolean =
        settings.aiRoute == AiRoute.OPERATOR || usage.tryUse(settings.dailyAiLimit)

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
        val ai = if (settings.aiRoute == AiRoute.OPERATOR) "operator" else "${settings.provider}/${settings.model()}"
        val cacheKey = "$ai/${channel.key}/${channel.guideLength}/${poi.id}/$share/$from"
        cache[cacheKey]?.let { return it }
        check(!limitReached() && takeCall(settings)) { "The daily limit of AI calls is reached" }
        val text = generate(
            Purpose.GUIDE, settings,
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
        if (!settings.isConfigured || limitReached() || !takeCall(settings)) return null
        val text = try {
            generate(Purpose.CHAT, settings, system, user)
        } catch (e: IllegalStateException) {
            return null
        }
        return if (OutputCheck.violation(text) != null) "" else text
    }

    private suspend fun generate(purpose: Purpose, settings: GuideSettings, system: String, user: String): String {
        val kind = purpose.name.lowercase()
        val startedAt = System.currentTimeMillis()
        fun report(reply: String?, error: String?) = onCall(
            AiCall(kind, settings.aiName, system, user, reply, error, reply?.let(OutputCheck::violation), System.currentTimeMillis() - startedAt),
        )
        val text = try {
            clientFor(settings).generate(system, user, purpose)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(null, "${e.javaClass.simpleName}: ${e.message}")
            // The relay's count ran out before ours: the same as reaching the limit here.
            if (e is RelayException && e.code == "daily_limit_reached") throw IllegalStateException("The daily limit of AI calls is reached", e)
            throw e
        }
        // Recorded as the AI wrote it, so stage directions it slipped in can still be counted.
        report(text, null)
        return OutputCheck.withoutStageDirections(text)
    }

    @Synchronized
    private fun clientFor(settings: GuideSettings): LlmClient {
        if (settings.aiRoute == AiRoute.OPERATOR) return relay ?: error("This build has no relay")
        client?.let { (s, c) -> if (s == settings) return c }
        return createClient(settings).also { client = settings to it }
    }

    companion object {
        /** A client for the user's own key ([AiRoute.OWN_KEY]). */
        fun createClient(settings: GuideSettings): LlmClient = when (settings.provider) {
            Provider.CLAUDE -> ClaudeClient(settings.apiKey(), settings.model())
            else -> OpenAiCompatibleClient(settings.baseUrl(), settings.apiKey(), settings.model())
        }
    }
}
