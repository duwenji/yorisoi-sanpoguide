package io.github.duwenji.yorisoi.sanpoguide.companion

import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChange
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeDetector
import io.github.duwenji.yorisoi.sanpoguide.settings.Threshold
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds

/** A change for the worse coming soon, as the settings set the lookahead and heavy rain (platform kit-geo). */
fun WeatherChangeDetector.detect(weather: Weather, now: Long, limits: Thresholds): WeatherChange? =
    detect(weather, now, limits.ms(Threshold.WEATHER_LOOKAHEAD_MIN), limits[Threshold.HEAVY_RAIN_MM_PER_H])
