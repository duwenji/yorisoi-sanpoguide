package io.github.duwenji.yorisoi.sanpoguide.companion

object PlanSuggestions {
    fun recommend(candidates: List<SpotCandidate>, themeCategory: String?): List<SpotCandidate> {
        val ranked = if (themeCategory.isNullOrBlank()) {
            candidates.sortedBy { it.distanceM }
        } else {
            SpotChooser.rank(
                candidates = candidates,
                prefer = setOf(themeCategory),
                skip = emptySet(),
                newSpots = true,
                revisits = true,
            )
        }
        return ranked.take(MAX_SUGGESTIONS)
    }

    private const val MAX_SUGGESTIONS = 3
}
