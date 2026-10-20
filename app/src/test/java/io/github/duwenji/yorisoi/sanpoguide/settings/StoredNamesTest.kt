package io.github.duwenji.yorisoi.sanpoguide.settings

import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.sanpoguide.SanpoApp
import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.core.format.GuideLength
import io.github.duwenji.yorisoi.core.format.SoundChoice
import io.github.duwenji.yorisoi.core.format.SpotKind
import io.github.duwenji.yorisoi.core.format.TalkEventKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Names the phone has already stored. Moving these types to other modules or
 * packages must not rename them, or an update loses the user's settings, API keys or
 * notification preferences. The Keystore entry for API keys is pinned in kit-llm (KeyCipherTest).
 */
class StoredNamesTest {
    @Test
    fun `settings store these enum names`() {
        // `ai_route`
        assertEquals(listOf("OPERATOR", "OWN_KEY"), AiRoute.entries.map { it.name })
        // `provider`, `api_key_{name}`, `model_{name}`
        assertEquals(listOf("CLAUDE", "DEEPSEEK", "OPENAI", "GEMINI", "CUSTOM"), Provider.entries.map { it.name })
        // `map_style`
        assertEquals(
            listOf("GSI_STANDARD", "GSI_PALE", "GSI_PHOTO", "GSI_RELIEF", "GOOGLE_ROADMAP", "GOOGLE_SATELLITE"),
            MapStyle.entries.map { it.name },
        )
        // `station_{id}_talk_level`
        assertEquals(listOf("QUIET", "NORMAL", "CHATTY"), TalkLevel.entries.map { it.name })
        // `threshold_{name}`
        assertEquals(
            listOf(
                "WEATHER_LOOKAHEAD_MIN", "WEATHER_REPEAT_MIN", "HEAVY_RAIN_MM_PER_H", "SUNSET_NOTICE_MIN", "SUNSET_LATEST_MIN",
                "TOILET_AFTER_MIN", "TOILET_RADIUS_M", "TOILET_REPEAT_MIN", "DRINK_AFTER_MIN", "DRINK_HOT_C", "DRINK_RADIUS_M",
                "DRINK_REPEAT_MIN", "SEAT_AFTER_MIN", "SEAT_RADIUS_M", "SEAT_REPEAT_MIN", "SHELTER_RADIUS_M", "SHELTER_REPEAT_MIN",
            ),
            Threshold.entries.map { it.name },
        )
    }

    @Test
    fun `channel overrides store channel_json spellings`() {
        // `station_{id}_events`, `_guide_length`, `_sound`, `_prefer` (OverrideCodec)
        assertEquals(listOf("spot", "revisit", "milestone", "rest", "start", "finish"), TalkEventKind.entries.map { it.json })
        assertEquals(listOf("short", "normal", "long"), GuideLength.entries.map { it.json })
        assertEquals(listOf("auto", "rain", "waves", "wind", "birds", "insects", "temple"), SoundChoice.entries.map { it.json })
        assertEquals(
            listOf(
                "shrine", "temple", "church", "worship", "historic", "museum", "gallery", "viewpoint", "artwork", "attraction",
                "park", "garden", "beach", "water", "nature", "restaurant", "cafe", "sweets", "food_shop", "market", "crafts", "shop",
            ).sorted(),
            SpotKind.entries.map { it.json }.sorted(),
        )
    }

    @Test
    fun `notification channels keep their ids`() {
        assertEquals(
            listOf("cue", "walk", "spot", "weather_alert"),
            listOf(SanpoApp.CHANNEL_CUE, SanpoApp.CHANNEL_WALK, SanpoApp.CHANNEL_SPOT, SanpoApp.CHANNEL_ALERT),
        )
    }
}
