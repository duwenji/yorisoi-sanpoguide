package io.github.duwenji.yorisoi.sanpoguide.companion

import io.github.duwenji.yorisoi.kit.geo.weather.ForecastSlot
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeDetector
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeKind
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherCodes
import io.github.duwenji.yorisoi.sanpoguide.settings.Threshold
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The weather turns the walk warns about, as the settings set them (the detection itself is kit-geo's). */
class WeatherChangeDetectorTest {
    private val now = 10_000_000_000L
    private val minute = 60_000L

    private fun forecast(vararg codes: Int, precipitation: Double = 0.0) =
        codes.mapIndexed { i, code -> ForecastSlot(now + (i + 1) * 15 * minute, code, precipitation) }

    private fun weather(code: Int, forecast: List<ForecastSlot>) =
        Weather(WeatherCodes.describe(code), 20.0, 0.0, code = code, forecast = forecast)

    @Test
    fun `lookahead and heavy rain come from the settings`() {
        val later = weather(3, forecast(3, 3, 3, 3, 3, 61))
        assertNull(WeatherChangeDetector.detect(later, now, Thresholds()))
        val longer = Thresholds().with(Threshold.WEATHER_LOOKAHEAD_MIN, 90)
        assertEquals(WeatherChangeKind.RAIN, WeatherChangeDetector.detect(later, now, longer)!!.kind)

        // 1.5mm in 15 minutes is 6mm/h: heavy only with a lower setting.
        val moderate = weather(61, forecast(63, precipitation = 1.5))
        assertNull(WeatherChangeDetector.detect(moderate, now, Thresholds()))
        val sensitive = Thresholds().with(Threshold.HEAVY_RAIN_MM_PER_H, 5)
        assertEquals(WeatherChangeKind.HEAVY_RAIN, WeatherChangeDetector.detect(moderate, now, sensitive)!!.kind)
    }
}
