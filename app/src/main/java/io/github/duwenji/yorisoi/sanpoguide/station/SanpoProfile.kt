package io.github.duwenji.yorisoi.sanpoguide.station

import io.github.duwenji.yorisoi.core.format.Activity
import io.github.duwenji.yorisoi.core.format.AppIds
import io.github.duwenji.yorisoi.core.format.AppProfile
import io.github.duwenji.yorisoi.core.format.Feature

/**
 * SanpoGuide as the ecosystem sees it (ECOSYSTEM E-6 ①, API-CHANNEL-PACKAGE G-4): a walking companion that has
 * every slot, trigger and presentation except recorded sound, which comes with app stage 2
 * (API-CHANNEL-PACKAGE M-1; ChannelSync.MEDIA_STAGE).
 */
object SanpoProfile {
    val FEATURES: Set<Feature> = Feature.entries.toSet() - Feature.PRESENT_PLAY - Feature.PRESENT_AMBIENT

    fun of(versionCode: Int) = AppProfile(AppIds.SANPOGUIDE, Activity.WALK, FEATURES, versionCode)
}
