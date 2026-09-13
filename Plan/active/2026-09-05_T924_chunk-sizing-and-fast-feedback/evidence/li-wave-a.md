# T924 LI Wave A — evidence report (LI-1, LI-2)

Code worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, HEAD `9baa8aa` (verified clean before work). No commits made, per instructions. All paths below are relative to `app/src/main/java/eu/kanade/translation/` unless noted.

## LI-1 — coordinator-aware completion projection

### Shell + reconciler (flagged COMPLETED post-pass)

- `pipeline/batch/BatchProgressReconciler.kt:127-193` — NEW `reconcileFlaggedCompleted(pageMap, orderedKeys, activeGeneration)`: flagged-lane COMPLETED projection. Every expected page counts done (including pages missing from the live map — the COMPLETE record covers them); `translationStatus == PARTIAL` counts into the partial bucket; `chapterStatus = READY_WITH_WARNINGS` when `partialCount > 0` else `TRANSLATED`; zero stranded pages, zero failures manufactured. Existing `reconcile` untouched.
- `pipeline/batch/BatchChapterTranslator.kt:662` — `var dispatchedFlaggedLane = false` captured by the local `runBatchPass1`.
- `pipeline/batch/BatchChapterTranslator.kt:691-696` — inside `runBatchPass1`, the existing `profilePipelineDispatchKind(...)` result is now bound to `dispatchKind` and stored into `dispatchedFlaggedLane` (PROFILE_PIPELINE → true). Flag-OFF behavior identical (same call, same args).
- `pipeline/batch/BatchChapterTranslator.kt:908-921` — the post-pass (`BatchPass1Status.COMPLETED` branch) now selects the projection via `postPassReconciliation(flaggedLane = dispatchedFlaggedLane, status = COMPLETED, ...)`; the stranded-page sweep that follows is a no-op for the flagged projection (empty `strandedPages`). The non-COMPLETED stop branch (~:878-897, PAUSED/FAILED/PERSISTENCE_REJECTED) keeps the original `reconcile(..., pauseOutcome = ...)` untouched for BOTH lanes.
- `pipeline/batch/BatchChapterTranslator.kt:1046-1066` — NEW internal pure `postPassReconciliation(flaggedLane, status, pageMap, orderedKeys, activeGeneration)`: flagged+COMPLETED → `reconcileFlaggedCompleted`; everything else → existing `reconcile`. This is the extracted, unit-tested decision surface of the post-pass.

### LI-14 fold-in — SKIPPED (deliberate)

The paused projection (`reconcilePaused`) counting translation-committed pages as done was skipped. Two verified reasons: (1) `TranslationBatchProgressTracker.pause` (`TranslationBatchProgressTracker.kt:147-164`) consumes only the `BatchPass1Outcome`, never the reconciliation, so the done-count change has zero user-visible consumer in the pause path today; (2) any predicate parameter on `reconcilePaused` widens the shared legacy reconciler API used by FF-01-OFF paths with no observable benefit — the definition of risking legacy behavior for no gain.

### Durable projector (manga-screen half)

- `store/StoreStatusProjector.kt:81-95` — `artifactStatus()` now consults the run-record authority first: when `manifest.authority == ARTIFACTS` AND `manifest.activeRun != null`, `completedRunRecordStatus(manifest)` runs; any non-null result is returned INSTEAD of the legacy live-page reconcile. Everything else falls through to the existing legacy path unchanged.
- `store/StoreStatusProjector.kt:184-243` — NEW `completedRunRecordStatus`: reads the run record through `store.artifactStore.readRunRecord(pointer)` (missing/unreadable/non-COMPLETE/empty page-record set → null → legacy path). Per page record: done = `PageDisplayProjection.from(record).displayReady` || `isTextless` || `record.candidate != null` || committed-textless display state; partial bucket = `record.translation?.status == PARTIAL` or a live `translationStatus == PARTIAL`; trusted-baseline shortfall counts as unevidenced; ERROR when any expected page is unevidenced, READY_WITH_WARNINGS when partial, else TRANSLATED.

