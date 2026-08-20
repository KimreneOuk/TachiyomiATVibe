# Phase 2 artifact schema checkpoint

## Scope completed

The chapter artifact schema, immutable storage layout, crash-safe document publication, cached model
identities, and deterministic legacy migration are implemented additively under
`app/src/main/java/eu/kanade/translation/artifact/`. The live pipeline and reader behavior are
unchanged: the legacy flat translation file remains the reader's authority until the store
transaction and UI phases switch consumption.

New components:

- `ArtifactContracts.kt` — explicit stage statuses (`ABSENT`, `RUNNING`, `READY`, `TEXTLESS`,
  `SKIPPED`, `FAILED_RETRYABLE`, `FAILED_TERMINAL`, `STALE`, `CORRUPT`, `PARTIAL`), origins
  (`BATCH`, `READER_ADHOC`, `LEGACY`, `UNKNOWN`), failure categories, generation lifecycle,
  display-base kinds, `SourceIdentity` (unprovable components stay null), stage records with
  fingerprint/provenance, and `DurableFailureMetadata` with failure fingerprint.
- `model/PageDisplayState.kt` — the canonical reader/drawer display-state vocabulary
  (`ORIGINAL_ONLY`, `CANDIDATE_RUNNING`, `DISPLAY_READY`, `REFRESHING_WITH_COMMITTED_RESULT`,
  `FAILED_WITH_COMMITTED_RESULT`, `FAILED_NO_RESULT`, `TEXTLESS_COMPLETE`). UI consumers migrate in
  a later phase; Phase 2 records the migration-time initial state.
- `StageFingerprints.kt` — deterministic length-prefixed SHA-256 fingerprints per lifecycle
  contract §§4–8 (detection, OCR, inpaint, translation with context checkpoint + relationship prior
  as inputs, layout, glossary version, failure, committed bundle).
- `ChapterArtifactManifest.kt` — manifest with page records, committed/previous-committed/candidate
  metadata, durable failures, glossary pointer; versioned vocabulary-hints glossary sidecar
  (`KIND_VOCABULARY_HINTS`, never identity evidence); generation records.
- `ChapterArtifactLayout.kt` — sibling `X.manifest.json` beside the legacy `X.json`; all immutable
  payloads under `X_artifacts/{artifacts,images,context,generations,glossary}/…`.
- `ChapterDocumentIo.kt` — path-addressed document IO over `UniFile` plus
  `AtomicChapterDocuments`: write `.tmp`, re-read and validate, rotate current to `.bak`, rename
  over primary. Corrupt primaries are quarantined as `.corrupt` (never deleted) and recovered from
  the retained backup. Migration never mutates the only recoverable copy in place.
- `ChapterArtifactStore.kt` — load-or-migrate (refuses to downgrade a newer-schema manifest),
  crash-safe manifest/glossary publication, durable failure recording, and retention
  reconciliation bounded to committed + candidate + one previous generation using store
  reachability (never filename age); legacy documents are never swept.
- `ModelIdentityCache.kt` — persisted SHA-256 identities for installed detector/OCR/inpaint
  assets, keyed by a `versionMarker:length:lastModified` stamp so no-op scans read cached
  identities instead of rehashing model files.
- `LegacyArtifactMigration.kt` — deterministic mapping of legacy shapes: displayable page with
  current-revision cleaned file → provisional committed bundle (explicit `LEGACY` origin, null
  fingerprints, unknown source identity); recognized-without-display → `ORIGINAL_ONLY` candidate
  data; partial → diagnostics, never promotable; persisted `RUNNING` → retryable candidate;
  missing/empty cleaned file → `CORRUPT` inpaint + `FAILED_NO_RESULT`, never pointing the reader at
  a missing file; stale-revision cleaned output → `STALE`; corrupt record → empty manifest with the
  corrupt legacy file kept verbatim; glossary → versioned vocabulary-hints sidecar.

`ChapterTranslationStore.open()` now snapshots the authoritative legacy bytes once, decodes and
hashes that exact snapshot, and runs migration or identity-aware resync under a process-wide lock.
The additive hook is guarded by `runCatching`, so artifact failures never prevent the legacy store
from opening; chapters without a translation file gain nothing.

## Storage and migration decisions

- The manifest is a sibling document, not a new directory root, matching the existing
  `X.glossary.json` / `X.summary.json` convention; immutable payloads live under `X_artifacts/`
  beside the legacy `X_images/` companion.
- Schema version 1 with `ignoreUnknownKeys` and a newer-schema refusal guard, so later phases can
  extend fields without invalidating this reader and this version can never overwrite a future
  manifest.
- Glossary sidecar versions are immutable files; republishing writes `chapter.glossary.N+1.json`
  and the manifest pointer records the version that actually landed.
- The migration-time display state is recorded but no UI consumes it yet, preserving current
  reader behavior until Phase 3/7 per the plan's rollout gates.

## Validation

- `:app:testStandardDebugUnitTest --tests 'eu.kanade.translation.artifact.*' --tests
  'eu.kanade.translation.ChapterTranslationStoreArtifactMigrationTest'`: BUILD SUCCESSFUL,
  84 tests, 0 failures.
- `:app:spotlessCheck`: BUILD SUCCESSFUL after the cycle-2 formatting corrections.
- `git diff --check`: clean.

## Out of scope (deferred)

Atomic promotion transactions, reader leases, `READER_ADHOC` write provenance in the live path,
the lifecycle planner, context interpretation, and UI migration remain for Phases 3, 4, 6, and 7.
