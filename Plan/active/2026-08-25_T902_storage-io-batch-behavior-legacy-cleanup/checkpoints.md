# T902 checkpoints

## Phase A — complete

- Implemented A1: moved the live `skipCache=true` download probes in `MangaScreenModel.confirmChapterTranslation` into `screenModelScope`/`withIOContext`, preserving queue-after-download and dispatch behavior.
- Implemented A2: added authority probing, summary-first durable status resolution, registry-owned artifact rehydration, file/chapter store aliasing, cache invalidation, and suspend IO reader reads with LEGACY fallback.
- Implemented A3: replaced destructive flat-file decode cleanup with `.corrupt` quarantine and ERROR logging.
- Applied review fixes: initialized `durableStatusCache` in the Unsafe-based reader teardown helper and cleared it from chapter, translation, inpaint, and OCR reset paths.
- Added artifact restart/reader, LEGACY flat-file, corrupt quarantine, and registry alias tests; retained and compiled the existing DownloadCache renewal guard test.
- Verification: app Kotlin compile PASS; focused Phase A classes PASS (11 tests); `spotlessCheck` PASS; `git diff --check` PASS. The post-fix translation slice ran 934 tests with only the known `AotReportBubbleFillTest` pixel mismatch; the order-dependent `BatchTranslateBlockMergeTest` no longer fails after the Unsafe helper fix.
- Full gate: Spotless and domain release unit tests passed; app standard-debug ran 994 tests with only the known `AotReportBubbleFillTest` pixel mismatch. `BatchTranslateBlockMergeTest` passed after the Unsafe helper fix.
- Final post-review app compile plus Spotless passed after hardening durable-cache initialization ordering.
- Full gate details are recorded in `engineering/phase-a-implementation.md`.

## Phase B — implementation complete, review fixes applied

- Removed summary-sidecar reads/writes from production paths. Artifact status now derives from manifest/page rehydration with additive `expectedPageCount`; LEGACY chapters retain flat-file reads.
- Added artifact-only lazy-store creation, coalesced page registration publication, promotion candidate write reuse, deferred live retention sweeps, atomic glossary persistence, and batch cancellation/close flushing.
- Added non-published durable probe storage with immediate release, bounded orphaned `X_images/` cleanup on chapter open/batch close, and active-reader lease protection.
- Added/extended restart, partial-status, LEGACY, quarantine, trusted/untrusted registration-baseline, artifact-only glossary, registry-probe, concurrent probe-adoption, promotion-dedupe, and retention tests.
- Review fixes: no pages-so-far `TRANSLATED` certification; reader-first baselines are explicitly untrusted; RUNNING/open-candidate pages and missing expected pages are warnings; durable failures remain errors; file-keyed opens take/re-home probes; cleaned-image sweeps protect a 30-second write-to-commit freshness window and apply the 64-file bound after protection filtering.
- Verification: review-fix focused classes 61 tests PASS; requested translation/download slice 949 tests with only known `AotReportBubbleFillTest` pixel mismatch; prior full app standard-debug 999 tests had only that same known mismatch; `spotlessCheck` PASS; prior `:domain:testReleaseUnitTest` 68 tests PASS; `git diff --check` PASS.
- Full details and remaining tradeoffs are recorded in `engineering/phase-b-implementation.md`.

## Phase C1 — implementation complete, pending coordinator review

- Added nullable schema-1 `legacyMigration` metadata with source/glossary identities, preservation intent, migration/version health, deterministic page-key digest, and fail-closed support validation.
- Applied C1 review hardening: source `INTENT`/`PRESERVED`/`DELETED` and glossary `NONE`/`INTENT`/`PRESERVED`/`DELETED` combinations now require coherent names, immutable identities, paired monotonic timestamps, and verification health; malformed metadata is ineligible for cleanup.
- Resync now carries prior migration provenance only when the fresh manifest exposes an exactly matching legacy source identity; absent, unreadable, or mismatched fresh identity clears stale provenance while a valid fresh marker remains eligible.
- Mapped incomplete/retryable/cancelled/textless/corrupt legacy pages to provisional `origin=LEGACY` committed records while preserving non-ready display states and the untrusted expected-page baseline.
- Extended committed snapshot materialization to compatibility pages and sanitized unvalidated cleaned-image names to original-source snapshots.
- Added metadata/digest round-trip and fail-closed tests, corrupt-image materialization coverage, and a schema-1 artifact-only/no-flat-file rehydration fixture.
- Validation: focused C1 migration/store tests PASS (83 tests, 0 failures/errors); `spotlessCheck` PASS; `git diff --check` clean. No commit created.
- Full implementation details are recorded in `engineering/phase-c1-implementation.md`.

## Phase C2 — implementation complete, pending coordinator review

- Implemented serialized lazy per-chapter rescue with a parent/file keyed lock.
  Rescue publishes a LEGACY staging manifest, materializes and re-reads the
  complete page/glossary graph, then performs one final ARTIFACTS authority
  publication. ARTIFACTS reopens never resync or fall back to flat JSON.