**DEVIATION from spec (important for the next wave):** the spec's done predicate (`PageDisplayProjection.from(record): displayReady or TEXTLESS_COMPLETE`) alone is NOT satisfiable by real flagged-lane manifests today. Verified in-code: `ChapterTranslationStore.persistArtifactMutationLocked` (`ChapterTranslationStore.kt:2034`) promotes a committed bundle ONLY when the page `hasRenderedResult || isTextlessTerminal`, and the flagged lane never renders in-pass — so a healthy flagged COMPLETED chapter has `committed == null` for every translatable page. The translated page snapshot is durably addressable through the OPEN `candidate.pageSnapshotFileName` pointer (that is exactly what a re-opened chapter lazily loads via `getOrLoadPageSnapshot`, `ChapterTranslationStore.kt:2194-2224`). Hence `candidate != null` is counted as done evidence under a COMPLETE run record. Residual: a reset performed by a PRE-FIX build leaves a stale COMPLETE pointer whose candidate snapshots were overwritten with cleared pages; the durable projection then says TRANSLATED until the next dispatch supersedes the run (the fixed build's resets always retire the pointer, so no new such states are created). If the next wave wants stricter behavior, the projector would need per-page snapshot reads (the coordinator gate does them; see LI-2).

## LI-2 — reset retires run identity + COMPLETE fast-path display gate

### CAS retirement transaction

- `artifact/ChapterArtifactStore.kt:376-412` — NEW `retireActiveRun(manifest, reason, nowEpochMs)`: `@Synchronized`, `staleManifestRejection` CAS guard, publishes `manifest.copy(activeRun = null)` in one manifest publication; idempotent (already-null → `Committed` with no publication); rejected on any stale/publication failure with the prior manifest retained. KDoc documents reset semantics ("a user reset means the recorded run must never short-circuit a future dispatch") and retention ownership: `ArtifactRetention.reachablePaths` retains exactly the manifest-pointed `activeRun` sidecar (`artifact/ArtifactRetention.kt:120`), so after retirement the orphaned run-record file is reclaimed by the next reachability sweep (pinned by test).

### Store façade

- `ChapterTranslationStore.kt:2287-2320` — NEW `suspend fun retireActiveRun(reason: String): Boolean`: store-mutex delegation over the artifact transaction, mirroring the `demoteCommittedDisplay` idiom; refreshes the `artifactManifest` façade only on Committed; logs INFO `TachiyomiAT artifact active run retired: ... reason=...` on success, WARN on rejection. ARTIFACTS-authority precondition; defunct store → false.

### Reset controller

