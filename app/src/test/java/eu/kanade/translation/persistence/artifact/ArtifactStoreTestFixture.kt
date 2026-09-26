package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal

/** Test-only input shape for artifact manifests; it never models a flat-file read. */
data class ArtifactPageFacts(
    val page: PageTranslation,
    val cleanedFileState: CleanedFileState = CleanedFileState.NONE_RECORDED,
    val cleanedImageDimensions: ProbedImage? = null,
)

enum class CleanedFileState {
    NONE_RECORDED,
    VALID,
    MISSING,
    EMPTY,
    CORRUPT_BYTES,
    DIMENSION_MISMATCH,
}

data class ArtifactSeed(
    val pages: Map<String, ArtifactPageFacts> = emptyMap(),
    val glossary: Map<String, String> = emptyMap(),
    val translationFileCorrupt: Boolean = false,
    val glossaryFileCorrupt: Boolean = false,
    val legacyIdentity: LegacySourceIdentity? = null,
    val sourceFileName: String? = null,
    val glossaryFileName: String? = null,
    val glossaryIdentity: LegacySourceIdentity? = null,
    val migratedByVersionCode: Long = 0L,
    val migratedAtEpochMs: Long = 0L,
)

/** Seeds only the artifact manifest needed by a durability test, then reloads it. */
fun ChapterArtifactEngine.loadArtifact(seed: ArtifactSeed = ArtifactSeed()): ChapterArtifactEngine.LoadResult {
    // Existing/future manifests are the subject of the caller's test. Do not
    // overwrite them with fixture pages; an artifact-only load must preserve
    // the durable document exactly as production does.
    val existing = readManifest()
    var manifest = load().manifest
    if (existing != null || (seed.pages.isEmpty() && seed.glossary.isEmpty())) {
        return ChapterArtifactEngine.LoadResult(manifest)
    }

    val pages = seed.pages.map { (pageKey, facts) ->
        val page = facts.page
        val displayState = when {
            page.isTextlessTerminal -> PageDisplayState.TEXTLESS_COMPLETE
            page.hasRenderedResult -> PageDisplayState.DISPLAY_READY
            else -> PageDisplayState.ORIGINAL_ONLY
        }
        // A seeded page represents an existing artifact-era source baseline,
        // even when its current stage is still in flight. Candidate and
        // checkpoint durability tests rely on that committed ORIGINAL_SOURCE
        // pointer remaining stable while a new generation is active.
        val committed = CommittedBundleMetadata(
            generationId = "fixture-$pageKey",
            displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE),
            origin = ArtifactOrigin.BATCH,
            promotedAtEpochMs = seed.migratedAtEpochMs,
        )
        pageKey to PageArtifactRecord(
            pageKey = pageKey,
            source = SourceIdentity(pageKey = pageKey),
            committed = committed,
            displayState = displayState,
        )
    }.toMap()
    manifest = manifest.copy(
        pages = pages,
        glossary = null,
        updatedAtEpochMs = seed.migratedAtEpochMs,
    )
    if (seed.glossary.isNotEmpty()) {
        val pointer = publishGlossary(seed.glossary)
        manifest = manifest.copy(glossary = pointer)
    }
    check(publishManifest(manifest)) { "artifact test fixture publication failed" }
    return ChapterArtifactEngine.LoadResult(manifest)
}