- Preserved C1's provisional committed snapshot through candidate open,
  failure, cancellation, and recovery. Added identity-based, bounded,
  collision-safe source/glossary INTENT-to-PRESERVED rename recovery, including
  adoption after a crash/provider failure following rename.
- Hardened atomic backup recovery and corrupt-flat quarantine with no-overwrite
  collision handling. Registry-owned reader/status reads now cache only
  successful non-null results; failed rescue/probe outcomes remain retryable in
  the same manager and successful opens invalidate stale durable status.
- Added restart, partial/in-flight, snapshot-failure, source/glossary
  collision, crash-after-rename, quarantine collision, and same-manager retry
  fixtures. Focused C2 classes pass (64 tests); the translation wildcard run
  completed 963 tests with only the known AotReportBubbleFillTest pixel
  mismatch. `spotlessCheck` and `git diff --check` pass.
- Applied C2 F1/F2 review fixes: SHA-256 plus length now owns source/glossary
  adoption while mtime is diagnostic only; every source, glossary, quarantine,
  and backup move uses the no-replace `RenameResult` primitive. Unsupported
  SAF/URI admission, deterministic race collisions, and bounded exhaustion
  retain the original bytes and return retryable state; no unchecked fallback
  can overwrite an externally inserted destination. Added mtime-change,
  unsupported-backend, source/glossary race, quarantine, and backup-recovery
  fixtures. Review-fix focused classes pass (80 tests); the translation
  wildcard run completed 971 tests with only the known AotReportBubbleFillTest
  pixel mismatch. Final `spotlessCheck` passed and `git diff --check` is clean
  after the report/checkpoint update.
- No C3 verification/deletion, C4 mutation admission/writer removal, or C5
  dead-code deletion was added. Full details are in
  `engineering/phase-c2-implementation.md`.

## Phase C2 F3 — SAF publication fix complete

- Restored a distinct owned-artifact rename path for the store's temp/primary/
  backup atomic protocol. URI-backed `UniFile` publication and rollback use
  `renameOwned`; external legacy/glossary/quarantine/displaced-backup names
  continue to require `renameNoReplace` and fail closed when SAF cannot provide
  no-replace admission.
- Added URI/fake-URI manifest, snapshot, and glossary publication plus owned
  backup recovery/race fixtures. The cumulative artifact focused suite passed
  after this fix; no commit was created.
- Details: `engineering/phase-c2-implementation.md` (F3 section).

## Phase C3 — complete, pending final review

- Added the chapter-open later-version health gate. It requires a complete,
  warning-free ARTIFACTS graph, no active lease/candidate, supported migration
  metadata, and matching page-key baseline before publishing `VERIFIED`.
- Re-read SHA-256/length identities immediately before deleting preserved
  source/glossary files; mismatches, provider failures, or marker failures
  retain recovery data. Cleanup is opened-chapter scoped with no startup scan.
- Cumulative storage-focused tests: 149 PASS; details in
  `engineering/phase-c3-implementation.md`.

## Phase C4 — complete, pending final review

- Added serialized typed mutation admission and applied it before page/stage,
  queue, deletion/rekey, and glossary mutations. Rescue/publication failure
  returns a retryable rejection before new page state is accepted.
- New lazy translator/pipeline storage acquisition is artifact-only; flat page,
  glossary, and summary writes are no longer used. The legacy `fileCreator`
  parameter remains only as an ignored source-compatible seam.
- Details and risks: `engineering/phase-c4-implementation.md`.

## Phase C5 — complete, pending final review

- Removed dead stage-transaction APIs, `tempFileNameFor`, summary helper and
  naming test, and obsolete resync helpers/tests while retaining live candidate
  recovery and legacy rescue/read paths.
- Production/test search is clean for removed definitions/callers. The final
  translation slice has only the known AOT pixel mismatch; full gate evidence
  is recorded in the C5 report.
- Details: `engineering/phase-c5-implementation.md`.

## Final translation-module audit fix — complete, pending narrow re-review

- Fixed F-B1: chapter-wide `deleteTranslation`/`resetChapterOcrData` now
  capture validated authority and migration metadata before the existing
  cancel/join/defunct/stream-clear ordering, remove manifest siblings and the
  artifact tree, identity-check exact legacy source/glossary names, retire
  companion images only after authority removal, and clear durable status.
  Per-page `resetOcrData`/`deletePageTranslation` remain page-scoped.
- Added raw and URI-style deletion failure/identity fixtures. F-M1 now skips
  repeated health verification/manifest rewrites for `VERIFIED` chapters with
  no pending `INTENT`/`PRESERVED` source or glossary state while preserving
  retry for pending cleanup.
- Focused storage/deletion suite: 64 tests PASS. Deterministic translation
  slice: 964 tests with only the known AOT pixel mismatch. Full gate: Spotless
  PASS, domain release tests 68 PASS, app standard-debug 1,024 tests with only
  the same known AOT mismatch; `git diff --check` clean. No commit created.
- Full details: `engineering/final-translation-module-audit-fix.md`.
- Final restart-fixture rerun: `ChapterArtifactDeletionTest` 2/2 PASS;
  `git diff --check` remains clean.
