package eu.kanade.translation.translator.contextual

import eu.kanade.translation.artifact.StageFingerprints

/**
 * T924 WP3 (pure planner, S4): global OCR corpus manifest assembly.
 *
 * Deterministic, native-free and provider-free assembly of the chapter-wide
 * OCR corpus summary (T924-FP-03) from per-page OCR fingerprints, plus a pure
 * gap/missing-page detector over the expected page set. No IO, no coroutines;
 * the same input collection in any iteration order produces the identical
 * manifest (sorted canonical order, never input order).
 *
 * Ordering follows the never-guess rule for `naturalPageIndex` (schemas
 * contract §1.2): natural order is used only when it is provable, otherwise
 * the manifest falls back to sorted pageKey order and records
 * `naturalOrderProven=false` as an explicit value.
 */

/** One per-page OCR corpus entry (pure input; no IO handles, no payloads). */
data class OcrCorpusPageEntry(
    /** Stable page key (e.g. `0001.jpg` or `p3`). */
    val pageKey: String,
    /**
     * Provable natural page index, or null when unprovable from the key set
     * (never-guess rule, schemas contract §1.2 `naturalPageIndex`).
     */
    val naturalPageIndex: Int?,
    /**
     * The page's semantic content fingerprint
     * ([StageFingerprints.pageOcrContentFingerprint], T924-FP-02).
     */
    val contentFingerprint: String,
    /**
     * Whether this page's OCR evidence is trusted (validated checkpoint with
     * matching provenance). Untrusted pages still count as present but are
     * reported separately so later stages can demand re-validation.
     */
    val trusted: Boolean,
)

/** Pure gap/missing-page diagnostics over the expected page set. */
data class OcrCorpusGaps(
    /**
     * Natural indexes of the expected range `0 until expectedPageCount` that
     * are absent. Empty when natural order is unprovable (indexes cannot be
     * mapped to expectations without order).
     */
    val missingNaturalIndexes: List<Int>,
    /** Natural indexes claimed by more than one present page. */
    val duplicateNaturalIndexes: List<Int>,
    /** Page keys appearing more than once in the input. */
    val duplicatePageKeys: List<String>,
    /** Present pages whose natural index is `>= expectedPageCount`. */
    val beyondExpectedIndexes: List<Int>,
    /** Total present (deduplicated) page entries. */
    val presentPageCount: Int,
    /**
     * Length of the contiguous present prefix `0,1,2,…` of natural indexes;
     * `0` when natural order is unprovable.
     */
    val gapFreePrefixLength: Int,
    /**
     * True when natural order was requested (all entries carried non-null
     * indexes) but could not be proven (duplicates or out-of-range indexes);
     * the manifest then degraded to sorted pageKey order.
     */
    val orderDegraded: Boolean,
    /**
     * True when the corpus needs no repair: no missing pages, no duplicates,
     * present count equals the expected count, and order is proven.
     */
    val isComplete: Boolean,
)

/**
 * The assembled chapter OCR corpus summary (pure planning output).
 *
 * Downstream consumers: analysis chunk planning
 * ([AnalysisChunkPlanner]) and envelope planning ([GlobalEnvelopePlanner])
 * consume [orderedPages], [corpusFingerprint] and [gaps].
 */
