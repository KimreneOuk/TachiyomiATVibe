package eu.kanade.translation.context

import eu.kanade.translation.engines.translator.contextual.ChapterGlossaryBuilder
import eu.kanade.translation.engines.translator.contextual.ProfileSubsetMatcher
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.engines.translator.contextual.TranslationPrompts
import eu.kanade.translation.persistence.artifact.ChapterContextSnapshot
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore

enum class LaneCapability {
    MANUAL,
    AUTO,
    PROFILE_BATCH,
    STANDARD_BATCH,
}

data class ContextRequest(
    val pageKeys: List<String>,
    val targetLang: String,
    val sourceLang: String? = null,
    val requestedOutputTokens: Int,
    val profile: TranslationContextChunkPlanner.Profile,
    val laneCapability: LaneCapability,
    val predecessorRange: IntRange? = null,
    val frozenProfile: ChapterTranslationProfile? = null,
    val envelopeSources: List<ProfileSubsetMatcher.EnvelopeSource>? = null,
    val rollingPairs: String? = null,
)

data class PreparedContext(
    val characterAndTermSheet: String,
    val rollingContext: String,
    val selectedTerms: List<Pair<String, String>>,
    val selectedPairs: List<Pair<String, String>>,
    val estimatedContextTokens: Int,
    val budgetDecision: String? = null,
    val omissionReasons: List<String> = emptyList(),
) {
    fun computeRequestContextFingerprint(
        targetLang: String = "",
        sourceLang: String? = null,
        reuseCompatibility: String = "v1",
        glossaryFingerprint: String? = null,
        profileInputFingerprint: String? = null,
    ): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val payload = buildString {
            append("v:1\n")
            append("tLang:").append(targetLang).append('\n')
            append("sLang:").append(sourceLang.orEmpty()).append('\n')
            append("compat:").append(reuseCompatibility).append('\n')
            append("glossaryFp:").append(glossaryFingerprint.orEmpty()).append('\n')
            append("profileFp:").append(profileInputFingerprint.orEmpty()).append('\n')
            append("sheet:").append(characterAndTermSheet).append('\n')
            append("rolling:").append(rollingContext).append('\n')
            selectedTerms.forEach { (s, t) -> append("term:").append(s).append('=').append(t).append('\n') }
            selectedPairs.forEach { (s, t) -> append("pair:").append(s).append('=').append(t).append('\n') }
        }
        val bytes = md.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        val EMPTY = PreparedContext(
            characterAndTermSheet = "",
            rollingContext = "",
            selectedTerms = emptyList(),
            selectedPairs = emptyList(),
            estimatedContextTokens = 0,
            budgetDecision = null,
            omissionReasons = emptyList(),
        )
    }
}

