package eu.kanade.translation.batch

/**
 * Pure planner for the Phase 6 page-prefix commit inside a request envelope
 * (contract §9): validate completed pages in natural order, commit the longest
 * gap-free prefix, and stop at the first page that structurally refused or
 * carries an invalid context delta. Pages after the stop never promote from
 * that response and retry from the preceding trusted checkpoint.
 */
object ScenePrefixPlanner {

    data class PageCommit(
        val pageKey: String,
        val delta: SceneContextDelta?,
    )

    data class PageStop(
        val pageKey: String,
        val reason: String,
    )

    data class PrefixPlan(
        /** Committable prefix in natural order. */
        val commits: List<PageCommit>,
        /** First invalid page and why the prefix stopped, if it stopped. */
        val stop: PageStop?,
    ) {
        val stopPageKey: String? get() = stop?.pageKey
        val stopReason: String? get() = stop?.reason
    }

    fun plan(
        /** Completed pages in natural order (already sorted by the caller). */
        orderedPages: List<String>,
        /** Raw page-scoped delta text per page key; missing means the provider sent none. */
        deltas: Map<String, String>,
        /** pageKey -> protocol page id (e.g. "p0007"). */
        pageIds: Map<String, String>,
        /** Citation universe: this envelope's request IDs plus prior committed turns. */
        knownBlockIds: Set<String>,
        /** Pages whose accepted output contains in-protocol refusal prose. */
        refusalPages: Set<String>,
    ): PrefixPlan {
        val commits = mutableListOf<PageCommit>()
        for (pageKey in orderedPages) {
            if (pageKey in refusalPages) {
                return PrefixPlan(commits.toList(), PageStop(pageKey, "provider refusal"))
            }
            val parse = SceneContextDeltaParser.parse(
                deltas[pageIds[pageKey] ?: ""],
                knownBlockIds,
            )
            if (!parse.isValid) {
                val reason = "invalid context delta (${parse.errors.firstOrNull() ?: "parse failed"})"
                return PrefixPlan(commits.toList(), PageStop(pageKey, reason))
            }
            commits += PageCommit(pageKey, parse.delta)
        }
        return PrefixPlan(commits.toList(), null)
    }
}
