package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import kotlinx.serialization.json.decodeFromStream

/**
 * Sidecar re-keying operations extracted from [ChapterArtifactEngine] to stay within file size bounds.
 */
internal fun ChapterArtifactEngine.preparePageSnapshotRekey(
    manifest: ChapterArtifactManifest,
    moves: Map<String, String>,
    livePages: Map<String, PublishedPageTranslation>,
    operationId: String,
): ChapterArtifactEngine.PreparedPageSnapshotRekey? {
    if (moves.isEmpty()) return ChapterArtifactEngine.PreparedPageSnapshotRekey(manifest.pages, emptyMap())
    val movedOldKeys = moves.keys
    val movedNewKeys = moves.values.toSet()
    // Do not merge into an artifact record that is outside this re-key
    // operation. The store's in-memory collision check cannot see dormant
    // manifest entries left by a prior partial lifecycle.
    if (movedNewKeys.any { it in manifest.pages && it !in movedOldKeys }) return null

    val pages = LinkedHashMap<String, PageArtifactRecord>()
    val journalMutations = LinkedHashMap<String, ChapterArtifactEngine.PreparedRekeyJournalMutation>()
    manifest.pages.forEach { (oldKey, record) ->
        val newKey = moves[oldKey] ?: oldKey
        if (newKey in pages) return null
        val isMoved = oldKey in moves
        var candidate = record.candidate
        var committed = record.committed
        var previousCommitted = record.previousCommitted

        if (isMoved) {
            if (candidate != null) {
                val live = livePages[oldKey] ?: return null
                if (live.sourceFileName != oldKey) return null
                val candidateSnapshot = live.toDraft().apply { sourceFileName = newKey }
                val published = publishRekeySnapshot(
                    snapshot = candidateSnapshot,
                    oldKey = oldKey,
                    newKey = newKey,
                    role = "candidate",
                    generationId = candidate.generationId,
                    operationId = operationId,
                ) ?: return null
                candidate = candidate.copy(
                    pageSnapshotFileName = published.fileName,
                    pageSnapshotFingerprint = published.fingerprint,
                )
                journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                    state = published.snapshot.toPublishedPage(),
                    artifactContentHash = published.fingerprint,
                )
            }

            committed = committed?.let { bundle ->
                rekeyCommittedSnapshot(
                    bundle = bundle,
                    oldKey = oldKey,
                    newKey = newKey,
                    role = "committed",
                    operationId = operationId,
                    source = record.source?.copy(pageKey = newKey),
                ) ?: return null
            }
            previousCommitted = previousCommitted?.let { bundle ->
                rekeyCommittedSnapshot(
                    bundle = bundle,
                    oldKey = oldKey,
                    newKey = newKey,
                    role = "previous",
                    operationId = operationId,
                    source = record.source?.copy(pageKey = newKey),
                ) ?: return null
            }

            // A page with no active candidate remains on its committed
            // role. Do not synthesize a candidate or treat a pointerless
            // legacy record as a verifiable completed page.
            if (candidate == null && committed?.pageSnapshotFileName != null) {
                val committedBundle = checkNotNull(committed)
                val snapshot = readPageSnapshot(committedBundle.pageSnapshotFileName) ?: return null
                if (snapshot.sourceFileName != newKey) return null
                val fingerprint = StageFingerprints.pageSnapshot(snapshot)
                val committedFingerprint = committedBundle.translationFingerprint
                if (committedFingerprint != null && committedFingerprint != fingerprint) return null
                journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                    state = snapshot.toPublishedPage(),
                    artifactContentHash = fingerprint,
                )
            }

            if (newKey !in journalMutations) {
                val live = livePages[oldKey] ?: return null
                if (live.sourceFileName != oldKey) return null
                val retryableState = live.toDraft().apply { sourceFileName = newKey }
                // Preserve inventory and the moved page's current logical state, but a
                // pointerless manifest has no artifact proof. The reducer consumes this
                // record as an invalid-page marker until a later verified publication.
                journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                    state = retryableState.toPublishedPage(),
                    artifactContentHash = null,
                )
            }
        }

        pages[newKey] = record.copy(
            pageKey = newKey,
            pageVersion = record.pageVersion + if (isMoved) 1L else 0L,
            source = record.source?.let { source -> if (isMoved) source.copy(pageKey = newKey) else source },
            candidate = candidate,
            committed = committed,
            previousCommitted = previousCommitted,
        )
    }
    // Moves without a manifest page entry still need an explicit retryable mutation when
    // this transaction is published for another moved page in the same operation.
    moves.forEach { (oldKey, newKey) ->
        if (newKey !in journalMutations) {
            val live = livePages[oldKey] ?: return null
            if (live.sourceFileName != oldKey) return null
            val retryableState = live.toDraft().apply { sourceFileName = newKey }
            journalMutations[newKey] = ChapterArtifactEngine.PreparedRekeyJournalMutation(
                state = retryableState.toPublishedPage(),
                artifactContentHash = null,
            )
        }
    }
    return ChapterArtifactEngine.PreparedPageSnapshotRekey(pages, journalMutations)
}

