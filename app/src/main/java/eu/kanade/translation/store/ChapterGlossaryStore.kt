package eu.kanade.translation.store

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.MutationAdmission
import eu.kanade.translation.artifact.ManifestAuthority
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Glossary collaborator moved from `ChapterTranslationStore` (T909 Phase 8).
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

    fun glossarySnapshot(): Map<String, String> = glossary.toMap()

    /**
     * TachiyomiAT T917 D5: the live glossary version for the reuse gate and
     * provenance stamps (phase3-design §1.2/§1.3). `null` means the gate is
     * OFF — a legacy-authority manifest, or no glossary ever published — which
     * keeps glossary-less chapters and the standard engine lane at REUSE with
     * zero extra paid calls. Pure in-memory read (`artifactManifest` is
     * volatile); safe to call with the store mutex already held, which is how
     * the batch provenance stamp consumes it.
     */
    internal fun currentGlossaryVersion(): Int? {
        val manifest = store.artifactManifest
        return if (manifest?.authority == ManifestAuthority.ARTIFACTS) manifest.glossary?.version else null
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

    suspend fun updateGlossary(updated: Map<String, String>) {
        if (store.isDefunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store updateGlossary rejected: store is defunct"
            }
            return
        }
        store.mutex.withLock {
            if (glossary != updated) {
                when (val admission = store.admitMutationLocked()) {
                    MutationAdmission.Granted -> Unit
                    is MutationAdmission.Rejected -> {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT store updateGlossary rejected: " +
                                "code=${admission.code} reason=${admission.message}"
                        }
                        return@withLock
                    }
                }
                glossary = updated.toMap()
                val artifact = store.artifactStore
                val manifest = store.artifactManifest
                if (artifact != null && manifest?.authority == ManifestAuthority.ARTIFACTS) {
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
                } else {
                    glossaryDirty = true
                }
                if (glossaryDirty) store.schedulePersist(markPageDirty = false)
            }
        }
    }

    internal fun loadGlossary() {
        val artifact = store.artifactStore
        val manifest = store.artifactManifest
        if (artifact != null && manifest?.authority == ManifestAuthority.ARTIFACTS) {
            manifest.glossary?.let { pointer ->
                glossary = artifact.readGlossary(pointer)?.entries.orEmpty()
            }
            return
        }
        val documents = store.legacyDocuments() ?: return
        glossary = documents.readValidated<Map<String, String>>(store.glossaryName()) ?: emptyMap()
    }

    internal fun persistGlossaryLocked(): Boolean {
        val artifact = store.artifactStore
        val manifest = store.artifactManifest
        if (artifact != null && manifest?.authority == ManifestAuthority.ARTIFACTS) {
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
