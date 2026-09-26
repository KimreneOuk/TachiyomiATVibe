package eu.kanade.translation.persistence.internal

import eu.kanade.translation.engines.translator.contextual.ChapterGlossaryBuilder
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.MutationAdmission
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Owns chapter glossary state. Mutations go through the store mutex. The flat
 * file fallback in [loadGlossary] remains so chapters written by older app
 * versions can still be opened.
 */
internal class ChapterGlossaryStore(private val store: ChapterTranslationStore) {

    // Keeps terms consistent across translated chunks. It is persisted as a
    // sibling artifact; failure leaves translation usable without added context.
    @Volatile
    internal var glossary: Map<String, String> = emptyMap()

    internal var glossaryDirty = false

    // One accumulator per store and a contribution watermark per page. Both
    // are protected by store.mutex.
    internal val stats = ChapterGlossaryBuilder.Stats()
    private var statsSeeded = false
    internal val pageContributions = HashMap<String, List<Pair<String, String>>>()

    fun glossarySnapshot(): Map<String, String> = glossary.toMap()

    /**
     * Current glossary version for the reuse gate and provenance stamps.
     * `null` means reuse gating is off because the manifest is not
     * artifact-authoritative or no glossary has been published. This is an
     * in-memory read; `artifactManifest` is volatile, so it is safe to call
     * while the store mutex is already held.
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
     * Rebuilds the glossary from current translated pairs when the incremental
     * accumulator cannot be used.
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
