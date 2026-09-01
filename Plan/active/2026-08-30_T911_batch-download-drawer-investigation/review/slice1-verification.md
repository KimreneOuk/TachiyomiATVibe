# T911 Slice 1 — independent verification (Reviewer pass)

Date: 2026-08-30. Branch `t911/repair`, uncommitted working tree reviewed
against `IMPLEMENTATION.md` "Slice 1" (the acceptance contract). All claims
below were checked against live source and a fresh test run, not the
implementer's report.

Classification per `docs/roles/reviewer.md`: VERIFIED = confirmed in code with
file:line; CONCERN = real but does not violate the slice contract's normative
text; all findings include evidence.

## Contract item 1 — Drawer opens on confirmation: PASS

- VERIFIED: the only assignment of `Dialog.TranslationProgress` in the codebase
  is `openTranslationProgressDrawer` (`MangaScreenModel.kt:888`, helper at
  :885-889). It is invoked from (a) `ChapterTranslationAction.DETAILS`
  (:913) and (b) `confirmChapterTranslation` (:1044), synchronously on the UI
  thread immediately after `acknowledgeTranslationRequests` +
  `updateTranslationRequests` (:1036-1039) and before the async download probe
  (`screenModelScope.launch` at :1045).
- VERIFIED: no downloader/translation callback performs navigation. There are
  zero `Dialog.` references under `app/src/main/java/eu/kanade/translation/`;
  the translation subsystem files are untouched by this diff (git status: 5
  modified files, none in the translation subsystem).
- VERIFIED (multi-select coherence): the bottom bar loops selected items
  through the single-item START handler
  (`presentation/manga/MangaScreen.kt:579-583`). Each iteration now ends in
  `openTranslationProgressDrawer(item)`, so the drawer ends up selected for the
  last-selected chapter and stays coherent (no crash, no lost dialog). The
  deeper multi-select defects (group overwrite, same-source eviction) are
  pre-existing and correctly deferred to Slice 2 item 5.
- VERIFIED: the drawer's own Resume and the cancel-Undo snackbar
  (:942-948) re-enter `confirmChapterTranslation`, re-selecting the same
  dialog — visual no-op when already open.
- Test evidence: `MangaScreenModelTranslationDrawerTest` orders 1/2/5 assert
  `successState().dialog == Dialog.TranslationProgress(chapterId)` after real
  `confirmChapterTranslation` / DETAILS calls (not smoke).

## Contract item 2 — Download phase joined into the projection: PASS

- VERIFIED read-only: `activeDownloadFor(chapterId)`
  (`MangaScreenModel.kt:867-868`) is a pure lookup via
  `DownloadManager.getQueuedDownloadOrNull` (`DownloadManager.kt:103-105`,
  `queueState.value.find { ... }`). No mutation of downloader state.
  `BatchProgressProjector` and `TranslationManager` are untouched by the diff;
  `BatchHeroProjection` imports only the `Download` model class, not the
  manager — no TranslationManager→DownloadManager dependency exists.
- VERIFIED live while the drawer is open: item `downloadState`/`downloadProgress`
  are kept fresh by the existing lifecycle-gated `statusFlow`/`progressFlow`
  collectors (`MangaScreenModel.kt:500-521`) via `updateDownloadState`
  (:648-660); the drawer branch re-reads `activeDownloadFor` on every
  recomposition (`ui/manga/MangaScreen.kt:295-302`), so `downloadedImages` /
  `pages.size` advance with the same state updates that drive the row's
  download ring.
- VERIFIED coverage of the contract's phase list, from
  `BatchHeroProjection.kt:116-137` (`Download.State` enum is fully covered,
  `Download.kt:66-72`):
  - queued / not-started download → indeterminate `WAITING_FOR_DOWNLOAD`
    ("Waiting for download") (:134-135);
  - downloading n% → `DOWNLOADING` with `fraction = progress/100` plus real
    downloaded-page count `x/y` (:122-128);
  - download finished but translation not yet admitted → `PREPARING`
    (finalization/rekey/handoff window) (:133);
  - download ERROR → explicit `DOWNLOAD_FAILED` error phase, not `0/0`
    (:129-130); request-phase `DOWNLOAD_FAILED` also maps there (:118-119).
    Error downloads are retained in the queue, so the state stays visible.
