---
title: "P1.3b production coverage — results and evidence"
kind: spec
comments: none
---

# P1.3b implementation results

Base: `architecture-cleanup` @ `444b5abf` (reviewed P1.3a commit). Exactly the four manifest paths touched; no new files, no deletions, no worker/store production edits, no generic abstractions. No commits/pushes/subagents; one Gradle owner.

# Exact change

`git diff --numstat` (all four files): **+266 / −120**; `git diff --check` exit 0.

| Path | Δ | Content |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/workflow/ChapterTranslator.kt` | +43 / −32 | Top-level `internal fun selectChapterWave(queue: List<Translation>): List<Translation>` (952→963 lines, +11); the `combine` callback now delegates: `combine(queue.map(Translation::statusFlow)) { selectChapterWave(queue) }` (line 460) |
| `app/src/test/java/eu/kanade/translation/workflow/ChapterTranslatorQueueRestoreTest.kt` | +92 / −0 | 6 wave tests + real-`Translation` fixtures; restore tests retained |
| `app/src/test/java/eu/kanade/translation/pipeline/batch/StandardPipelineCoordinatorTest.kt` | +131 / −0 | 1 direct `StandardLaneWorker.runPhase` X6 test (try/finally fixture cleanup per fixture-finalization ticket); 11 coordinator tests retained |
| `app/src/test/java/eu/kanade/translation/coexistence/BatchParallelWindowBudgetTest.kt` | +0 / −88 | Exactly the two local-mirror tests removed (0 insertions proves the two bitmap-budget tests are byte-unchanged) |

# Extraction body parity (production linkage)

The removed `launchTranslatorJob` lambda body (32 lines) and the new function body were compared after whitespace/comment normalization: **all 25 statements byte-identical modulo indentation**; comments carried verbatim (`A rearmed paused entry…`, `Allow the next queued chapter…`). The sole intended text delta is the lambda tail expression becoming `return selected.asSequence()`. Every filtering (QUEUE/TRANSLATING candidates), ordering (first active stable, `active.take(1)`, first source group), equality (`it !in active` reference/data identity, `it.source == activeSource` identity), source-grouping and capacity (`take(1)`, `take(2)`) decision is unchanged. No status-flow observation, coroutine ownership, dispatch, or cancellation change: the same `combine`/`transformLatest`/`distinctUntilChanged` structure now wraps the delegate call.

# Replaced wave cases (ChapterTranslatorQueueRestoreTest, 4 → 10 tests)

Real `Translation` objects (`HttpSource` via `mockk(relaxed = true)` — same mock instance encodes same source identity, distinct instances distinct sources; `Manga.create()`; `Chapter.create().copy(id, name)`), status set through the flow-backed setter. Selection is never recreated in tests — every case calls `selectChapterWave` directly.

1. `selectChapterWave admits only the first entry of a fresh queue` — old S11 case 1.
2. `selectChapterWave pairs the active chapter with the next queued chapter of its source` — old S11 case 2.
3. `selectChapterWave keeps only the active chapter when the next queued is from another source` — old S11 case 3.
4. `selectChapterWave keeps only the active chapter when no queued entries remain` — old S11 case 4.
5. `selectChapterWave excludes terminal and non-participating statuses` — sanctioned pin: TRANSLATED/ERROR/PAUSED/READY_WITH_WARNINGS/NOT_TRANSLATED never selected.
6. `selectChapterWave admits at most one translating entry even with same-source queued work` — sanctioned pin: second TRANSLATING never re-picked (`!in active`); the same-source queued entry pairs with the first active → `[1, 3]`.

# X6 gap-terminal coverage (StandardPipelineCoordinatorTest, 11 → 12 tests)

`drained standard lane tail pauses on gaps or missing corpus fingerprint and finalizes only a full corpus` runs the REAL `StandardLaneWorker` with a real `StandardLaneWorkerContext` over the file's existing fixtures (`lazyStore`, `ChapterProfileBatchCoordinator.frozenRunConfig`, real `ChapterArtifactEngine`) — no boolean helper, no status copy, no store or worker edits. The typed transport is non-null (CONFIGURATION gate cannot fire) and is a controlled callback fake that completes the single ordered page, so the drain provably reaches the tail: in all four combinations the published records are exactly `[TRANSLATE, TRANSLATE]` with the drained record carrying `pagesTranslated=1` and `preflightStop=1`.

Four fingerprint×gaps combinations, discriminated by a finalizer spy:

- (null, gaps) / (fp, gaps) / (null, no gaps) → `BatchPass1Status.PAUSED`, reason `STOP_REASON`, `completedPageKeys == {p1}`; finalizer calls = 0 after all three.
- (fp, no gaps) → exactly one finalizer call receiving the corpus fingerprint and `pagesTranslated=1`; the returned outcome matches the finalizer's result on asserted fields (`status`, `reason`).

Evidence precision: the `publishRecord`/`record` lambdas are test-local callbacks — the captured records are **in-memory evidence that the worker's control flow reached the drained tail** (and of the record states/counters the worker composed), not durable persistence or artifact publication verification; no record is written to disk in this test. The "finalizer-sentinel" outcome is asserted at field level, not by instance identity.

Interpretation note: the ticket's "small available-checkpoint case" is realized as a one-page, seam-completed drain — the minimal plumbing that still proves tail arrival through captured callback-record and finalizer-spy assertions; the permitted empty-`orderedPages` variant was not needed.

# Validation sequence (fresh compilation via `--rerun` in every run)

| Run | Shell | Exit | Result |
| --- | --- | --- | --- |
| Pre-baseline, 3 classes, DevDebug + StandardDebug | `bdde2cad` | 0 | 19 tests green per variant (wave 4, coordinator 11, budget 4); XML preserved |
| Gate (superseded) | `a27b9ee2` | 1 | compileDevDebugUnitTestKotlin FAILED: 2 errors in my new code (missing `Translation` import; non-suspend local fun calling suspend `runPhase`). Fixed; no test executed |
| DevDebug gate, mirrors still present | `302a4d78` | 0 | BUILD SUCCESSFUL 31s; 26 tests green (wave 10, coordinator 12, budget 4) — replacements proven before mirror deletion; XML preserved |
| Final, mirrors deleted, DevDebug + StandardDebug | `65e50629` | 0 | fresh `compileDevDebugUnitTestKotlin` + `compileStandardDebugUnitTestKotlin`; BUILD SUCCESSFUL 30s/30s; **24 tests / 0 failures per variant** (wave 10, coordinator 12, budget 2); XML preserved |

Per-class count change vs pre-baseline: ChapterTranslatorQueueRestoreTest **4→10 (+6)**; StandardPipelineCoordinatorTest **11→12 (+1)**; BatchParallelWindowBudgetTest **4→2 (−2)**.

Warnings: the two `Condition is always 'true'` notes in ChapterTranslator.kt are pre-existing — present before this slice in run `f8a82d9f` at lines 476/765, now at 487/776 from the +11-line shift of the extraction. Zero new warnings; no warnings reference the three test files.

# Fixture-finalization follow-up (pre-commit cleanup, post Gemini acceptance)

Scope: only the new X6 test. All fixture-dependent work after `lazyStore()` is now enclosed in `try { … } finally { store.closeAndFlush() }` so an assertion failure can no longer leak the store; the copied "flake hardening (diagnosis §4)" comment block was removed from this test (the diagnosis is not this slice's). All assertions, combinations, spies, sibling tests, and production bodies unchanged. Historical runs/logs/XML above are retained untouched.

Recheck (only `StandardPipelineCoordinatorTest`, both variants, `--rerun` fresh test compilation):

| Run | Shell | Exit | Result |
| --- | --- | --- | --- |
| Recheck DevDebug | `a4950e67` | 0 | fresh `compileDevDebugUnitTestKotlin`; BUILD SUCCESSFUL 31s; **12 tests / 0 failures** |
| Recheck StandardDebug | same shell | 0 | fresh `compileStandardDebugUnitTestKotlin`; BUILD SUCCESSFUL 32s; **12 tests / 0 failures** |

The other two classes were unchanged by this follow-up and their P1.3b final checks stand (no re-run, per ticket).

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Actual diff: `scratch/phase-1-cleanup/test-repair/production-coverage/p1.3b-actual.diff` (post-P1.3b, pre-cleanup; historical) and `p1.3b-final-actual.diff` (current tree incl. fixture-finalization)
- Pre-baseline XML: `scratch/phase-1-cleanup/test-repair/production-coverage/pre-baseline/{devdebug,standarddebug}/`
- Gate XML (mirrors present): `scratch/phase-1-cleanup/test-repair/production-coverage/gate-devdebug/`
- Final XML: `scratch/phase-1-cleanup/test-repair/production-coverage/final/{devdebug,standarddebug}/`
- Fixture-finalization recheck XML: `scratch/phase-1-cleanup/test-repair/production-coverage/fixture-finalization/{devdebug,standarddebug}/`
- Run logs: `commands/699de408-00ac-4fe5-a903-b541c23063cc/{bdde2cad,a27b9ee2,302a4d78,65e50629,a4950e67}/output.log`
- User report re-hashed: SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` — byte-identical. `git status`: exactly the four modified files + the untracked report; HEAD `444b5abf`.

# Limits and notes

- Narrow scope: only the three affected classes; final fresh checks in DevDebug + StandardDebug only; release variants untested (test sources are variant-independent, dev/standard differ only in flavor config).
- MockK mock equality is identity-based, which matches the production semantics under test (same source instance = same source); `Translation` data-class equality in `it !in active` is pinned by distinct chapter ids.
- The X6 test drives the seam callback rather than real provider/store translation work; the CONTRACT under test (tail gate + finalizer discrimination) is exercised on the real worker/context — no `StandardLaneWorker`/store code was modified for test convenience.
- The five other Phase 0 failure methods remain untouched and tracked (P1.3a repaired one); no phone/native/model/provider contact; P1.4+ untouched.

# Next

Slice complete; stopping for fresh Gemini/Antigravity review of the actual diff before any further slice.
