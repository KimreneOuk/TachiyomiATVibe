package eu.kanade.translation.persistence.internal

import eu.kanade.translation.engines.translator.contextual.ChapterGlossaryBuilder
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.MutationAdmission
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Glossary collaborator moved from `ChapterTranslationStore` ( Phase 8).
 * Owns the chapter glossary state; all mutations are delegated by the store
 * under the store mutex (the collaborator receives the owning store and locks
 * through it). The LEGACY flat-file read fallback in [loadGlossary] is kept
 * deliberately; the flat-file accessors it uses remain store-side.
 */
internal class ChapterGlossaryStore(private val store: ChapterTranslationStore) {

    // TachiyomiAT: chapter-level term→target glossary for cross-chunk translator
    // continuity. Persisted to sibling JSON (additive; failure degrades to empty).
    @Volatile
    internal var glossary: Map<String, String> = emptyMap()

    internal var glossaryDirty = false

    //  / Task 1.1: per-store accumulator and per-page contribution watermark.
    // Owned under store.mutex.
    internal val stats = ChapterGlossaryBuilder.Stats()
    private var statsSeeded = false
    internal val pageContributions = HashMap<String, List<Pair<String, String>>>()

    fun glossarySnapshot(): Map<String, String> = glossary.toMap()

    /**
     * TachiyomiAT   the live glossary version for the reuse gate and
     * provenance stamps. `null` means the gate is
     * OFF — a legacy-authority manifest, or no glossary ever published — which
     * keeps glossary-less chapters and the standard engine lane at REUSE with
     * zero extra paid calls. Pure in-memory read (`artifactManifest` is
     * volatile); safe to call with the store mutex already held, which is how
     * the batch provenance stamp consumes it.
     */
    internal fun currentGlossaryVersion(): Int? {
        val manifest = store.artifactManifest
        return manifest?.glossary?.version
    }

    /**
     * All translated (source => target) pairs in the chapter so far — used to
     * (re)build the glossary. Reads the in-memory pages map (no I/O).
     */
    fun translatedPairs(): List<Pair<String, String>> =
        store.pages.values.flatMap { page ->
            page.blocks.mapNotNull { block ->
                val s = block.text.trim()
                val t = block.translation.trim()
                if (s.isBlank() || t.isBlank() || t == s) null else s to t
            }
        }

    /**
     * Lazily seeds the accumulator once per store from existing store pages.
     * NEVER seeds from the capped 30-entry glossary map.
     * Must be called under store.mutex.
     */
    private fun ensureAccumulatorSeededLocked() {
        if (statsSeeded) return
        for ((key, page) in store.pages) {
            val existingPairs = page.blocks.mapNotNull { block ->
                val s = block.text.trim()
                val t = block.translation.trim()
                if (s.isBlank() || t.isBlank() || t == s) null else s to t
            }
            if (existingPairs.isNotEmpty()) {
                pageContributions[key] = existingPairs
                stats.addAll(existingPairs)
            }
        }
        statsSeeded = true
    }

    /**
     * Folds a page's translated pairs into the per-store accumulator under store.mutex.
     * Replaces any prior contribution recorded for [pageKey] to prevent duplicate
     * count inflation on retranslation.
     */
    suspend fun foldPageContribution(pageKey: String, pairs: List<Pair<String, String>>) {
        if (store.isDefunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store foldPageContribution rejected: store is defunct"
            }
            return
        }
        store.mutex.withLock {
            ensureAccumulatorSeededLocked()
            val prior = pageContributions[pageKey]
            if (prior != null) {
                stats.removeAll(prior)
            }
            pageContributions[pageKey] = pairs
            stats.addAll(pairs)
            val updated = stats.build()
            updateGlossaryLocked(updated)
        }
    }

    suspend fun updateGlossary(updated: Map<String, String>) {
        if (store.isDefunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store updateGlossary rejected: store is defunct"
            }
            return
        }
        store.mutex.withLock {
            updateGlossaryLocked(updated)
        }
    }

    internal fun updateGlossaryLocked(updated: Map<String, String>) {
        if (glossary != updated) {
            when (val admission = store.admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store updateGlossary rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return
                }
            }
            glossary = updated.toMap()
            if (eu.kanade.translation.persistence.artifact.GroupCommitConfiguration.enabled && store.hasStagedMutations()) {
                store.flushStagedMutationsLocked(eu.kanade.translation.persistence.artifact.CommitPoint.EXPLICIT_FLUSH)
            }
            val artifact = store.artifactEngine
            val manifest = store.artifactManifest
            if (artifact != null && manifest != null) {
                val glossaryWriter = eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry.registerWriter(
                    chapterKey = store.chapterKey,
                    origin = eu.kanade.translation.persistence.chapter.WriterOrigin.GLOSSARY_LANE,
                )
                try {
                    val pointer = artifact.publishGlossary(glossary)
                    if (pointer != null) {
                        val next = manifest.copy(
                            glossary = pointer,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                        if (artifact.publishManifest(next)) {
                            store.artifactManifest = next
                            glossaryDirty = false
                        } else {
                            glossaryDirty = true
                        }
                    } else {
                        glossaryDirty = true
                    }
                } finally {
                    glossaryWriter.close()
                }
            } else {
                glossaryDirty = true
            }
            if (glossaryDirty) store.schedulePersist(markPageDirty = false)
        }
    }

    /**
     * Labeled fallback per  memory contract: recomputes the glossary by streaming all
     * translated pairs in the chapter rather than using the incremental accumulator.
     */
    internal fun streamedRecomputeFallback(): Map<String, String> =
        ChapterGlossaryBuilder.streamedRecompute(translatedPairs())

    internal fun loadGlossary() {
        val artifact = store.artifactEngine
        val manifest = store.artifactManifest
        if (artifact != null && manifest != null) {
            manifest.glossary?.let { pointer ->
                glossary = artifact.readGlossary(pointer)?.entries.orEmpty()
            }
            return
        }
        glossary = emptyMap()
    }

    internal fun persistGlossaryLocked(): Boolean {
        val artifact = store.artifactEngine
        val manifest = store.artifactManifest
        if (artifact != null && manifest != null) {
            val pointer = artifact.publishGlossary(glossary) ?: return false
            val updated = manifest.copy(
                glossary = pointer,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            if (!artifact.publishManifest(updated)) return false
            store.artifactManifest = updated
            return true
        }
        logcat(LogPriority.ERROR) {
            "TachiyomiAT glossary persistence failed: reason=artifact authority unavailable"
        }
        return false
    }
}
