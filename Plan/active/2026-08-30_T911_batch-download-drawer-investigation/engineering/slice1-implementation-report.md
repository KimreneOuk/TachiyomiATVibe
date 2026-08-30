# T911 Slice 1 implementation report — immediate and truthful UX

Implementer: Implementer role, T911 Slice 1. Branch `t911/repair`
(based at `67b1ff0`). Changes left uncommitted in the working tree.
Verified against `IMPLEMENTATION.md` Slice 1 scope: manga screen +
projection + sheet/indicator rendering only. No persistence schema
changes, no coordinator protocol changes, no downloader behavior
changes for chapters without a translation request.

## Post-review fixes (Reviewer verdict: ACCEPT, three LOW concerns)

Applied the two authorized fixes; the third concern (pipeline grid
"0 / 1" placeholder polish) is deferred untouched.

1. Thread safety: `ChapterTranslationSnapshotRegistry` now backs its
   keyed store with `ConcurrentHashMap`
   (`ChapterTranslationSnapshotRegistry.kt:4,26`), with the class doc
   documenting why: snapshot writes arrive via the per-chapter
   collectors' `withUIContext` delivery while list rebuilds can read
   from IO dispatchers, so the map must be safe across both threads.
   The registry tests make no construction-type assertions, so they
   are unchanged and still pass.
2. Residual 0/0: the sheet subtitle's translating fallback
   ("Translating pages (0/0)") is now phase-aware like the hero.
   `batchStatusHeaderSubtitle` (made `internal` for testing,
   `TranslationProgressSheet.kt:864`) routes its FIRST_PASS fallback
   through `BatchHeroProjection.of`
   (`TranslationProgressSheet.kt:908-915`): unknown totals render the
   owning phase (`phaseSubtitleLine`,
   `TranslationProgressSheet.kt:832-847`, e.g. "Preparing translation
   batch..."), real totals keep the numeric "Translating pages (x/y)".
   New focused suite
   `eu.kanade.presentation.manga.components.TranslationProgressSheetSubtitleTest`
   (3 tests) asserts no "0/0" in the unknown-total phase, numeric
   "12/40" retained for real totals, and the phase-aware waiting
   subtitle.

Post-fix verification: same focused command extended with the subtitle
suite — 15 suites, 79 tests, 0 failures, 0 errors (`BUILD SUCCESSFUL`).

## Contract item 1 — Drawer opens on confirmation

`app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`

- New private transaction helper `openTranslationProgressDrawer(item)`
  (`MangaScreenModel.kt:885-891`): starts the keyed batch-progress
  collector for the chapter and selects
  `Dialog.TranslationProgress(chapterId)` in the same
  `updateSuccessState` transaction. Null chapter id is a safe no-op.
- `ChapterTranslationAction.DETAILS` now delegates to the same helper
  (`MangaScreenModel.kt:913`), replacing the inline DETAILS branch —
  identical behavior to before, one shared transaction.
- `confirmChapterTranslation` calls `openTranslationProgressDrawer(item)`
  immediately after `acknowledgeTranslationRequests` +
  `updateTranslationRequests` (`MangaScreenModel.kt:1040-1044`), i.e. in
  the same UI transaction that acknowledges the request. The async
  download probe that follows never performs navigation, and no
  downloader/translation callback performs navigation (none did before;
  none was added).
- Confirmation is also reachable via the drawer's own Resume action and
  the cancel-Undo snackbar; both re-select the same dialog, which is a
  visual no-op when already open.

## Contract item 2 — Download phase joined into the batch projection

Read-only display join, implemented at the UI-projection layer:

- `MangaScreenModel.activeDownloadFor(chapterId)` (new accessor,
  `MangaScreenModel.kt:862-867`) exposes the existing
  `DownloadManager.getQueuedDownloadOrNull` view the screen model
  already uses for chapter rows.
- `MangaScreen.kt:293-302` (`Dialog.TranslationProgress` branch) passes
  the item's `downloadState`/`downloadProgress` (already collected by
  `updateDownloadState`, `MangaScreenModel.kt:511-520,638-649`) plus
  the live download page counts (`Download.downloadedImages`,
  `Download.pages.size`) into `TranslationProgressSheet`.
