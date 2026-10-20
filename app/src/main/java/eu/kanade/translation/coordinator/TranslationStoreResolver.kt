package eu.kanade.translation.coordinator

import eu.kanade.translation.persistence.ChapterTranslationStore

/**
 * resolves the live [ChapterTranslationStore] for a chapter id.
 *
 * Reader and batch coordination need access to the live per-chapter store,
 * while its lifecycle (open, evict, observe) remains owned by
 * [eu.kanade.translation.workflow.TranslationManager]. The reader and
 * translator share a single store instance per chapter.
 *
 * Implementations return null when no store is registered for the chapter
 * (e.g. the chapter was never opened, or has already been evicted on chapter
 * change). Coordinators treat null as "nothing to reset" and no-op.
 */
fun interface TranslationStoreResolver {
    suspend fun resolve(chapterId: Long): ChapterTranslationStore?
}
