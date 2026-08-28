# T902 Phase B implementation

## Interpretation

The Director's amendment removes the summary sidecar from the durable status
contract. `X.summary.json` is neither read nor published by production paths;
the existing summary helper remains only as deferred Phase C cleanup. Artifact
status is derived from the manifest and rehydrated page state. A manifest
`expectedPageCount` baseline is captured at batch pre-registration or on the
first durable reader write and is never reduced. A batch baseline is marked
trusted; a reader-first pages-seen baseline is retained for diagnostics but is
never used to certify completion. The glossary remains the only
sidecar: batch updates persist at committed chunk boundaries and are flushed at
successful completion, cancellation, and close; reader/manual single-page
cadence is unchanged.

## Changes

- `ChapterTranslationStore.kt:1061-1105,1210-1265,1640-1685` batches pending
  page registrations into the next durable artifact publication, records a
  trusted batch `expectedPageCount` or an explicitly untrusted reader-first
  count, and derives status with `BatchProgressReconciler`. Missing expected
  pages and interrupted/in-flight work map to `READY_WITH_WARNINGS`; only
  durable failure records map to `ERROR`.
  Artifact-only lazy stores use their artifact parent/name and do not create a
  flat compatibility file. `ChapterArtifactManifest.kt:22` carries the
  additive baseline, and `LegacyArtifactMigration.kt` preserves the maximum
  baseline during resync.
- `TranslationManager.kt:469-505,626-651` removes summary-based status and
  reader reads, routes ARTIFACTS chapters through registry-backed rehydration,
  keeps LEGACY flat decoding, quarantines corrupt flat files, and releases
  durable probe stores immediately unless an active reader/batch adopted one.
  `ActiveChapterStoreRegistry.kt:60-171` keeps probes in a non-published map;
  both chapter-keyed and file-keyed opens take and re-home probes under the
  shared opening lock.
- `ChapterArtifactStore.kt:360-463` avoids re-publishing an identical live
  candidate snapshot during promotion while retaining candidate/committed
  atomic publication order. Live promote/demote/delete paths no longer sweep
  retention; recovery/load and dead stage-transaction APIs retain their
  existing sweeps. `ChapterTranslationStore.kt:1839-1866` and
  `TranslationPipeline.kt:2736-2743` provide serialized close/batch cleanup.
- `ChapterTranslationSummaryStore.kt` now uses the atomic document helper for
  its deferred compatibility implementation, but has no production callers.
  Batch-end and reader-path summary writes were removed from
  `TranslationPipeline.kt`; batch glossary updates remain at chunk boundaries,
  while the batch `finally` flushes dirty glossary/page state under
  `NonCancellable`.
- `TranslationManager.kt:918-948` adds a bounded (64 files per sweep)
  `X_images/` orphan cleanup on chapter open and batch close. It preserves
  names reachable from live/committed/retired state and names with active
  reader leases. `TranslationStreamRegistry.kt:286-300` supplies the chapter
  lease count.

## Tests added or extended

- `TranslationManagerArtifactReadTest`: ARTIFACTS restart status/page
  rehydration without a summary, partial warning derivation, LEGACY flat
  compatibility, and corrupt-file quarantine.
- `ChapterTranslationStoreArtifactMigrationTest`: expected-page registration,
  artifact-only lazy glossary persistence without a flat/summary file, and
  reader-first baseline capture; existing migration/recovery cases remain.
- `ChapterArtifactStoreTest`: identical candidate promotion write reuse and
  deferred retention sweep behavior.
- `ActiveChapterStoreRegistryTest`: probe eviction, chapter/file adoption, and
  concurrent file-open adoption under the shared opening lock.

## Verification

- Review-fix focused run: 61 tests, 0 failures (`TranslationManagerArtifactReadTest` 6,
  `ChapterTranslationStoreArtifactMigrationTest` 18,
  `ActiveChapterStoreRegistryTest` 7, `ChapterArtifactStoreTest` 30).
- Requested translation/download slice after the review fixes: 949 tests, with only the known
  `AotReportBubbleFillTest` pixel mismatch.
- Prior full app standard-debug result: 999 tests, 1 failure, exactly the same
  known `AotReportBubbleFillTest` mismatch; no errors.
- `spotlessCheck`: PASS after the review-fix changes.
- Prior `:domain:testReleaseUnitTest`: PASS (68 tests, 0 failures).
- `git diff --check`: PASS after the review-fix changes.

## Tradeoffs and remaining risks

- B2 deliberately batches registration records and removes the promotion
  duplicate, but does not introduce a manifest journal; subsequent durable page
  mutations still publish full manifests so fencing and crash recovery remain
  unchanged. A journal/high-watermark design is future work.
- Existing `X.summary.json` files are ignored and not physically removed until
  Phase C. The summary helper and legacy migration/write compatibility remain
  temporarily so already-produced LEGACY chapters can be rescued.
- Probe stores are promptly released and not exposed through active snapshots;
  a store adopted by a reader/batch remains active. The registry is not an LRU
  because normal probes close after resolution and active stores have explicit
  lifecycle ownership.
- The bounded image sweep may leave more than 64 stale files for a later sweep;
  it never deletes a referenced or actively leased image. Legacy flat-file
  creation remains in compatibility-shaped writer fallbacks until the planned
  Phase C migration boundary.
- The additive `expectedPageCountTrusted` bit is required because a reader can
  only know pages seen so far. Reader-first baselines are persisted but remain
  warning-only until a batch supplies the complete ordered total.
- R4 is resolved per Director decision: missing expected pages and interrupted
  or dequeued incomplete batches remain `READY_WITH_WARNINGS`; a manifest
  durable failure or terminal failed page remains `ERROR`.
- R6 (manifest-only status fast path) remains a Phase C performance follow-up;
  current probe opens are bounded by `durableStatusCache` and released after
  resolution.