- New pure projection `BatchHeroProjection.of(...)` in
  `app/src/main/java/eu/kanade/translation/model/BatchHeroProjection.kt`
  merges the snapshot's request phase with the download state/progress:
  a `WAITING_FOR_DOWNLOAD` request over a `DOWNLOADING` chapter yields
  `Phase(DOWNLOADING, fraction = downloadProgress/100, donePages,
  totalPages)`; over a `QUEUE`d download it yields an indeterminate
  `WAITING_FOR_DOWNLOAD` phase; over `ERROR` a `DOWNLOAD_FAILED` error
  phase; over `DOWNLOADED` a `PREPARING` phase (finalization/rekey/
  handoff window).
- The downloader gains no translation ownership: `BatchProgressProjector`
  and `TranslationManager` are untouched; the join consumes only what
  the manga screen already collected.
- `TranslationProgressSheet.kt:91-94,116-121` receives the join;
  `TranslationProgressSheet.kt:513-535,564-573` additionally fixes the
  status pill showing "Idle" while a request is accepted/waiting/
  preparing/failed.

Deliberate deviation (justified): the contract pointed at
`BatchProgressProjector.observeBatchProgress` as the current code path.
The join is implemented as a pure hero projection fed by the manga
screen's existing chapter-row download state instead of inside
`BatchProgressProjector`, because the projector lives in the
translation subsystem and adding a `DownloadManager` dependency there
would couple the translation manager to the downloader. The observable
behavior demanded by the contract (drawer shows download phase +
determinate percent while WAITING_FOR_DOWNLOAD) is fully delivered.

## Contract item 3 — Never render unknown totals as 0/0

- `BatchHeroProjection.of` classifies every snapshot into either
  `Numeric` (real translation totals: `totalPages > 0 ||
  totalStages > 0 || pages.isNotEmpty()` — existing percent +
  "Page x of y" rendering, kept byte-for-byte in behavior) or `Phase`
  (`ACCEPTED`, `WAITING_FOR_DOWNLOAD`, `DOWNLOADING n%`,
  `DOWNLOAD_FAILED`, `PREPARING`, `QUEUED`, `PAUSED`, `FINALIZING`,
  `COMPLETED`, `FAILED_NO_PAGES`). A real zero-page failure
  (`state ERROR`/`aborted`/terminal-with-failures and no page data) is
  a distinct `FAILED_NO_PAGES` error phase — never numeric 0/0, never
  an unknown-total phase. Terminal-without-totals-without-failures
  renders `COMPLETED` (no numeric 0).
- `TranslationProgressSheet.kt:197-278` renders the two branches:
  `Numeric` keeps the animated percent + page string + determinate bar;
  `Phase` shows the phase headline (`phaseHeroLabel`,
  `TranslationProgressSheet.kt:850`), an optional page-count line
  for the download phase, and an indeterminate bar (or download-percent
  determinate bar; no bar for error phases).
- Phase labels are new i18n strings in
  `i18n-at/src/commonMain/moko-resources/base/strings.xml:215-225`
  (`manga_batch_phase_*`), generated into `ATMR` at build time.
- The pure mapping is fully unit-tested (`BatchHeroProjectionTest`, 16
  cases covering the contract's phase list, the download join, the
  zero-page failure distinction, and the fresh-open accepted case).

## Contract item 4 — Snapshot retention across list rebuilds

- New screen-scoped keyed store
  `app/src/main/java/eu/kanade/tachiyomi/ui/manga/ChapterTranslationSnapshotRegistry.kt`
  (`remember`/`snapshotFor`/`forget`, one compact record per chapter id,
  main-thread only, screen lifetime — bounded by construction).
- `MangaScreenModel.kt:557-561` instantiates the registry next to the
  collector job map.
- Write path: `updateTranslationProgress` remembers every canonical
  emission before it reaches the item (`MangaScreenModel.kt:597-600`).
- Read path: `toChapterListItems` (now `internal`,
  `MangaScreenModel.kt:663`) appends
  `.carryingTranslationSnapshots(translationSnapshots)`
  (`MangaScreenModel.kt:714`), which fills `translationProgress = null`
  gaps in freshly reconstructed items with the retained snapshot while
  never overwriting an item that already carries a fresher live value.
