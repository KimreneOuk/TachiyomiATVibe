package eu.kanade.translation.scheduling

import eu.kanade.translation.persistence.chapter.ChapterTranslationStore

/**
 * resolves the live [ChapterTranslationStore] for a chapter id.
 *
 * [TranslationScheduler] needs to read/write the per-chapter store to reset
 * stranded RUNNING statuses on cancellation and to flip stages during auto
 * scheduling — but it does NOT own the store lifecycle (open/evict/observe),
 * which stays on [eu.kanade.translation.workflow.TranslationManager] because the reader
 * and translator share a single store instance per chapter.
 *
 * Implementations return null when no store is registered for the chapter
 * (e.g. the chapter was never opened, or has already been evicted on chapter
 * change). The scheduler treats null as "nothing to reset" and no-ops.
 */
fun interface TranslationStoreResolver {
    suspend fun resolve(chapterId: Long): ChapterTranslationStore?
}
