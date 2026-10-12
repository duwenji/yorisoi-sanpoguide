package io.github.duwenji.michizure.kit.mood

import io.github.duwenji.michizure.core.format.SoundChoice
import io.github.duwenji.michizure.kit.voice.Soundscape
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundscapeTest {
    private fun mood(time: TimeOfDay = TimeOfDay.DAY, season: Season = Season.SPRING, sky: Sky? = Sky.CLEAR, place: Place? = null) =
        Mood(time, season, sky, place)

    @Test
    fun `weather comes first`() {
        assertEquals(Soundscape.RAIN, SoundscapeChoice.forMood(mood(sky = Sky.RAIN, place = Place.WATERSIDE)))
        assertEquals(Soundscape.RAIN, SoundscapeChoice.forMood(mood(sky = Sky.THUNDER)))
        assertEquals(Soundscape.WIND, SoundscapeChoice.forMood(mood(sky = Sky.SNOW, place = Place.SHRINE_TEMPLE)))
    }

    @Test
    fun `then the place, then the time and season`() {
        assertEquals(Soundscape.WAVES, SoundscapeChoice.forMood(mood(time = TimeOfDay.NIGHT, place = Place.WATERSIDE)))
        assertEquals(Soundscape.INSECTS, SoundscapeChoice.forMood(mood(time = TimeOfDay.NIGHT, season = Season.AUTUMN, place = Place.PARK)))
        assertEquals(Soundscape.WIND, SoundscapeChoice.forMood(mood(time = TimeOfDay.NIGHT, season = Season.WINTER)))
        assertEquals(Soundscape.TEMPLE, SoundscapeChoice.forMood(mood(place = Place.SHRINE_TEMPLE)))
        assertEquals(Soundscape.BIRDS, SoundscapeChoice.forMood(mood(place = Place.PARK)))
        assertEquals(Soundscape.BIRDS, SoundscapeChoice.forMood(mood(sky = null)))
        assertEquals(Soundscape.WIND, SoundscapeChoice.forMood(mood(place = Place.TOWN)))
        assertEquals(Soundscape.WIND, SoundscapeChoice.forMood(mood(season = Season.WINTER, place = Place.PARK)))
    }

    @Test
    fun `a channel can fix the sound or leave it to the mood`() {
        val rainyTemple = mood(sky = Sky.RAIN, place = Place.SHRINE_TEMPLE)
        assertEquals(Soundscape.RAIN, SoundscapeChoice.of(SoundChoice.AUTO, rainyTemple))
        assertEquals(Soundscape.TEMPLE, SoundscapeChoice.of(SoundChoice.TEMPLE, rainyTemple))
        assertEquals(Soundscape.BIRDS, SoundscapeChoice.of(SoundChoice.BIRDS, mood(time = TimeOfDay.NIGHT)))
    }
}
