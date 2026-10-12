package io.github.duwenji.michizure.kit.mood

import io.github.duwenji.michizure.core.format.SoundChoice
import io.github.duwenji.michizure.kit.voice.Soundscape

/** Which [Soundscape] plays: the channel's choice, or one that fits the mood. */
object SoundscapeChoice {
    /** The channel's choice: a fixed sound, or [AUTO][SoundChoice.AUTO] to follow the mood. */
    fun of(choice: SoundChoice, mood: Mood): Soundscape = when (choice) {
        SoundChoice.AUTO -> forMood(mood)
        SoundChoice.RAIN -> Soundscape.RAIN
        SoundChoice.WAVES -> Soundscape.WAVES
        SoundChoice.WIND -> Soundscape.WIND
        SoundChoice.BIRDS -> Soundscape.BIRDS
        SoundChoice.INSECTS -> Soundscape.INSECTS
        SoundChoice.TEMPLE -> Soundscape.TEMPLE
    }

    /**
     * The sound for [mood]: the weather first (rain sounds while it rains), then the place,
     * then the time and season. Thunder plays as plain rain: a made-up rumble could hide or
     * be mistaken for the real thing.
     */
    fun forMood(mood: Mood): Soundscape {
        val night = mood.time.isDark
        val insectSeason = mood.season == Season.SUMMER || mood.season == Season.AUTUMN
        return when {
            mood.sky == Sky.RAIN || mood.sky == Sky.THUNDER -> Soundscape.RAIN
            mood.sky == Sky.SNOW -> Soundscape.WIND
            mood.place == Place.WATERSIDE -> Soundscape.WAVES
            night -> if (insectSeason) Soundscape.INSECTS else Soundscape.WIND
            mood.place == Place.SHRINE_TEMPLE -> Soundscape.TEMPLE
            mood.season == Season.WINTER -> Soundscape.WIND
            mood.place == Place.TOWN -> Soundscape.WIND
            else -> Soundscape.BIRDS
        }
    }
}
