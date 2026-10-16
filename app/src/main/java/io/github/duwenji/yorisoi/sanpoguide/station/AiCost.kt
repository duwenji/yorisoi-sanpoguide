package io.github.duwenji.yorisoi.sanpoguide.station

import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel

/**
 * What a channel asks of the user's AI service, shown before it is used (platform ADR-004 H-9,
 * stakeholders.md W-3): how much it talks and how long its narrations are, the most AI calls an
 * hour's walk may take, and whether that is more than the standard channel.
 */
data class AiCost(
    val name: String,
    val talk: TalkLevel,
    val guideLabel: String,
    val maxCallsPerHour: Int,
    val moreThanStandard: Boolean,
    /** The user's daily limit; null for none. */
    val dailyLimit: Int?,
) {
    companion object {
        private const val HOUR_MS = 60 * 60_000L

        /** The companion says nothing unprompted more often than [TalkLevel.minGapMs], spot narrations included. */
        fun maxCallsPerHour(talk: TalkLevel): Int = (HOUR_MS / talk.minGapMs).toInt()

        fun of(channel: Station, standard: Station, dailyLimit: Int?): AiCost = AiCost(
            name = channel.name,
            talk = channel.talkLevel,
            guideLabel = StationLabels.of(channel.guideLength),
            maxCallsPerHour = maxCallsPerHour(channel.talkLevel),
            moreThanStandard = channel.talkLevel > standard.talkLevel || channel.guideLength > standard.guideLength,
            dailyLimit = dailyLimit,
        )
    }
}
