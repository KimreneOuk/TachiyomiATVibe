package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.persistence.artifact.BitmapFactoryCleanedImageProbe
import eu.kanade.translation.persistence.artifact.CleanedImageProbe
import eu.kanade.translation.persistence.journal.ChapterJournalSnapshotManager
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Consolidated test-seam collaborator (Phase 7 S8). One counted object owns
 * every production-embedded JVM test hook that used to sit scattered on the
 * [ChapterTranslationStore] facade and its companion, so the facade carries no
 * test-only members. Production code reads a seam only where a test needs to
 * observe or gate a real transition ([ChapterBulkMutator] reads
 * [afterRekeyManifestPublicationBeforeLiveSwap]); everything else is write-only
 * injection delegated to the owning collaborator.
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

    /** Injects a virtual-storage writer before the first page write in JVM tests. */
    internal fun attachJournalWriter(writer: ChapterJournalWriter) =
        store.journalBridge.attachJournalWriterForTests(writer)

    internal fun attachJournalSnapshotManager(manager: ChapterJournalSnapshotManager) =
        store.journalBridge.attachJournalSnapshotManagerForTests(manager)

    /** Test seam for verifying that closeAndFlush joins all drain children. */
    internal fun hasActivePersistenceChildren(): Boolean = store.writeDrain.hasActiveChildren()

    companion object {
        /** Artifact cleaned-image probe retained for artifact validation tests. */
        internal var artifactImageProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe
    }
}
