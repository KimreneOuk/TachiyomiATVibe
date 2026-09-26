package eu.kanade.translation.context

import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.FactProvenance
import eu.kanade.translation.persistence.artifact.RunConfigSnapshot
import eu.kanade.translation.persistence.artifact.StageFingerprints
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries the most recently frozen [ChapterTranslationProfile] across chapters
 * in one series. A later chapter may reuse it only when its language pair,
 * provider family, and token budget remain compatible.
 */
object SeriesProfileRegistry {

    data class CarriedOverProfile(
        val seriesKey: String,
        val profile: ChapterTranslationProfile,
        val sourceLang: String,
        val targetLang: String,
        val providerKey: String,
        val registeredAtEpochMs: Long = System.currentTimeMillis(),
    )

    private val registry = ConcurrentHashMap<String, CarriedOverProfile>()

    fun register(
        seriesKey: String,
        profile: ChapterTranslationProfile,
        sourceLang: String,
        targetLang: String,
        providerKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (!profile.isSemanticallyValid) return
        if (profile.entities.isEmpty() && profile.terms.isEmpty()) return

        registry[seriesKey] = CarriedOverProfile(
            seriesKey = seriesKey,
            profile = profile,
            sourceLang = sourceLang,
            targetLang = targetLang,
            providerKey = providerKey,
            registeredAtEpochMs = nowEpochMs,
        )
    }

    fun get(seriesKey: String): CarriedOverProfile? = registry[seriesKey]

    fun remove(seriesKey: String): CarriedOverProfile? = registry.remove(seriesKey)

    fun clear() {
        registry.clear()
    }

    /**
     * Drift-gating check:
     * 1. Source and target languages must match.
     * 2. Provider engine backend family must match (e.g. lm_studio does not mix with gemini).
     * 3. Profile must be semantically valid.
     * 4. Facts count and character/token size must respect 8k token budgets.
     */
    fun isDriftSafe(
        carried: CarriedOverProfile,
        currentConfig: RunConfigSnapshot,
    ): Boolean {
        if (!carried.sourceLang.equals(currentConfig.sourceLang, ignoreCase = true)) {
            return false
        }
        if (!carried.targetLang.equals(currentConfig.targetLang, ignoreCase = true)) {
            return false
        }

        val carriedEngine = carried.providerKey.substringBefore(':').trim().lowercase(Locale.ROOT)
        val currentEngine = currentConfig.providerKey.substringBefore(':').trim().lowercase(Locale.ROOT)
        if (carriedEngine != currentEngine) {
            return false
        }

        val profile = carried.profile
        if (!profile.isSemanticallyValid) {
            return false
        }

        val totalFacts = profile.entities.size + profile.terms.size
        if (totalFacts !in 1..256) {
            return false
        }

        // Budget check under 8k rules: character length of canonical forms must fit context allowance
        val totalChars = profile.entities.sumOf { (it.canonicalSourceForm?.length ?: 0) + (it.canonicalTargetForm?.length ?: 0) } +
            profile.terms.sumOf { (it.canonicalSourceForm?.length ?: 0) + (it.canonicalTargetForm?.length ?: 0) }
        if (totalChars > 4096) {
            return false
        }

        return true
    }

    /**
     * Adopts a carried-over profile for a new chapter run:
     * - Provenance of facts updated to [FactProvenance.SERIES_CANON].
     * - Scenes are cleared (scenes are chapter-specific narrative ranges).
     * - Candidates and unresolved facts cleared.
     * - New [sourceRunId] and [profileInputFingerprint] stamped.
     * - Validated and re-hashed [contentFingerprint].
     */
    fun adoptForChapter(
        carried: CarriedOverProfile,
        runId: String,
        profileInputFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ChapterTranslationProfile {
        val base = carried.profile
        val adoptedEntities = base.entities.map { it.copy(provenance = FactProvenance.SERIES_CANON) }
        val adoptedTerms = base.terms.map { it.copy(provenance = FactProvenance.SERIES_CANON) }

        val draft = base.copy(
            version = 1,
            sourceRunId = runId,
            profileInputFingerprint = profileInputFingerprint,
            contentFingerprint = "",
            entities = adoptedEntities,
            terms = adoptedTerms,
            scenes = emptyList(),
            unresolvedFacts = emptyList(),
            seriesUpdateCandidates = emptyList(),
            correctionCandidates = emptyList(),
            frozenAtEpochMs = nowEpochMs,
        )

        val finalProfile = draft.copy(
            contentFingerprint = StageFingerprints.profileContentFingerprint(draft),
        )

        check(finalProfile.isSemanticallyValid) {
            "Adopted series profile failed validation: ${finalProfile.validationError()}"
        }
        return finalProfile
    }
}
