package eu.kanade.translation.persistence.chapter

import java.util.concurrent.atomic.AtomicInteger

/**
 * Consolidated test-seam collaborator (Phase 7 S8, P4 cutover). One counted object owns
 * production-embedded JVM test hooks without carrying test-only members on the facade.
 */
internal class ChapterStoreTestSeams internal constructor(
    private val store: ChapterTranslationStore,
) {
    /** JVM regression seam for the durable-publication/live-map-swap crash boundary. */
    @Volatile
    internal var afterRekeyManifestPublicationBeforeLiveSwap: (() -> Unit)? = null

    private val publishedPageCopyCount = AtomicInteger()

    /**
     * Test-only counter of page publishes: production publishes increment it
     * so read-only-view tests can assert no-copy publication.
     */
    internal val publishedPageCopies: Int
        get() = publishedPageCopyCount.get()

    internal fun countPublishedPageCopy() {
        publishedPageCopyCount.incrementAndGet()
    }

    /** Test seam for verifying that closeAndFlush joins all drain children. */
    internal fun hasActivePersistenceChildren(): Boolean = store.writeDrain.hasActiveChildren()
}