- Terminal retention: `stopTranslationProgress`
  (`MangaScreenModel.kt:601-607`) still cancels the collector and the
  registry entry survives, so the post-terminal list rebuild reads the
  terminal snapshot instead of falling back to `0/0`.
- Eviction: explicit reset/delete (`runChapterReset`,
  `MangaScreenModel.kt:990`) forgets the snapshot, so reset chapters do
  not resurrect stale progress.
- Tests: `ChapterTranslationSnapshotRegistryTest` (7 cases) covers the
  pure registry + rebuild semantics; the screen-model fixture
  (`MangaScreenModelTranslationDrawerTest`) proves the same against the
  real screen model, including a rebuild executed while the collector
  is cancelled at terminal status.

## Contract item 5 — DETAILS reachable from all states

- New pure routing table `translationIndicatorTapAction(state,
  hasPendingRequest)` in
  `app/src/main/java/eu/kanade/presentation/manga/components/ChapterTranslationIndicator.kt:60-73`:
  every state with observable work (pending request, QUEUE,
  TRANSLATING, PAUSED, TRANSLATED, READY_WITH_WARNINGS, ERROR) routes
  tap to `DETAILS`; only a chapter with no work and no request starts a
  new batch from a tap.
- Rewired `TranslatedIndicator` tap (`:325-336`): was open
  Translate/Delete menu — now opens the progress drawer; the
  retranslate/delete menu remains on long-press.
- Rewired `ErrorIndicator` tap (`:371-385`): was START retry — now
  opens the progress drawer (new `translationState` parameter wired
  through the routing function); retry remains on long-press.
- `PendingTranslationIndicator` and `TranslatingIndicator` already
  routed tap to DETAILS (pending/queued/downloading/translating/
  paused) — unchanged, now covered by the same routing table.
- No Compose test harness exists in this repo (no `createComposeRule`
  usage, no ui-test dependency), so routing is covered at the
  pure-logic level per contract: `ChapterTranslationIndicatorRoutingTest`
  (all 7 states x pending-request combinations).

## Tests added and evidence

New focused suites:

| Suite | Tests | Result |
| --- | --- | --- |
| `eu.kanade.translation.model.BatchHeroProjectionTest` | 16 | pass |
| `eu.kanade.tachiyomi.ui.manga.ChapterTranslationSnapshotRegistryTest` | 7 | pass |
| `eu.kanade.presentation.manga.components.ChapterTranslationIndicatorRoutingTest` | 4 | pass |
| `eu.kanade.tachiyomi.ui.manga.MangaScreenModelTranslationDrawerTest` | 5 | pass |

Fixture suite coverage: (a) `confirmChapterTranslation` selects
`Dialog.TranslationProgress(chapterId)`; (b) DETAILS still selects it;
(c) a live snapshot emitted on the canonical flow survives a full
`toChapterListItems` rebuild performed without a new canonical
emission; (d) a terminal snapshot survives collector cancellation
(triggered by a real TRANSLATED status emission through the
lifecycle-gated `statusFlow` collector) plus a later rebuild, and a
post-cancellation canonical emission provably does not reach the item;
(e) a WAITING_FOR_DOWNLOAD acknowledgement reaches the chapter item and
the drawer stays selectable. The fixture uses a fully mocked
constructor, a real resumed `LifecycleRegistry` (with an ArchTaskExecutor
delegate so `Looper` is not needed), and a single-threaded main
executor (MigratorTest pattern).

Regression suites run against the touched paths (all pre-existing,
unmodified):

| Suite | Tests | Result |
| --- | --- | --- |
| `eu.kanade.tachiyomi.ui.manga.EnqueueTranslationDownloadsTest` | 3 | pass |
| `eu.kanade.translation.TranslationManagerPendingAcknowledgementTest` | 4 | pass |
| `eu.kanade.translation.TranslationManagerDownloadFailureRecoveryTest` | 5 | pass |
| `eu.kanade.translation.TranslationPendingRequestStoreTest` | 5 | pass |
| `eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTest` | 8 | pass |
| `eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistryTest` | 4 | pass |
| `eu.kanade.translation.pipeline.batch.BatchProgressReconcilerTest` | 2 | pass |
| `eu.kanade.translation.pipeline.batch.TranslationBatchProgressReducerTest` | 3 | pass |
| `eu.kanade.translation.model.TranslationUiProjectionTest` | 3 | pass |
| `eu.kanade.translation.model.TranslationProgressTest` | 7 | pass |

