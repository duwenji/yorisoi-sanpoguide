package io.github.duwenji.yorisoi.sanpoguide.guide

import io.github.duwenji.yorisoi.kit.llm.AiCall
import io.github.duwenji.yorisoi.kit.llm.AiGateway
import io.github.duwenji.yorisoi.kit.llm.LlmClient
import io.github.duwenji.yorisoi.kit.llm.OutputCheck
import io.github.duwenji.yorisoi.kit.llm.Purpose
import io.github.duwenji.yorisoi.kit.llm.RelayClient
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.mood.Mood
import io.github.duwenji.yorisoi.core.prompt.PromptTemplates
import io.github.duwenji.yorisoi.kit.channel.material.Ground
import io.github.duwenji.yorisoi.sanpoguide.prompt.Prompts
import io.github.duwenji.yorisoi.kit.llm.AiRoute
import io.github.duwenji.yorisoi.kit.llm.AiUsage
import io.github.duwenji.yorisoi.sanpoguide.settings.GuideSettings
import io.github.duwenji.yorisoi.sanpoguide.settings.SettingsRepository
import io.github.duwenji.yorisoi.kit.channel.Station
import java.util.concurrent.ConcurrentHashMap
import io.github.duwenji.yorisoi.sanpoguide.station.guideSystemVars

/**
 * The AI service: narrations for spots, cached so each spot is generated (and billed) once, and
 * free-form lines for the companion. The producers in `material/` decide what stands in without it.
 * The operator's AI goes through [relay] (null in a build without one; platform ADR-AI-RELAY).
 */
class GuideRepository(
    private val settingsRepo: SettingsRepository,
    private val prompts: PromptTemplates,
    usage: AiUsage,
    relay: RelayClient?,
) {
    /** Reaching the AI, within the day's limit, is platform kit-llm's. */
    private val gateway = AiGateway({ settingsRepo.settings.value.connection }, usage, relay)

    /**
     * Whether today's calls reached the limit: the user's on their own key (platform ADR-004 H-9), the
     * relay's on the operator's AI (ADR-AI-RELAY R-2); cached narrations still come.
     */
    fun limitReached(): Boolean = gateway.limitReached()

    private val cache = ConcurrentHashMap<String, String>()

    /** Hears of every call made, for the developer's record of a walk. */
    var onCall: (AiCall) -> Unit
        get() = gateway.onCall
        set(value) {
            gateway.onCall = value
        }

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
        val text = gateway.generate(
            Purpose.GUIDE,
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
        val text = try {
            gateway.generate(Purpose.CHAT, system, user)
        } catch (e: IllegalStateException) {
            return null
        }
        return if (OutputCheck.violation(text) != null) "" else text
    }

    companion object {
        /** A client for the user's own key ([AiRoute.OWN_KEY]). */
        fun createClient(settings: GuideSettings): LlmClient = AiGateway.createClient(settings.connection)
    }
}