class ChapterContextService(
    val store: ChapterTranslationStore,
) {

    companion object {
        const val TARGET_TERMS_TOKENS = 320
        const val TARGET_SAFEGUARDS_TOKENS = 96
        const val TARGET_PAIRS_TOKENS = 288
        const val TARGET_SCENE_TOKENS = 96
    }

    data class BudgetAllocation(
        val termsTarget: Int,
        val safeguardsTarget: Int,
        val pairsTarget: Int,
        val sceneTarget: Int,
        val termsBudget: Int,
        val safeguardsBudget: Int,
        val pairsBudget: Int,
        val sceneBudget: Int,
    )

    fun computeBudgetAllocation(
        maxBudget: Int,
        termsUsed: Int,
        safeguardsUsed: Int,
        pairsUsed: Int,
    ): BudgetAllocation {
        val tTarget = TARGET_TERMS_TOKENS
        val sTarget = TARGET_SAFEGUARDS_TOKENS
        val pTarget = TARGET_PAIRS_TOKENS
        val scTarget = TARGET_SCENE_TOKENS

        val tBudget = minOf(maxBudget, tTarget)
        val unusedTerms = maxOf(0, tBudget - termsUsed)

        val sBudget = minOf(maxOf(0, maxBudget - termsUsed), sTarget + unusedTerms)
        val unusedSafeguards = maxOf(0, sBudget - safeguardsUsed)

        val pBudget = minOf(maxOf(0, maxBudget - termsUsed - safeguardsUsed), pTarget + unusedSafeguards)
        val unusedPairs = maxOf(0, pBudget - pairsUsed)

        val scBudget = minOf(maxOf(0, maxBudget - termsUsed - safeguardsUsed - pairsUsed), scTarget + unusedPairs)

        return BudgetAllocation(
            termsTarget = tTarget,
            safeguardsTarget = sTarget,
            pairsTarget = pTarget,
            sceneTarget = scTarget,
            termsBudget = tBudget,
            safeguardsBudget = sBudget,
            pairsBudget = pBudget,
            sceneBudget = scBudget,
        )
    }

    fun prepare(request: ContextRequest): PreparedContext {
        if (request.laneCapability == LaneCapability.STANDARD_BATCH) {
            return PreparedContext.EMPTY
        }

        val profile = request.frozenProfile ?: readReusableProfileFromStore()
        val foldedGlossary = store.glossarySnapshot()
        val envelopeSources = request.envelopeSources ?: defaultEnvelopeSources(request.pageKeys)

        var pairLines = request.rollingPairs ?: defaultRollingPairs()
        var subset = if (profile != null) {
            ProfileSubsetMatcher.match(profile, envelopeSources)
        } else {
            ProfileSubsetMatcher.ProfileSubset(emptyList(), emptyList(), false)
        }

        var resolvedLines = if (profile != null) {
            ProfileSubsetMatcher.resolvedEntityLines(profile, pairLines)
        } else {
            emptyList()
        }

        var unresolvedLines = if (profile != null) {
            ProfileSubsetMatcher.unresolvedReferenceLines(profile)
        } else {
            emptyList()
        }

        var includeScenes = profile != null && subset.scenes.isNotEmpty()

        val constraints = TranslationContextChunkPlanner.constraintsFor(request.profile)
        val maxBudget = constraints.maxRollingContextTokens

        val omissions = mutableListOf<String>()

        var sheet = ""
        var rolling = ""

        fun rebuild() {
            if (profile != null) {
                var s = TranslationPrompts.characterAndTermSheetPrefix(subset, includeScenes)
                val additionalGlossary = foldedGlossary.filterKeys { k ->
                    subset.entries.none { it.sourceForm.equals(k, ignoreCase = true) }
                }
                if (additionalGlossary.isNotEmpty()) {
                    s = s.trimEnd() + "\nAdditional chapter terms:\n" + ChapterGlossaryBuilder.formatGlossary(additionalGlossary) + "\n"
                }
                sheet = s
                rolling = TranslationPrompts.profileAwareRollingPrefix(pairLines, resolvedLines, unresolvedLines)
            } else {
                sheet = ChapterGlossaryBuilder.formatGlossary(foldedGlossary)
                rolling = pairLines
            }
        }

        rebuild()

        fun currentSheetTokens(): Int =
            if (sheet.isBlank()) 0 else TranslationContextChunkPlanner.estimateTokens(sheet)

        fun currentRollingTokens(): Int =
            if (rolling.isBlank()) 0 else TranslationContextChunkPlanner.estimateTokens(rolling)

        fun currentContextTokens(): Int = currentSheetTokens() + currentRollingTokens()

        fun pairLineCount(): Int = pairLines.lineSequence().count { it.isNotBlank() }

        // Trimming under budget constraint follows  reverse order:
        // 1. Scene / style dropped first (includeScenes = false)
        if (currentContextTokens() > maxBudget && includeScenes) {
            includeScenes = false
            omissions += "SCENE_DROPPED_FOR_BUDGET"
            rebuild()
        }

        // 2. Pairs dropped second (halve until 1 line, then empty)
        while (currentContextTokens() > maxBudget && pairLineCount() > 1) {
            val keep = (pairLineCount() + 1) / 2
            pairLines = pairLines
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(keep)
                .joinToString("\n")
            omissions += "PAIRS_HALVED_FOR_BUDGET"
            rebuild()
        }
        if (currentContextTokens() > maxBudget && pairLines.isNotBlank()) {
            pairLines = ""
            omissions += "PAIRS_DROPPED_FOR_BUDGET"
            rebuild()
        }

        // 3. Safeguards dropped third (resolvedLines = emptyList(), unresolvedLines = emptyList())
        if (currentContextTokens() > maxBudget && (resolvedLines.isNotEmpty() || unresolvedLines.isNotEmpty())) {
            resolvedLines = emptyList()
            unresolvedLines = emptyList()
            omissions += "SAFEGUARDS_DROPPED_FOR_BUDGET"
            rebuild()
        }

        // 4. Terms / character sheet kept last!
        if (profile != null) {
            while (currentContextTokens() > maxBudget && subset.entries.size > 1) {
                subset = subset.copy(entries = subset.entries.take((subset.entries.size + 1) / 2))
                omissions += "TERMS_HALVED_FOR_BUDGET"
                rebuild()
            }
            if (currentContextTokens() > maxBudget && subset.entries.isNotEmpty()) {
                subset = subset.copy(entries = emptyList())
                omissions += "TERMS_DROPPED_FOR_BUDGET"
                rebuild()
            }
        } else {
            val entries = foldedGlossary.entries.toList()
            var keepCount = entries.size
            while (currentContextTokens() > maxBudget && keepCount > 1) {
                keepCount = (keepCount + 1) / 2
                sheet = ChapterGlossaryBuilder.formatGlossary(entries.take(keepCount).associate { it.key to it.value })
                omissions += "TERMS_HALVED_FOR_BUDGET"
            }
            if (currentContextTokens() > maxBudget) {
                sheet = ""
                omissions += "TERMS_DROPPED_FOR_BUDGET"
            }
        }

        val selectedTerms = mutableListOf<Pair<String, String>>()
        if (profile != null) {
            for (entry in subset.entries) {
                if (entry.kind == ProfileSubsetMatcher.EntryKind.TERM || entry.kind == ProfileSubsetMatcher.EntryKind.ENTITY) {
                    selectedTerms += entry.sourceForm to entry.targetForm
                }
            }
        }
        for ((k, v) in foldedGlossary) {
            if (selectedTerms.none { it.first.equals(k, ignoreCase = true) }) {
                selectedTerms += k to v
            }
        }

        val selectedPairs = pairLines.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("=>")
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toList()

        val estimatedTokens = currentContextTokens()
        val decision = if (omissions.isEmpty()) "FITS_BUDGET" else "TRIMMED: ${omissions.distinct().joinToString(",")}"

        return PreparedContext(
            characterAndTermSheet = sheet,
            rollingContext = rolling,
            selectedTerms = selectedTerms,
            selectedPairs = selectedPairs,
            estimatedContextTokens = estimatedTokens,
            budgetDecision = decision,
            omissionReasons = omissions.distinct(),
        )
    }

    suspend fun submitCommittedOutput(pageKey: String, pairs: List<Pair<String, String>>) {
        store.foldPageContribution(pageKey, pairs)
    }

    private fun readReusableProfileFromStore(): ChapterTranslationProfile? =
        store.readReusableProfile()

    private fun defaultRollingPairs(): String =
        store.translatedPairs()
            .mapNotNull { (src, tgt) ->
                val t = tgt.trim()
                if (t.isBlank() || t == src.trim()) null else "$src => $t"
            }
            .takeLast(TranslationContextChunkPlanner.MAX_ROLLING_PAIRS)
            .joinToString("\n")

    private fun defaultEnvelopeSources(pageKeys: List<String>): List<ProfileSubsetMatcher.EnvelopeSource> {
        val manifestPages = store.artifactEngine?.readManifest()?.pages
        return pageKeys.mapIndexed { index, pageKey ->
            val page = store.pages[pageKey]
            val naturalIndex = manifestPages?.get(pageKey)?.naturalPageIndex ?: index
            val text = page?.blocks?.mapNotNull { it.text.trim().ifEmpty { null } }?.joinToString("\n").orEmpty()
            ProfileSubsetMatcher.EnvelopeSource(
                naturalPageIndex = naturalIndex,
                sourceText = text,
            )
        }
    }

    fun snapshotForDurable(
        targetLang: String,
        sourceLang: String? = null,
        revision: Long = 1L,
    ): ChapterContextSnapshot {
        val prepared = prepare(
            ContextRequest(
                pageKeys = emptyList(),
                targetLang = targetLang,
                sourceLang = sourceLang,
                requestedOutputTokens = 2048,
                profile = TranslationContextChunkPlanner.Profile.DEFAULT,
                laneCapability = LaneCapability.MANUAL,
            ),
        )
        val chapterKey = store.artifactEngine?.layout?.chapterKey ?: "chapter"
        val contentFp = ChapterContextSnapshot.computeContentFingerprint(
            chapterKey = chapterKey,
            targetLang = targetLang,
            sourceLang = sourceLang,
            revision = revision,
            sheet = prepared.characterAndTermSheet,
            rolling = prepared.rollingContext,
        )
        return ChapterContextSnapshot(
            chapterKey = chapterKey,
            targetLang = targetLang,
            sourceLang = sourceLang,
            chapterContextRevision = revision,
            contentFingerprint = contentFp,
            characterAndTermSheet = prepared.characterAndTermSheet,
            rollingContext = prepared.rollingContext,
            selectedTerms = prepared.selectedTerms,
            selectedPairs = prepared.selectedPairs,
            estimatedContextTokens = prepared.estimatedContextTokens,
            budgetDecision = prepared.budgetDecision,
            omissionReasons = prepared.omissionReasons,
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }
}