private data class RekeyedSnapshot(
    val fileName: String,
    val fingerprint: String,
    val snapshot: PageTranslation,
)

private fun ChapterArtifactEngine.rekeyCommittedSnapshot(
    bundle: CommittedBundleMetadata,
    oldKey: String,
    newKey: String,
    role: String,
    operationId: String,
    source: SourceIdentity?,
): CommittedBundleMetadata? {
    val oldFileName = bundle.pageSnapshotFileName
    if (oldFileName == null || bundle.translationFingerprint == null) {
        // A pointer or claimed digest without the paired semantic proof is
        // not sufficient to authenticate a completed re-key destination.
        // Keep the committed role metadata, but leave its snapshot retryable.
        val bundleFingerprint = bundle.bundleFingerprint?.let {
            StageFingerprints.committedBundle(
                sourceIdentity = source,
                displayBase = bundle.displayBase,
                translationFingerprint = null,
                layoutFingerprint = bundle.layoutFingerprint,
            )
        }
        return bundle.copy(
            bundleFingerprint = bundleFingerprint,
            translationFingerprint = null,
            pageSnapshotFileName = null,
        )
    }
    val original = readPageSnapshot(oldFileName) ?: return null
    if (original.sourceFileName != oldKey) return null
    val originalFingerprint = StageFingerprints.pageSnapshot(original)
    if (bundle.translationFingerprint != originalFingerprint) return null
    val rewritten = original.detachedCopy().apply { sourceFileName = newKey }
    val published = publishRekeySnapshot(
        snapshot = rewritten,
        oldKey = oldKey,
        newKey = newKey,
        role = role,
        generationId = bundle.generationId,
        operationId = operationId,
    ) ?: return null
    val updatedBundleFingerprint = bundle.bundleFingerprint?.let {
        StageFingerprints.committedBundle(
            sourceIdentity = source,
            displayBase = bundle.displayBase,
            translationFingerprint = published.fingerprint,
            layoutFingerprint = bundle.layoutFingerprint,
        )
    }
    return bundle.copy(
        bundleFingerprint = updatedBundleFingerprint,
        translationFingerprint = published.fingerprint,
        pageSnapshotFileName = published.fileName,
    )
}

private fun ChapterArtifactEngine.publishRekeySnapshot(
    snapshot: PageTranslation,
    oldKey: String,
    newKey: String,
    role: String,
    generationId: String,
    operationId: String,
): RekeyedSnapshot? {
    if (snapshot.sourceFileName != newKey) return null
    val fingerprint = StageFingerprints.pageSnapshot(snapshot)
    val fileName = layout.rekeyedPageSnapshotFile(
        pageKey = newKey,
        role = role,
        generationId = generationId,
        operationId = "$operationId:$oldKey:$newKey:$role:$fingerprint",
    )
    if (!layout.isManagedPath(fileName)) return null
    if (documents.exists(fileName)) {
        // Never overwrite a path that another/stale re-key may already
        // reference. This also makes an exact retry idempotent.
        val existing = io.read(fileName)?.let { bytes ->
            runCatching { documents.json.decodeFromStream<PageTranslation>(bytes.inputStream()) }.getOrNull()
        } ?: return null
        if (existing != snapshot || StageFingerprints.pageSnapshot(existing) != fingerprint) return null
    } else {
        if (!documents.publishJson(fileName, snapshot.detachedCopy())) return null
        val readBack = readPageSnapshot(fileName) ?: return null
        if (readBack != snapshot || StageFingerprints.pageSnapshot(readBack) != fingerprint) return null
    }
    return RekeyedSnapshot(fileName, fingerprint, snapshot.detachedCopy())
}
