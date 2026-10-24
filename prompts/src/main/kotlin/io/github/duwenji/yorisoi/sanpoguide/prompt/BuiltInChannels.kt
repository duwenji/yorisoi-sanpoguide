package io.github.duwenji.yorisoi.sanpoguide.prompt

import io.github.duwenji.yorisoi.kit.channel.ResourceChannels

/**
 * The channels that ship with the app, as resources under `sanpoguide/channels/{id}/` in the
 * same layout as a third-party package (read and checked by platform kit-channel). The index of
 * their files is written by the build (`channelIndex` in prompts/build.gradle.kts).
 */
object BuiltInChannels : ResourceChannels(
    resourceDir = "sanpoguide/channels",
    standardId = "standard",
    ids = listOf("standard", "history", "nature", "gourmet", "shopping", "quiet"),
) {
    const val STANDARD = "standard"

    /** In the order the app lists them. */
    val IDS: List<String> get() = ids
}
