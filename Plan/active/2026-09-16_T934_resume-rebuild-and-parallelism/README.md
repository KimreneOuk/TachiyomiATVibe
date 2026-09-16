# T934 — Resume-rebuild cost, lease aborts, phase-truth UI, inpaint parallelism

## Director request (2026-09-16)

1. "The rebuilding takes way too long" — resume/rebuild after process death must
   stop re-hashing and re-adopting the whole chapter.
2. Golden-standard storage research accepted (SQLite-WAL / snapshot+log /
   trust-on-load): record digests at write; open must be O(1); verify lazily.
3. Batch translation UI: simplified default with optional advanced view; a
   phase-aware status ("Rebuilding pipeline…", "Restoring N pages…") in the
   sheet AND the reader bottom bar; never a frozen "Batch X/Y" during rebuild.
4. Inpaint must not wait for the same page's translation — it depends only on
   detection/OCR (verified: `StageFingerprints.inpaint` has no translation
   input; the current gate is `OverlapScheduler.nextInpaintCandidate` ~:287).

## Base state

- Repo: this worktree (`orchestrate_execution_order_v3`), detached HEAD
  `8010961`, ~33 uncommitted entries (prior session's E-fixes + in-flight
  milestone tail). W0 must baseline this before any lane edits.
- Build: `gradlew.bat`, JAVA_HOME from `_build.bat`
  (`C:\Program Files\Android\Android Studio\jbr`).
- Tests: `:app:testStandardDebugUnitTest` (plain JVM). Suite was 1944 green at
  GATE P1.

## Verified evidence base (debate + code verification, 2026-09-16)

- Lease model: ONE slot per page per origin; same-origin re-acquire returns the
  same token; new token only on empty slot (`store/PageStageLeaseTable.kt`).
  Envelope `finally` releases the BATCH slot while overlap-inpaint identities
  still ride it (`ProfileEnvelopeExecutor.kt:416-418`, `OverlapScheduler.kt:331-343`)
  → next acquire mints T2 → inpaint admission rejects "page lease token changed"
  → candidate abort + wasted pass (observed 2026-09-16 pages 047/052).
- Write-gate heal fence: refresh only when generation AND token match;
  token/generation mismatch must keep rejecting (T917 manual-ownership fence,
  `BatchWriteGate.kt:107-126`). Any heal must prove ownership via re-acquire,
  never via bare snapshot re-arm.
- Resume cost: run start re-hashes ALL sources (`ChapterProfileBatchCoordinator.kt:268`
  sourceShaByPageKey, `:301` orderedSourceDigest, `:309` runId match) and walks
  per-page checkpoint adoption (`:408-457`, sidecar read + mergeOcr transaction
  each). OCR reuse fingerprints are NOT build-volatile (`PageDecode.kt:154-158`
  uses enum names; checkpoint reuse is source-sha content identity, `:3245-3256`)
  — the re-OCR cliff is adoption-path failure, currently untyped (single WARN at
  ~`:455`).
- UI: sheet opens only from MangaScreen; `TranslationProgressSheet.kt:471-480`
  self-resets "Resuming…"; `BottomReaderBar.kt:68-90` hardcodes copy and freezes
  at "Batch X/Y" during rebuild; four surfaces re-derive status copy; the truth
  layer is `TranslationUiTruth`. `TranslationBatchPhase` already has
  FINALIZING/FINISHED — do NOT add a competing enum.
- Storage: per-chapter JSON sidecar fleet (manifest + page snapshots + OCR
  checkpoints + run records) + hand-built group commit (T930) + page-cache fast
  path — the anti-pattern per the accepted standards; images correctly stay
  files.

## Tracks

### W0 — Baseline stewardship (Main Leader only)
Classify + test + commit the 33 dirty entries on a new branch
`t934/resume-rebuild-and-parallelism`. No lane starts before this lands.

