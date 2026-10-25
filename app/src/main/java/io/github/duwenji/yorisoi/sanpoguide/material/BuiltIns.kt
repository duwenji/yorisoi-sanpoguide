package io.github.duwenji.yorisoi.sanpoguide.material

import io.github.duwenji.yorisoi.kit.channel.material.MaterialRequest
import io.github.duwenji.yorisoi.kit.channel.material.Content
import io.github.duwenji.yorisoi.kit.channel.material.Credit
import io.github.duwenji.yorisoi.kit.channel.material.Material
import io.github.duwenji.yorisoi.kit.channel.material.MaterialResult
import io.github.duwenji.yorisoi.kit.channel.material.Producer
import android.util.Log
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkCompanion
import io.github.duwenji.yorisoi.kit.geo.SpotPhotos
import io.github.duwenji.yorisoi.sanpoguide.guide.GuidePrompt
import io.github.duwenji.yorisoi.sanpoguide.guide.GuideRepository
import io.github.duwenji.yorisoi.kit.channel.material.MaterialResult.Reason
import io.github.duwenji.yorisoi.core.format.material.BuiltInProducers
import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.core.prompt.PromptTemplates
import io.github.duwenji.yorisoi.sanpoguide.prompt.Prompts
import io.github.duwenji.yorisoi.kit.llm.AiRoute
import io.github.duwenji.yorisoi.sanpoguide.settings.GuideSettings
import io.github.duwenji.yorisoi.sanpoguide.settings.SettingsRepository
import io.github.duwenji.yorisoi.kit.mood.SoundscapeChoice
import kotlin.coroutines.cancellation.CancellationException

// The producers built into the app (API-004「組み込みの素材提供者」). Each wraps the code that made
// this material before producers existed, so what the user sees and hears stays the same.

private fun ready(type: MaterialType, content: Content, producer: String, credit: Credit? = null, basedOn: List<String> = emptyList()) =
    MaterialResult.Ready(Material(type, content, producer, credit, basedOn = basedOn))

/** The AI names itself as the author of what it writes (API-004 P-3). */
/** Who wrote it: the operator's AI, or the AI service and model of the user's own key. */
private fun aiCredit(s: GuideSettings) =
    Credit(author = if (s.aiRoute == AiRoute.OPERATOR) GuideSettings.OPERATOR_AI_NAME else "AI（${s.model()}）", license = "generated")

/** Runs [block], turning a failure into [Reason.FAILED] (cancellation still propagates). */
private inline fun failing(tag: String, block: () -> MaterialResult): MaterialResult = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(tag, "Production failed", e)
    MaterialResult.Unavailable(Reason.FAILED, e.message)
}

/** Spot narration by the AI. */
class AiGuideProducer(private val guides: GuideRepository, private val settings: SettingsRepository) : Producer {
    override val descriptor = BuiltInProducers.AI_GUIDE

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        val spot = request.subject as? PoiSubject ?: return MaterialResult.None
        val s = settings.settings.value
        if (!s.isConfigured) return MaterialResult.Unavailable(Reason.NO_KEY)
        return failing(TAG) {
            // Over the limit, a narration made before still comes from the cache; a new one doesn't.
            val text = try {
                val station = request.context.station
                guides.guideFor(spot.poi, spot.distanceM, station, request.grounding, request.context.walk?.mood?.takeIf { station.moodTone })
            } catch (e: IllegalStateException) {
                if (guides.limitReached()) return MaterialResult.Unavailable(Reason.LIMIT) else throw e
            }
            val basedOn = request.grounding.map { it.producerName }.distinct()
            ready(MaterialType.TEXT, Content.Text(text), descriptor.id, aiCredit(s), basedOn)
        }
    }

    private companion object {
        const val TAG = "AiGuideProducer"
    }
}

/** The walking companion's lines by the AI. */
class AiCompanionProducer(private val companion: WalkCompanion, private val settings: SettingsRepository) : Producer {
    override val descriptor = BuiltInProducers.AI_COMPANION

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        val event = (request.subject as? EventSubject)?.event ?: return MaterialResult.None
        val c = request.context
        return failing(TAG) {
            val line = companion.aiLine(event, c.walk?.session, c.walk?.weather, c.station)
            when {
                line == null -> MaterialResult.Unavailable(if (settings.settings.value.isConfigured) Reason.LIMIT else Reason.NO_KEY)
                line.isBlank() -> MaterialResult.Unavailable(Reason.FAILED, "empty reply")
                else -> ready(MaterialType.TEXT, Content.Text(line), descriptor.id, aiCredit(settings.settings.value))
            }
        }
    }

    private companion object {
        const val TAG = "AiCompanionProducer"
    }
}

/**
 * The fixed lines used without the AI. For a spot, the summary from the map tags when there is no
 * AI set up, and a short "nearby" line when the AI failed (the walk shouldn't wait on a retry).
 */
class FallbackTextProducer(private val prompts: PromptTemplates, private val companion: WalkCompanion) : Producer {
    override val descriptor = BuiltInProducers.FALLBACK_TEXT

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        val text = when (val subject = request.subject) {
            is PoiSubject -> if (request.context.after?.reason == Reason.FAILED) {
                prompts.render(Prompts.Fallback.NEARBY, mapOf("name" to subject.poi.name, "category" to subject.poi.category))
            } else {
                prompts.render(Prompts.Fallback.GUIDE, GuidePrompt.fallbackVars(subject.poi))
            }
            is EventSubject -> companion.fallbackLine(subject.event, request.context.walk?.session)
            else -> return MaterialResult.None
        }
        return ready(MaterialType.TEXT, Content.Text(text), descriptor.id)
    }
}

/** Spot photos from Wikimedia Commons, as the user's photo settings allow. */
class CommonsPhotoProducer(private val photos: SpotPhotos, private val settings: SettingsRepository) : Producer {
    override val descriptor = BuiltInProducers.COMMONS_PHOTO

    override fun peek(request: MaterialRequest): MaterialResult? {
        val spot = request.subject as? PoiSubject ?: return MaterialResult.None
        val s = settings.settings.value
        return when {
            !s.spotPhotos || !photos.hasPhotoHint(spot.poi) -> MaterialResult.None
            photos.isMetered() && !s.photosOnMobileData -> MaterialResult.Unavailable(Reason.METERED)
            else -> null
        }
    }

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        peek(request)?.let { return it }
        val spot = request.subject as PoiSubject
        val photo = photos.photoFor(spot.poi) ?: return MaterialResult.None
        return ready(
            MaterialType.IMAGE, BitmapContent(photo.bitmap), descriptor.id,
            Credit(photo.author, photo.license, source = photo.pageUrl, via = "Wikimedia Commons"),
        )
    }
}

/** Background sounds synthesized on the phone; the channel's `mood.sound` picks one, or the mood does. */
class AmbientProducer : Producer {
    override val descriptor = BuiltInProducers.AMBIENT

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        val mood = request.context.walk?.mood ?: return MaterialResult.None
        val sound = SoundscapeChoice.of(request.context.station.sound, mood)
        return ready(MaterialType.AUDIO, AmbientContent(sound), descriptor.id)
    }
}

/** The mood picture, drawn by the app from the current mood. */
class MoodSceneProducer : Producer {
    override val descriptor = BuiltInProducers.MOOD_SCENE

    override suspend fun produce(request: MaterialRequest): MaterialResult {
        val mood = request.context.walk?.mood ?: return MaterialResult.None
        return ready(MaterialType.IMAGE, SceneContent(mood), descriptor.id)
    }
}