- `manager/ChapterDataResetController.kt:266-268` and `:281-283` — both branches of the shared private `resetChapterData` (the ACTIVE-store branch and the persisted-only/open-store branch) call `store.retireActiveRun("chapter data reset")` after the existing per-page transform + `demoteCommittedDisplay` + `flush`. All three public chapter reset paths (`resetChapterTranslationData`, `resetChapterInpaintData`, `resetChapterData`'s generic callers) delegate to this body, so all three retire the run. The per-page reset paths (`resetTranslationData`/`resetInpaintData`/`resetOcrData` single-page) are intentionally untouched (not in scope). `resetOcrData` → `deleteTranslation` deletes the whole manifest, so no retirement is needed there.

### Coordinator COMPLETE gate

- `pipeline/batch/ChapterProfileBatchCoordinator.kt:1680-1725` — the `COMPLETE` branch of `resumeFinalizeOrComplete` is now gated on per-page work-product evidence via the NEW helper `pageWorkProductResolvable` (`:1735-1766`); any page without evidence → INFO log `"TachiyomiAT t924 resume: recorded COMPLETE lacks display evidence; superseding with a fresh run"` and `return null`, so the normal run-start path publishes a fresh RUN_SNAPSHOT (ST-15-style supersession). KDoc of `resumeFinalizeOrComplete` documents the LI-2 semantics as the flag-ON mirror of the flag-OFF F-4 gate.
- `pipeline/batch/ChapterProfileBatchCoordinator.kt:1735-1766` — evidence rule: committed bundle present → evidence; committed/textless `displayState` → evidence; else the candidate snapshot is READ and must still be a translation-terminal page (`hasRenderedResult || isTextlessTerminal || hasRecognizedTranslation`); fallback to live `isTextlessTerminal`.

**DEVIATION from spec (same root cause as above):** the spec's gate predicate (`manifest.pages[pageKey]?.committed != null || live isTextlessTerminal`) breaks two real cases, both verified by running the real coordinator:
1. Healthy in-session re-dispatch: flagged pages have NO committed bundles (see LI-1 deviation), so the strict predicate supersedes every healthy COMPLETE re-dispatch. The pinned test `a durable COMPLETE record under flag ON re-dispatches as a finished outcome with zero work` regressed to PAUSED under the strict predicate; the content-aware gate restores it.
2. Post-reset: `demoteCommittedDisplay` no-ops for flagged chapters (it only acts when an in-session `committedDisplay` entry exists — the flagged lane never promotes one), so the reset leaves the candidate POINTER intact; what changes is the snapshot CONTENT (the reset persists the cleared PENDING page over it). Pointer-presence evidence would pass post-reset; only the snapshot-content check catches it.

Cost note: the gate reads at most one snapshot file per page, only on the COMPLETE fast path (zero paid work scenario). Post-restart re-dispatch stays zero-work (candidate snapshots on disk are translation-terminal). Post-reset re-dispatch (without retirement — e.g. the gate-only path) supersedes and re-derives honestly.

## Tests (RED-first; new files under `app/src/test/java/eu/kanade/translation/`)

### T1 — LI-1 shell+reconciler (`pipeline/batch/BatchPostPassProjectionTest.kt`, new; 4 tests)

Level tested: `reconcileFlaggedCompleted` directly + the pure `postPassReconciliation` selector (the complete decision surface of the shell's post-pass). The full shell was NOT driven: the coexistence harness has no flagged-lane wiring (no FF-01 flag / contextual-AI support), so a real flagged COMPLETED shell run is impractical in this tree; the legacy post-pass path through the real shell is covered by the existing suites staying green (my wiring touches it — `Phase0BatchTranslationCharacterizationTest` etc. pass in the sweep).
- `flagged completed outcome with translatable unrendered pages projects TRANSLATED with zero stranded` — 2 unrendered READY pages + textless + 1 page missing from the map → TRANSLATED, done 3, stranded empty, failed 0.
- `flagged completed outcome with a PARTIAL page projects READY_WITH_WARNINGS` — partialCount 1 → READY_WITH_WARNINGS.
- `the legacy projection still strands flagged-shaped unrendered pages (bug anchor)` — documents the bug via the existing `reconcile` (ERROR + stranded).
- `the post-pass selector picks the flagged projection only for flagged COMPLETED` — flagged+COMPLETED → TRANSLATED/zero stranded; legacy COMPLETED → ERROR/stranded (FF-01-OFF unchanged); flagged+non-COMPLETED → legacy reconcile.
- RED: compile-gated (new API); the bug itself is pinned behaviorally by the `bug anchor` characterization.

### T2 — LI-1 durable (`store/StoreStatusProjectorRunRecordTest.kt`, new; 6 tests)

- `complete run record over committed page records projects TRANSLATED` — RED CONFIRMED: `expected:<TRANSLATED> but was:<READY_WITH_WARNINGS>` (legacy misprojection) → GREEN after fix.
- `page without committed or textless evidence under a COMPLETE record is ERROR` — RED CONFIRMED: `expected:<ERROR> but was:<READY_WITH_WARNINGS>` → GREEN.
- `partial translation stage among committed records yields READY_WITH_WARNINGS`, `trusted baseline shortfall under a COMPLETE record is ERROR` — GREEN (new behavior).
- `no activeRun keeps the existing legacy projection unchanged` and `unreadable run record keeps the existing legacy projection unchanged` — GREEN both before and after (regression guards; both assert the pre-existing READY_WITH_WARNINGS legacy result).

### T3 — LI-2 coordinator (`pipeline/batch/Stage7FinalizeResumeCoordinatorTest.kt`, +1 test)

`a recorded COMPLETE lacking per-page display evidence is superseded by a fresh run (LI-2)` — drives the REAL coordinator: pass 1 → COMPLETE (pins: candidate snapshots addressable); real reset primitives (live page transform + `demoteCommittedDisplay`, pinned: snapshots overwritten with cleared pages, COMPLETE record still owns the chapter); pass 2 must not return the zero-work outcome.
- RED CONFIRMED: pass 2 returned `RESUME_COMPLETE_REASON` ("T924 recorded run already COMPLETE; treated as finished (ST-14 idempotent resume)") — assertion `"…treated as finished…" should not equal "…treated as finished…"` failed at Stage7FinalizeResumeCoordinatorTest.kt:541.
- GREEN: `reason == TRANSLATE_COMPLETE_REASON`, fresh translation requests (same envelope count as pass 1), `activeRunPointer changed` (fresh RUN_SNAPSHOT→COMPLETE publication), final record COMPLETE.

### T4 — LI-2 reset

- `manager/ResetRetiresActiveRunTest.kt` (new; 1 test) — drives the REAL `ChapterDataResetController.resetChapterTranslationData` (all collaborators mocked, real artifact store + store) over a durably published COMPLETE record.
  - RED CONFIRMED (first attempt hit an unstubbed `Manga.id` in the fixture; fixed): `Expected value to be null, but was SidecarPointer(fileName=Chapter 1_artifacts/runs/f-297a….json, …)` — the reset left the run pointer intact. GREEN after fix (pointer null; live pages still demoted to PENDING).
- `artifact/ChapterArtifactStoreRetireActiveRunTest.kt` (new; 3 tests, compile-gated new API so no behavioral RED): `retireActiveRun clears the pointer and is idempotent`; `retireActiveRun rejects a stale manifest snapshot and keeps the pointer` (CAS enforced); `the retired run record sidecar becomes a retention orphan` (sidecar file remains on disk; the next `ArtifactRetention.reconcileRetention` sweep deletes it — verifying the retention-ownership KDoc claim).
- Controller-level was feasible (ChapterDataResetController is lambda-injected, mockk-friendly), so the "document why not" branch was not needed.

## Suite results (worktree `TachiyomiAT-t924-impl`)

RED run (fixes stashed; 3 RED-capable classes): 10 tests, 4 failures (2× T2, 1× T3, 1× T4-fixture → T4 re-run behavioral RED after fixture fix). Output quoted above.

GREEN runs:
- All new tests: `StoreStatusProjectorRunRecordTest` (6), `ResetRetiresActiveRunTest` (1), `BatchPostPassProjectionTest` (4), `ChapterArtifactStoreRetireActiveRunTest` (3), `Stage7FinalizeResumeCoordinatorTest` (3 incl. new) — all pass.
- Mandatory suites: `Stage7FinalizeCoordinatorTest`, `Stage7FinalizeResumeCoordinatorTest`, `BatchDispatchResumeWiringTest`, `OcrPreflightFlagOffMidRunTest`, `ProfilePipelineDispatchGateTest`, `AnalysisChunkValidationTest` — pass.
- Extra suites matching the grep patterns: `BatchProgressReconcilerTest`, `TranslationManagerDeleteResetOrderingTest`, `BatchProgressProjectorDurableReconstructionTest`, `DurableStatusWipeGateTest`, `DurableDocumentMemoTest`, `ChapterTranslationStatusOffMainThreadTest`, `ProfileReconcilerTest`, `P5HonestOutcomeTypingTest`, `ChapterTranslationStoreArtifactMigrationTest` — pass.
- FULL SWEEP `eu.kanade.translation.*`: see final section below (filled after the run).

## Notes for the next wave

1. The flagged lane has NO committed display bundles at COMPLETED — `committed` promotion requires a rendered result (`ChapterTranslationStore.kt:2034`). Anything that keys display/completion evidence on `committed != null` alone (including the existing F-4 shell helper `activeRunPagesDisplayCommitted`) will treat every healthy flagged chapter as unevidenced. The reader-side "committed manifest bundles" model the background describes arrives only at reader adoption / with FF-02 render wiring.
2. `demoteCommittedDisplay` is a conditional no-op (`committedDisplay.containsKey(pageKey)` gate, `ChapterTranslationStore.kt:2274`) — for flagged chapters the reset's manifest cleanup is effectively the live-page persist overwriting the candidate snapshot. If the next wave wants the durable manifest to reflect resets for flagged pages, `demoteLivePage` (or an explicit candidate-clear) should be called unconditionally by the reset.
3. The coordinator's COMPLETE gate now performs per-page snapshot reads (bounded, fast-path only). If that ever matters on hot paths, a `displayState` stamp written at the TX-20 commit would make it I/O-free.
4. LI-14 (paused projection counting translated pages as done) skipped — see above; revisit only if `tracker.pause` ever starts consuming the reconciliation.
5. A hung background probe test run once left a stale Gradle test worker holding `classes.jar` (`FileSystemException ... being used by another process`); killing the worker JVM cleared it. Not a code issue.

*Final sweep results appended below after completion.*

## FINAL SWEEP (completed)

`./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"` — **BUILD SUCCESSFUL, zero failures**: 240 test suites, 1774 tests, 0 failures, 0 skipped (summed from the JUnit XML results under `app/build/test-results/testStandardDebugUnitTest/`).

Worktree state at finish (nothing committed): 7 modified main sources + 1 modified test + 4 new test files (3 under untracked dirs). Diff stat: 446 insertions, 10 deletions across the 8 tracked files.