### R1 — Lease abort/re-work fix (wave 1)
Files (allowlist): `pipeline/batch/BatchWriteGate.kt`,
`pipeline/batch/ProfileEnvelopeExecutor.kt`, `store/PageStageLeaseTable.kt`,
`ChapterTranslationStore.kt` (lease helpers only, if needed), tests:
`BatchWriteGateHealTest.kt` (extend) + new release-window test.
1. Owner-proof heal: on lease-token-mismatch reject for a batch write, attempt
   `tryAcquirePageStageLease(pageKey, stage, BATCH)`. Denied → today's typed
   contention yield. Granted AND generation + candidateGenerationId match the
   cached identity → refresh identity to the granted token, retry ONCE.
   Never heal from a bare snapshot; never heal an absent-lease reject.
2. Flip-source fix: the envelope completion path must not release a page whose
   write identity another batch component still holds (refcount the shared
   BATCH slot across envelope/overlap, or scope the `finally` release).
3. T917 fence: existing fence semantics and assertions unchanged and green.

### U — Phase truth + simplified UI (wave 1)
Files (allowlist): `presentation/manga/components/TranslationProgressSheet.kt`,
`translation/model/TranslationProgressSnapshot.kt`, `translation/manager/BatchProgressProjector.kt`,
`presentation/reader/appbars/BottomReaderBar.kt`, `translation/ui/TranslationUiTruth.kt`,
i18n string resources for NEW copy only, tests for truth/projection.
1. Phase model: extend the existing projection with a rebuild/restore phase +
   payload (restored/remaining counts) derived from run-record states +
   counters. No competing enum; no weakening of existing truth assertions.
2. Sheet: Simple default = hero + ready chip + progress bar (indeterminate
   during rebuild) + ONE status line + one-line failure notice when failures>0
   + actions. Advanced (chevron, persisted) = stage cards, page grid, failure
   groups, queue detail.
3. Reader bar: consume the same phase truth; show rebuild/restore states.
4. Resuming state: snapshot-driven (holds until phase leaves rebuild/IDLE);
   delete the synchronous self-reset.

### R2a — Write-time digests + typed adoption failures (wave 2, after U)
Files: `pipeline/batch/ChapterProfileBatchCoordinator.kt`, manifest/store
digest fields, `artifact/ChapterRunRecord.kt` counters, tests.
1. Record per-page source SHA-256 durably at first admission; run start uses
   recorded digests (no whole-chapter re-hash); lazy verification where a page
   is consumed; genuine mismatch still fails closed.
2. Typed adoption-failure reasons (NO_POINTER, SIDE_CAR_UNREADABLE,
   SHA_MISMATCH, LEASE_DENIED, MERGE_REJECTED, BUNDLE_MISSING) in WARN + run
   record counters.
3. D5/D6/D9/D10/D11 assertions untouched.

### R2c — Consolidation spike (wave 1, read-only)
Report only: size (A) consolidated per-chapter resume snapshot vs (B) Room/
SQLite chapter-state store. File touchpoints, T930/T933 seam risk, test
impact, migration order, recommendation. No code.

### I — Inpaint decoupling (wave 2, after R1)
Files: `pipeline/batch/OverlapScheduler.kt`, scheduler tests.
Relax the translation-terminal gate: candidate when the page's OCR is final,
regardless of translation status. Keep "no inpaint during OCR preflight";
keep bitmap-budget concurrency caps; display promotion gate unchanged.

## Standing constraints

- Subagents: no git-mutating commands, no Gradle runs (Main Leader builds,
  tests, commits — single committer/builder).
- No net test assertion weakened or deleted; semantics-preserving conversions
  only, documented in the lane report.
- Reader stability and normal-manga behavior must not regress; bounded memory
  (6 GB devices); Android 8+.
- Lane reports go to `team/<lane>.md`; Main Leader verifies against
  CHECKLIST.md with real test output before any commit.

## Verification model

Every checklist item is verified by the Main Leader: diff review + targeted
test run + evidence recorded in CHECKLIST.md. Lane commits happen only after
the lane's items pass. Full suite at each wave gate.