Totals: 14 suites, 76 tests, 0 failures, 0 errors — verified twice,
including one full `--rerun-tasks` run.

Exact command (Windows Git Bash; `JAVA_HOME` must point at the JDK, e.g.
Android Studio's JBR):

```
JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" \
./gradlew :app:testDevDebugUnitTest --console=plain \
  --tests "eu.kanade.translation.model.BatchHeroProjectionTest" \
  --tests "eu.kanade.tachiyomi.ui.manga.ChapterTranslationSnapshotRegistryTest" \
  --tests "eu.kanade.presentation.manga.components.ChapterTranslationIndicatorRoutingTest" \
  --tests "eu.kanade.tachiyomi.ui.manga.MangaScreenModelTranslationDrawerTest" \
  --tests "eu.kanade.tachiyomi.ui.manga.EnqueueTranslationDownloadsTest" \
  --tests "eu.kanade.translation.TranslationManagerPendingAcknowledgementTest" \
  --tests "eu.kanade.translation.TranslationManagerDownloadFailureRecoveryTest" \
  --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressTrackerTest" \
  --tests "eu.kanade.translation.pipeline.batch.TranslationBatchTrackerRegistryTest" \
  --tests "eu.kanade.translation.pipeline.batch.BatchProgressReconcilerTest" \
  --tests "eu.kanade.translation.pipeline.batch.TranslationBatchProgressReducerTest" \
  --tests "eu.kanade.translation.model.TranslationUiProjectionTest" \
  --tests "eu.kanade.translation.model.TranslationProgressTest" \
  --tests "eu.kanade.translation.TranslationPendingRequestStoreTest"
```

(Note: the module has a `default` flavor dimension, so the task is
`testDevDebugUnitTest`, not `testDebugUnitTest`.)

## Files changed

Modified:

- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt`
- `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt`
- `app/src/main/java/eu/kanade/presentation/manga/components/ChapterTranslationIndicator.kt`
- `i18n-at/src/commonMain/moko-resources/base/strings.xml`

Added:

- `app/src/main/java/eu/kanade/translation/model/BatchHeroProjection.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/ChapterTranslationSnapshotRegistry.kt`
- `app/src/test/java/eu/kanade/translation/model/BatchHeroProjectionTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/manga/ChapterTranslationSnapshotRegistryTest.kt`
- `app/src/test/java/eu/kanade/presentation/manga/components/ChapterTranslationIndicatorRoutingTest.kt`
- `app/src/test/java/eu/kanade/presentation/manga/components/TranslationProgressSheetSubtitleTest.kt` (post-review)
- `app/src/test/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModelTranslationDrawerTest.kt`

## Acceptance mapping (Slice 1 subset of SYNTHESIS gates 1, 2, 3, 5)

- Confirming a fresh undownloaded chapter now opens the drawer
  immediately (item 1, test a).
- While WAITING_FOR_DOWNLOAD the drawer hero shows the download phase
  with live determinate percent, or the waiting/accepted/preparing
  phase — never 0/0 (items 2-3, tests in BatchHeroProjectionTest +
  screen fixture e).
- Transitions to Preparing -> Queued/Translating come from the existing
  pending -> queue state changes; `0/N` appears only when a real total
  exists (totalPages/totalStages > 0), enforced by the projection.
- No list emission or terminal cleanup can revert the item to no
  snapshot (item 4, fixture tests c/d).
- Drawer and ring agree: both consume the same retained
  `ChapterList.Item.translationProgress`.

## Deliberately left for Slice 2/3

- Download cancel/remove/clear/stop/offline/missing-source still leaves
  the request WAITING (R5); the drawer now at least shows
  `DOWNLOAD_FAILED` when the download object itself is in ERROR, but
  the durable request is not transitioned — Slice 2 item 3.
- Request generations/cancel fencing, multi-select group wiring,
  startup reconciliation — Slice 2.
- Tracker totals from ordered work keys, typed terminal snapshots for
  exceptional exits, finalization/handoff failure split, durable
  terminal reconstruction — Slice 3.
- The registry is screen-scoped; terminal details after process death
  still rely on the bounded manager cache — Slice 3 item 4.