data class OcrCorpusManifest(
    /** Whole-corpus identity (T924-FP-03 [StageFingerprints.ocrCorpusFingerprint]). */
    val corpusFingerprint: String,
    /**
     * Canonical page order: natural page order when proven, otherwise sorted
     * pageKey order. This is THE order every downstream windowing uses.
     */
    val orderedPages: List<OcrCorpusPageEntry>,
    /** Whether [orderedPages] is proven natural page order. */
    val naturalOrderProven: Boolean,
    val expectedPageCount: Int,
    val expectedPageCountTrusted: Boolean,
    /** Present pages with `trusted == true`. */
    val trustedPageCount: Int,
    /** Present pages with `trusted == false`. */
    val untrustedPageCount: Int,
    val gaps: OcrCorpusGaps,
) {
    /**
     * `OcrCorpusFingerprint` over a subset of pages of this manifest, taken in
     * [orderedPages] order (schemas contract §1.3: the contributing corpus
     * fingerprint is the corpus fingerprint "over the contributing page set in
     * order"). The subset is treated as its own fully-ordered corpus: trusted
     * expected count, proven order. Unknown page keys are ignored.
     */
    fun contributingFingerprint(pageKeys: Collection<String>): String {
        val wanted = pageKeys.toHashSet()
        val subset = orderedPages.filter { it.pageKey in wanted }
        return StageFingerprints.ocrCorpusFingerprint(
            pages = subset.map { it.pageKey to it.contentFingerprint },
            expectedPageCount = subset.size,
            expectedPageCountTrusted = true,
            naturalOrderProven = true,
        )
    }

    companion object {
        /**
         * Deterministically assemble the corpus manifest. Duplicate page keys
         * are recorded in the gaps diagnostics and deduplicated: the retained
         * entry is the minimum under a total canonical comparator (pageKey,
         * natural index, content fingerprint, trusted) so the outcome never
         * depends on input iteration order. Natural order is proven only when
         * every entry carries a distinct non-null in-range index and there
         * are no duplicate page keys.
         */
        fun assemble(
            pages: Collection<OcrCorpusPageEntry>,
            expectedPageCount: Int,
            expectedPageCountTrusted: Boolean,
        ): OcrCorpusManifest {
            val canonical = compareBy<OcrCorpusPageEntry>(
                { it.pageKey },
                { it.naturalPageIndex ?: Int.MAX_VALUE },
                { it.contentFingerprint },
                { it.trusted },
            )
            val sortedByPageKey = pages.sortedWith(canonical)

            // Duplicate page keys: diagnostics + deterministic dedup.
            val duplicatePageKeys = sortedByPageKey
                .groupBy { it.pageKey }
                .filterValues { it.size > 1 }
                .keys
                .sorted()
            val deduped = sortedByPageKey
                .groupBy { it.pageKey }
                .map { (_, sameKey) -> sameKey.minWith(canonical) }
                .sortedWith(canonical)

            // Natural-order provability (never-guess rule).
            val duplicateIndexes = deduped
                .filter { it.naturalPageIndex != null }
                .groupBy { it.naturalPageIndex }
                .filterValues { it.size > 1 }
                .keys
                .mapNotNull { it }
                .sorted()
            val negativeIndexes = deduped.any { (it.naturalPageIndex ?: 0) < 0 }
            val naturalOrderProven = deduped.all { it.naturalPageIndex != null } &&
                duplicateIndexes.isEmpty() &&
                !negativeIndexes &&
                duplicatePageKeys.isEmpty()
            val orderDegraded = !naturalOrderProven && pages.isNotEmpty()

            val orderedPages =
                if (naturalOrderProven) {
                    deduped.sortedBy { it.naturalPageIndex!! }
                } else {
                    deduped
                }

            // Gap detection over the expected natural range.
            val presentIndexes = deduped.mapNotNull { it.naturalPageIndex }.toSet()
            val missingNaturalIndexes =
                if (naturalOrderProven && expectedPageCount > 0) {
                    (0 until expectedPageCount).filter { it !in presentIndexes }
                } else {
                    emptyList()
                }
            val beyondExpectedIndexes =
                if (naturalOrderProven) {
                    presentIndexes.filter { it >= expectedPageCount }.sorted()
                } else {
                    emptyList()
                }
            val gapFreePrefixLength =
                if (naturalOrderProven) {
                    var prefix = 0
                    while (prefix in presentIndexes) prefix++
                    prefix
                } else {
                    0
                }

            val trustedPageCount = deduped.count { it.trusted }
            val isComplete = !orderDegraded &&
                missingNaturalIndexes.isEmpty() &&
                duplicateIndexes.isEmpty() &&
                duplicatePageKeys.isEmpty() &&
                beyondExpectedIndexes.isEmpty() &&
                deduped.size == expectedPageCount

            val corpusFingerprint = StageFingerprints.ocrCorpusFingerprint(
                pages = orderedPages.map { it.pageKey to it.contentFingerprint },
                expectedPageCount = expectedPageCount,
                expectedPageCountTrusted = expectedPageCountTrusted,
                naturalOrderProven = naturalOrderProven,
            )

            return OcrCorpusManifest(
                corpusFingerprint = corpusFingerprint,
                orderedPages = orderedPages,
                naturalOrderProven = naturalOrderProven,
                expectedPageCount = expectedPageCount,
                expectedPageCountTrusted = expectedPageCountTrusted,
                trustedPageCount = trustedPageCount,
                untrustedPageCount = deduped.size - trustedPageCount,
                gaps = OcrCorpusGaps(
                    missingNaturalIndexes = missingNaturalIndexes,
                    duplicateNaturalIndexes = duplicateIndexes,
                    duplicatePageKeys = duplicatePageKeys,
                    beyondExpectedIndexes = beyondExpectedIndexes,
                    presentPageCount = deduped.size,
                    gapFreePrefixLength = gapFreePrefixLength,
                    orderDegraded = orderDegraded,
                    isComplete = isComplete,
                ),
            )
        }
    }
}
