---
kind: spec
title: "P2.4 chapter-replacement — results and evidence"
comments: none
---

# P2.4 implementation results

Ticket `phase-2-contracts/tickets/chapter-replacement/index.md` on `architecture-cleanup` @ `aef57913` (P2.3 commit). `TranslationManager.cancelRunningChapterForReplace` made suspending — both `runBlocking` bridges removed; direct await-cancel → clear (if active store) → remove-queue order preserved. No fire-and-forget, no compat shim, no timeout/admission change, no store-internals or scheduler-API rewrite; committed/terminal artifacts untouched; absent-chapter guard and no-active-store behavior preserved. Sole implementation/Gradle owner; no commits/pushes/P2.5/source-fingerprint edits/devices/providers/models/subagents; report SHA256 unchanged `EFC11B90…88DDD`.

# Caller/mock refresh (pre-edit, base aef57913)

Exactly one production caller: `MangaScreenModel.confirmReplaceRunningChapter:1311` inside `screenModelScope.launchNonCancellable` (IO) — already a legal suspending caller; **unchanged** (the suspend conversion's compile edge is exercised by every fresh module compile in this slice, both flavors). One doc mention in `ChapterQueuePreflight.kt:20`, one comment in `MangaScreenModel.kt:1817` — both still accurate. No synchronous test stubs of the method existed; `scheduler.cancelPageTranslations` is the awaited leg (suspend, defined at `TranslationManager.kt:2146` delegating to `ReaderTeardownCoordinator`). No other callers.

# Exact change (2 logical files)

| Path | Δ | Content |
| --- | --- | --- |
| `workflow/TranslationManager.kt` | 4/7 | `fun` → `suspend fun`; both `kotlinx.coroutines.runBlocking { … }` wrappers deleted; the three legs now awaited directly in order: `scheduler.cancelPageTranslations(chapterId)` → `activeStores.get(chapterId)?.let { it.clearTransientQueuePages("Replaced by another chapter's batch") }` → `removeFromTranslationQueue(chapter)`. KDoc gains one sentence: "Suspending: awaits each leg in order; callers must invoke from a coroutine." Guard (`?: return` on absent queue entry) unchanged. |
| `test/…/workflow/TranslationManagerDeleteResetOrderingTest.kt` | +109 | 3 new tests in the existing fixture (same mocks, reflective field getter added — no production seams, no harness, no sleeps): see below. Imports: `CompletableDeferred`, `launch`, `runTest` placed ASCII-correct in the kotlinx block. |

All edits via Write/Edit tools and byte-safe shell only; no `Get-Content`/`Set-Content` text rewriting.

# Ordering gates (the actual regression)

1. `cancelRunningChapterForReplace awaits cancel and clear before removing the queue entry` — controlled child `launch`es the call; the scheduler mock logs "cancelPageTranslations", completes `cancelStarted`, then awaits `cancelRelease`; the store mock logs "clearTransientQueuePages", completes `clearStarted`, awaits `clearRelease`. From the TEST body (external verification, never inside a stub): after `cancelStarted.await()`, assert neither clear nor remove has begun; release cancel; after `clearStarted.await()`, assert removal has not begun; release clear; `job.join()`; assert the exact event sequence `["cancelPageTranslations", "clearTransientQueuePages", "removeFromQueue"]`. Because the child is parked inside the awaited cancel while the test observes an empty teardown, this proves the production code actually awaits the suspending leg — an in-stub precheck assertion alone could not.
2. `returns without side effects for an absent queue entry` — empty queue, call returns, zero events.
3. `without an active store still cancels then removes` — empty `ActiveChapterStoreRegistry`; exact sequence `["cancelPageTranslations", "removeFromQueue"]` (no clear leg).

All three run under `runTest` (StandardTestDispatcher, deterministic single-thread interleaving, no sleeps, no wall-clock policy). New resources are joined/complete in-body; nothing outlives the test.

# UI-entry test: skipped with justification

`confirmReplaceRunningChapter` is unchanged 6-line code whose only new requirement (suspend-caller legality) is proven by fresh module compilation in every focused run. No existing MangaScreenModel test exercises the `RunningTranslationConflict` dialog; standing one up would require new bootstrap/dialog-seeding infrastructure the ticket excludes ("reuse the existing fixture … no new production constructor seams/harness"). Recorded for the reviewer as a deliberate omission, not an oversight.

# Validation

| Run | Shell | Exit | Result (executed XML authoritative) |
| --- | --- | --- | --- |
| Pre-baseline | `a0fb5bbf` | 0/0 | 10/0 per flavor fresh (ordering 3, conflict 7) |
| Post | `fb2ddde7` | 0/0 | **13/0 per flavor** fresh compile (ordering 3+3 new, conflict 7 unchanged) — includes the MangaScreenModel suspend-caller compile edge |
| `:app:spotlessCheck` (read-only) | `fb2ddde7` | 1 | **two** files flagged: the known LiteRT debt **and** `pipeline/TranslationPipeline.kt` — see finding below |
| `:app:spotlessCheck` (re-verify after restoration) | `47dfcb26` | 1 | **three** files: LiteRT **+ TranslationUiTruth bracing + TranslationScheduler whole-file CRLF** (initially mis-extracted as "LiteRT only") — finalization authorized |

# Finalization (coordinator-authorized, pre-commit)

After the primary re-read the `47dfcb26` log and found my extraction wrong a second time, a bounded finalization ran:

1. **Whole-file CRLF sweep** — every committed Phase-2-touched file (dab5c124..HEAD) read as bytes; files containing CRLF whose CRLF→LF normalization equals HEAD byte-for-byte were restored to exact HEAD blobs. **10 files** (TranslationScheduler.kt + 9 test files: P5HonestOutcomeTypingTest, RenderPermitReacquisitionTest, P5OutcomeProjectionTest, CancelBatchLeaseSkipTest, CancelSyncStoreWriteTest, RollingAutoCoordinatorRegistrationRaceTest, RollingAutoCoordinatorTest, RollingAutoCoordinatorTraceTest, TranslationSchedulerTraceTest), each with normalized-equality precheck and byte-identity postcheck. All now status-M with **empty diffs** (autocrlf listing artifact only). This restores committed content; the git diff is unchanged by it. Meaningful edits (TranslationManager, ordering test) excluded, and no file with a normalized difference was overwritten.
2. **TranslationUiTruth rejected() bracing** (P2.2 committed file) — `} else when (…) {` wrapped into an explicit `} else { when (…) { … } }` block, exactly the formatter's hunk; zero semantic/text/action change. Diff: 7/5.
3. **Self-code format nits** found by the first finalization spotless run (`86fa1399`, full inventory parsed): `TranslationPackageBoundaryTest` import order (`java.io.File` → last) and one missing blank line in the ordering test. Fixed; diff 1/1 and the ordering test is now **+110**.
4. **Final verified state** — spotless `a5aff896` parsed in full: **exactly one flagged file: LiteRT** (recorded debt; skip authorized). Focused fresh 33-test check (P5OutcomeProjectionTest 20 + ordering 6 + conflict 7) post-fix: **both flavors exit 0** (`86fa1399` pre-fix legs and `a5aff896` post-fix legs both green; XML in `finalization/{dev,std}/xml` and `xml-fix`).

**Miss disposition, recorded per instruction**: my `fb2ddde7` and `47dfcb26` "LiteRT-only" reports were both **partial extractions** (first-file/first-context reads of the spotless log) and are reclassified as missed. Correct claims exist only from `86fa1399` (full-parse) onward. The TranslationPipeline and TranslationScheduler CRLF residues stem from the P2.2 encoding-repair path; the P2.2/P2.3 results artifacts have been corrected accordingly.

# Spotless finding and resolution (disclosed, reviewer-relevant)

The P2.4 run surfaced `pipeline/TranslationPipeline.kt` with a whole-file CRLF violation (`@@ -1,1445 +1,1445 @@`, `-…\r\n`): working-copy residue from the P2.2 encoding-incident repair path (the committed blob at HEAD is LF-normalized; git's autocrlf masked it in every diff). Accounting correction: this file was flagged since the P2.3 run `b1ff47db`, and the P2.2 finalization run `46b0153f` had likewise flagged `RoiPageRecognitionEngine.kt` — both initially missed by an over-narrow log extraction that reported "LiteRT only"; the P2.2 and P2.3 results artifacts have been corrected accordingly (results-only edits). Resolution mirrors the coordinator-authorized P2.2 RoiPage remedy exactly: `git show HEAD:<path>` byte dump via cmd redirection, normalized (CRLF→LF) old-vs-HEAD equality verified TRUE before writing (no meaningful edit lost), exact 68,388-byte blob restored, byte-identity re-verified, path dropped to autocrlf-listing-only status with an empty diff. No source change; no test rerun required (content byte-identical to the already-green base). The second spotless run confirms LiteRT-only remains the only standing violation.

# Limits

- Focused suites only (per ticket); full-suite and parity runs remain the parent's gate.
- The UI-entry test omission above is a documented scope decision for reviewer sign-off.
- LiteRT wrapping debt, ArtifactOpenProbe ledger, JDK/device items: unchanged, deferred.
- The `context-frontier` preparation's remaining selectors were not part of this slice.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Baseline XML: `scratch/phase-2-contracts/chapter-replacement/pre-baseline/{dev,std}/xml`
- Post XML: `scratch/phase-2-contracts/chapter-replacement/post-change/{dev,std}/xml`
- Logs: `commands/…/{a0fb5bbf-804b-4010-9c69-9b1f502a4fa4,fb2ddde7-0cc9-4e6d-9ee5-6c1faa9a1fba,47dfcb26-30cb-4b80-bdf3-c3e6b7fa31d6}/output.log`
- Ticket: `artifacts/phase-2-contracts/tickets/chapter-replacement/index.md`

# Next

Stopped for Gemini/Antigravity actual-diff review. No commits/pushes, no further Phase 2 code until review verdict.