- Accepted deviation (documented by the implementer): the join lives in a pure
  UI-projection fed by the manga screen's already-collected download state
  instead of inside `BatchProgressProjector`. This avoids coupling the
  translation subsystem to the downloader and delivers the contract's required
  observable behavior. No objection.

## Contract item 3 — Unknown totals never render as 0/0: PASS (hero), 2 CONCERNS

- VERIFIED hero: `BatchHeroProjection.of` returns `Numeric` only when real
  translation totals exist (`totalPages > 0 || totalStages > 0 ||
  pages.isNotEmpty()`, `BatchHeroProjection.kt:87-107`); every other path
  returns a `Phase`. The sheet's Phase branch renders only the phase label plus
  an optional download page-count line gated on non-null download pages
  (`TranslationProgressSheet.kt:237-250`) and an indeterminate bar (or a real
  download-percent bar); error phases render no bar (:304-323). The numeric
  "Page x of y" / percent are unreachable without real totals.
- VERIFIED zero-page failure distinctness: `isErrorState && !hasRealTotals` →
  `FAILED_NO_PAGES` error phase (:95-97), distinct from `COMPLETED` for
  terminal-without-totals-without-failures (:111-113). Both are unit-tested
  (`BatchHeroProjectionTest`: "real zero-page failure stays a distinct error
  phase", "terminal batch ... completed without numeric zero").
- VERIFIED call sites: there is exactly one `TranslationProgressSheet(`
  call site in the app (`ui/manga/MangaScreen.kt:296`) and it passes the join.
  `BatchHeroProjection` has no other consumers.
- CONCERN A (pre-existing, not introduced): `LivePipelineGrid` renders
  unconditionally (`TranslationProgressSheet.kt:336`); while totals are
  unknown, each of the four stage cards shows "0 / 1" (total coerced via
  `coerceAtLeast(1)` at :594) and "0%" (:725, :731) — fake numeric progress
  directly under a correct phase hero, during exactly the waiting/preparing
  phases this slice targets. Not literally `0/0`, and the contract's normative
  sentence is hero-scoped ("the sheet hero shows the phase ... with no fake
  percentage or page count"), so this does not fail the contract — but it
  partially preserves the Director's misleading-zero complaint inside the same
  drawer.
- CONCERN B (pre-existing, narrow window): header subtitle
  `batchStatusHeaderSubtitle` can render "Translating pages (0/0)"
  (:890) in the FIRST_PASS window where a tracker exists, no stage is active
  yet, and totals are still 0 (pre-registration race).

## Contract item 4 — Snapshot retention: PASS, 1 CONCERN

- VERIFIED write path: every canonical emission is remembered before it
  reaches the item (`MangaScreenModel.kt:600`); a null emission retains the
  previous snapshot (`ChapterTranslationSnapshotRegistry.kt:25-27`).
- VERIFIED rebuild coverage: all full rebuilds flow through
  `toChapterListItems` — the init `combine` over manga DB + `downloadCache.changes`
  + `downloadManager.queueState` + `translationManager.queueState` +
  `pendingTranslationRequests` (`MangaScreenModel.kt:184-197`) and the initial
  load (:230-233) — and both append `.carryingTranslationSnapshots`
  (:714), which fills `translationProgress = null` gaps from the registry and
  never overwrites an item that already carries a live value
  (`ChapterTranslationSnapshotRegistry.kt:45-53`).
- VERIFIED terminal retention: `stopTranslationProgress` cancels only the
  collector job, not the registry entry (:589-594); the post-terminal rebuild
  reads the terminal snapshot back. The fixture test proves the collector is
  really cancelled (a post-cancellation canonical emission does not reach the
  item) and the rebuild still restores the terminal snapshot
  (`MangaScreenModelTranslationDrawerTest` order 4).
- VERIFIED eviction and scoping: explicit reset/delete forgets the snapshot
  (:989-990). The registry is a per-screen-model `HashMap` — one compact
  record per chapter of this screen, no static references, dies with the
  screen model; cross-manga staleness is impossible by construction. Registry
  boundedness/forget/null-key are unit-tested.
- CONCERN (thread-safety, low impact): the registry doc says "Main-thread
  only", but `remember` runs on Main (via `withUIContext`, :584), while
  `carryingTranslationSnapshots` reads run on `Dispatchers.IO` (init collect in
  `launchIO`, :183) and `forget` runs on IO (`launchNonCancellable`) — a plain
  `HashMap` accessed cross-thread. This follows the pre-existing pattern of the
  adjacent `translationProgressJobs` map (:555, mutated from the same rebuild
  path since before this slice). Realistic worst case is a transient missed
  backfill (self-heals on the next emission), not a crash on JDK 8+ HashMap.
  Cheap hardening: declare the map `ConcurrentHashMap` and fix the doc.

## Contract item 5 — DETAILS reachable from all states: PASS

- VERIFIED routing table `translationIndicatorTapAction`
  (`ChapterTranslationIndicator.kt:60-73`) is an exhaustive `when` over all 7
  `Translation.State` values (enum confirmed at
  `Translation.kt:34-46`): pending request → DETAILS; QUEUE, TRANSLATING,
  PAUSED, TRANSLATED, READY_WITH_WARNINGS, ERROR → DETAILS; only
  NOT_TRANSLATED without a pending request → START.
- VERIFIED wiring: PendingTranslationIndicator (covers pending/accepted/
  waiting-for-download/download-failed request phases) taps → DETAILS (:150);
  TranslatingIndicator (QUEUE/TRANSLATING/PAUSED) taps → DETAILS (:241);
  TranslatedIndicator (TRANSLATED/READY_WITH_WARNINGS) taps → routing →
  DETAILS, retranslate/delete moved to long-press (:326-334); ErrorIndicator
  taps → routing → DETAILS, retry moved to long-press (:378-386).
- Test evidence: `ChapterTranslationIndicatorRoutingTest` covers all 7 states
  (4 test methods). No Compose harness exists in the repo (verified: no
  `createComposeRule`, no ui-test dependency), so pure-logic coverage is the
  honest maximum for this slice.

## Contract item 6 — Scope discipline: PASS

- VERIFIED working tree contains exactly 5 modified files (manga screen/model,
  sheet, indicator, i18n base strings) and 6 new files (2 main: projection +
  registry; 4 test suites). No persistence schema, coordinator protocol,
  downloader, or pipeline changes.
- VERIFIED normal-download non-regression by inspection: the only behavioral
  additions to shared paths are the read-only `activeDownloadFor` accessor and
  the registry backfill, which is a no-op for chapters without translation
  snapshots (empty registry). START/CANCEL/reset flows otherwise unchanged;
  `toChapterListItems` visibility change (private→internal) is test-only.
- Slice 2/3 boundaries respected: download-cancel request transition (Slice 2
  item 3), request generations, multi-select group API, startup reconciler,
  durable terminal reconstruction are all untouched and correctly listed in the
  implementation report.

## Contract item 7 — Test honesty and re-run: PASS

Test quality (read in full):
- `BatchHeroProjectionTest` (16): asserts phase-vs-numeric selection and, for
  every unknown-total phase, asserts the `Phase` variant with `null` fraction /
  `null` page counts — a structural absence of `0%`/`0/0`, not smoke. Covers
  the full download join matrix, zero-page failure distinctness, and the
  fresh-open (empty snapshot → ACCEPTED) case.
- `ChapterTranslationSnapshotRegistryTest` (7): retention, null-retain, forget,
  rebuild-backfill without new emission, terminal survival, no-overwrite of
  live item, boundedness.
- `MangaScreenModelTranslationDrawerTest` (5): real screen-model fixture with
  resumed lifecycle; asserts dialog equality after confirm and DETAILS, live
  emission → item copy, rebuild without new canonical emission → retained,
  terminal status through the real `statusFlow` → collector cancelled
  (post-cancel emission provably suppressed) → rebuild retains terminal
  snapshot, and WAITING acknowledgement reaches the item with the drawer still
  selectable. Assertions are behavioral, not smoke. Caveat (documented in the
  KDoc): PER_CLASS ordered tests share one model instance.
- The drawer-open assertion is state-level (dialog selection), not Compose UI —
  acceptable given the repo has no Compose test harness (verified).

Re-run by reviewer (same focused command, exit code 0, BUILD SUCCESSFUL):

| Suite | Tests | Failures | Errors |
| --- | --- | --- | --- |
| eu.kanade.translation.model.BatchHeroProjectionTest | 16 | 0 | 0 |
| eu.kanade.tachiyomi.ui.manga.ChapterTranslationSnapshotRegistryTest | 7 | 0 | 0 |
| eu.kanade.presentation.manga.components.ChapterTranslationIndicatorRoutingTest | 4 | 0 | 0 |
| eu.kanade.tachiyomi.ui.manga.MangaScreenModelTranslationDrawerTest | 5 | 0 | 0 |
| eu.kanade.tachiyomi.ui.manga.EnqueueTranslationDownloadsTest | 3 | 0 | 0 |
| eu.kanade.translation.TranslationManagerPendingAcknowledgementTest | 4 | 0 | 0 |
| eu.kanade.translation.TranslationManagerDownloadFailureRecoveryTest | 5 | 0 | 0 |
| eu.kanade.translation.TranslationPendingRequestStoreTest | 5 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTest | 8 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistryTest | 4 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.BatchProgressReconcilerTest | 2 | 0 | 0 |
| eu.kanade.translation.pipeline.batch.TranslationBatchProgressReducerTest | 3 | 0 | 0 |
| eu.kanade.translation.model.TranslationUiProjectionTest | 3 | 0 | 0 |
| eu.kanade.translation.model.TranslationProgressTest | 7 | 0 | 0 |

Total: 14 suites, 76 tests, 0 failures, 0 errors — matches the implementer's
claimed counts exactly (counts taken from
`app/build/test-results/testDevDebugUnitTest/TEST-*.xml`).

## Contract item 8 — Strings / i18n: PASS

- VERIFIED: 9 new `manga_batch_phase_*` strings added to
  `i18n-at/src/commonMain/moko-resources/base/strings.xml:215-225`; all 9 are
  referenced from the sheet (hero labels + status pill).
- VERIFIED placeholder correctness: `manga_batch_phase_downloading` is
  `"Downloading %1$d%%"` — one Int positional arg plus a literal-percent
  escape — matching its single-Int `stringResource` call
  (`TranslationProgressSheet.kt:836-837`). `manga_batch_page_progress`
  (`Page %1$d of %2$d`, strings.xml:165) is always called with two Int args.
- VERIFIED no missing-translation crash path: `i18n-at` has only the `base`
  locale, so every locale resolves from base by construction. App main sources
  referencing the new `ATMR.strings` entries compiled in the reviewer's test
  run, so codegen/resolution is intact.

## Findings summary

| # | Severity | Type | Finding |
| --- | --- | --- | --- |
| 1 | LOW | defect (cosmetic, pre-existing) | Pipeline grid renders "0 / 1" + "0%" per stage card while totals are unknown (TranslationProgressSheet.kt:336,594,725,731) — recommend gating on real totals as slice-1 polish or folding into Slice 2. |
| 2 | LOW | defect (cosmetic, pre-existing, narrow) | Header subtitle can render "Translating pages (0/0)" in the FIRST_PASS pre-registration window (TranslationProgressSheet.kt:890). |
| 3 | LOW | design limitation | Registry HashMap is accessed from Main and IO threads despite "main-thread only" doc (ChapterTranslationSnapshotRegistry.kt:22 vs MangaScreenModel.kt:584/:183) — recommend `ConcurrentHashMap`. |
| 4 | INFO | expected behavior | Download-cancel/remove still leaves the request WAITING (drawer shows DOWNLOAD_FAILED only while the download object is in ERROR) — Slice 2 item 3, correctly deferred. |

None of the findings block the slice contract. Items 1-2 predate this slice
and sit outside the contract's hero-scoped wording; item 3 is cheap hardening.

VERDICT: ACCEPT

Recommended (non-blocking) follow-ups, in priority order:
1. Gate `LivePipelineGrid` (or swap its content) when
   `totalPages == 0 && totalStages == 0 && pages.isEmpty()` so unknown-total
   phases show no fake per-stage numerics.
2. Replace the `Translating pages (0/0)` subtitle branch with a phase-appropriate
   string for the no-active-stage case.
3. Make `ChapterTranslationSnapshotRegistry.snapshots` a `ConcurrentHashMap`
   and correct its threading doc.
