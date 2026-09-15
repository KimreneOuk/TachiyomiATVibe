# T929 Round 1 — verify-ui: adversarial consensus on T928 `team/ui/report.md`

Base: `main` @ 9c19ad0, tracked files only (verified clean). Reviewer read every
cited file at HEAD. Paths relative to `app/src/main/java/`.

## 1. Verdict table

| Claim | Verdict | Reviewer evidence (independent) |
|---|---|---|
| 3 disjoint vocabularies/transports; manual=durable-store-only | AGREE | `scheduling/TranslationScheduler.kt:702` calls `executor.translateSinglePage(...)` with NO listener; listeners exist only at `RollingAutoCoordinator.kt:505,913,928` (`stageListenerFor`); batch emits tracker calls directly (`BatchLaneWorkers.kt:471,479,595,639,954,969`; `BatchRenderJoin.kt:145,194,310`) |
| Manual pill can never show Queued | AGREE | `PageTranslation.toReaderPageFeedback()` (`viewer/ReaderTranslationFeedback.kt:78-87`) has no PENDING/Queued branch; `else -> null` |
| L1 no manual admission signal | AGREE | `ReaderViewModel.kt:2202-2320` does no UI write; `TranslationScheduler.kt:658-702` dedup early-return 667-672, no admission write. AGGRAVATION MISSED: silent early-returns (manga null 2203; no bytes 2263-2268; lazy-download failure 2292-2298) mean some taps produce NO UI truth ever — see M1 |
| L2 persist/manifest I/O before `_state`/`_display` | AGREE-WITH-NUANCE | `ChapterTranslationStore.kt:1876-1897`: `persistArtifactMutationLocked` (1884-1888) runs before publish (1894-1895); first registration mandatory (1943-1969). Nuance: pure in-memory stores (1919-1924) and null-manifest stores (1928) skip the I/O — real reader stores have a manifest, so the claim holds in production |
| L3 first holder emission waits for store open + migration | AGREE | `ReaderViewModel.kt:2866-2884`: ANR-fix comment + store resolved inside `flow{}` on IO; source is `store.display` (2904-2908) |
| L4 150ms resetting debounce defers auto window | AGREE | `ReaderViewModel.kt:1272-1279` (`delay(150L)` at 1275, cancel/restart). ALSO: toggle-on, prefetch-slider, engine-change, and foreground-resume kicks route through the same debounce (603/628/644/685/2545 → `translateCurrentPageForAuto` → `handleAutoTranslation`) |
| L5 coalescer adds ≤120ms per post-first stage | AGREE | `ReaderTranslationFeedback.kt:223-245` + `postDelayed` flush (`ReaderPageImageView.kt:655-666`) |
| L6 chapter-seed collector delayed by store open; null → no pill | AGREE | Open before collect at `ReaderViewModel.kt:2706-2717`; seed `PagerPageHolder.kt:152-163`; null durable → null pill (`ReaderTranslationFeedback.kt:86`) |
| S1 coalescer overwrites single pending slot | AGREE (worse than stated) | Single `pending` field, overwrite at 229-231; `highestStageRank` was ALREADY bumped (220-221) before the drop, so the skipped stage can never be re-shown later either |
| S2 fixed-priority stage collapse | AGREE-WITH-NUANCE | Collapse is real, but report mis-transcribes the tracker order: `progressStage` = render>TRANSLATE>INPAINT>ocr (`TranslationBatchProgressTracker.kt:495-498`); pill mapper & `PageLifecycle` = render>inpaint>translate>ocr (`ReaderTranslationFeedback.kt:81-84`; `PageTranslationState.kt:191-194`). The two mappers disagree with each other — strengthens I1 |
| S3 StateFlow conflation at every hop | AGREE | Store `_state`/`_display` (`ChapterTranslationStore.kt:117,146`), tracker `_snapshot` (54-55), coordinator `_snapshot` (105-106), scheduler `autoSnapshotFlow` `stateIn(Eagerly)` over `flatMapLatest` (`TranslationScheduler.kt:156-165`) — one hop the report did not itemize — VM `state` (173), `autoTranslationUiState` distinct (177-185) |
| S4 terminal latch + committed-first suppress refresh animation | AGREE | Latch 193/208-214; committed-first `resolveDisplayPage` (`ChapterTranslationStore.kt:2227-2229`) used at `ReaderViewModel.kt:2766,2802`; `REFRESHING_WITH_COMMITTED_RESULT` computed (`PageDisplayProjection.kt:93`) but pill ignores PageDisplayState |
| S5 rank gate drops regressions | AGREE | `ReaderTranslationFeedback.kt:216-221` |
| S6 duplicate-tap silent no-op | AGREE | `TranslationScheduler.kt:667-672` |
| I1 three+ production vocabularies | AGREE (understated) | All cited vocabularies verified; report MISSED `AiPageProgressState` (BUFFERED/RUNNING/SUCCEEDED/FAILED/PAUSED, tracker 95-99) and `requestState`/`BatchPhase.DISPLAY` (5th phase, `snapshotFor` 195-197) — fragmentation is larger than reported |
| I2 transport differs per path | AGREE | Follows from section (a), all verified |
| I3 rebind seeds; refresh only ATTACHED holders | AGREE | Seeds `PagerPageHolder.kt:152-163` / `WebtoonPageHolder.kt:247-260`; attached-only filters `PagerViewer.kt:375-397`, `WebtoonViewer.kt:417-440` (both read) |
| I5 pager keeps autoFeedback across re-attach vs webtoon clears on bind | AGREE-WITH-NUANCE | Clear points verified (pager detach 290; webtoon bind 257 + recycle 325), but observable divergence ≈ nil: both resubscribe to `autoTranslationUiState`, a conflated StateFlow that replays its current value on Main.immediate within the same bind pass. Real differences: webtoon's bind fence (261-288) vs pager's none; init-seed vs bind-seed timing |
| I6 ring fraction ≠ pill stage semantics | AGREE-WITH-NUANCE | `fraction = doneStages/totalStages` verified (~`TranslationProgressSnapshot.kt:105`); cross-page ring inflation real. Nuance: sheet/indicator are chapter-level by design — the defect is the pill-vs-ring mental-model clash, not the aggregate itself |
| Delay inventory 150/120/900/250/800/400 + 250ms persist | AGREE (incomplete) | All verified at cited lines; see section 3 for what's missing |
| "No polling loops drive stage UI" | AGREE | No `delay(` in RollingAutoCoordinator / TranslationScheduler / BatchProgressProjector; only `ChapterTranslator.kt:462` in-flight claim retry, as reported |
| "No memory-pressure-specific stage suppression in UI layer — VERIFIED" | DISAGREE | `ReaderViewModel.onMemoryPressure` (2548-2562): Critical trim resets `liveTranslationState = NOT_TRANSLATED` (CP8-gated); background cancel rebuilds state (2506-2517). It is a state reset, not per-stage suppression, but the flat "none found" is wrong |

Every L/S/I mechanism claimed exists in code as described. No claim was found
fabricated. Two evidence lines are mis-transcribed (S2 priority order; the
memory-pressure absence claim), and L1/S6/I1 are understated (see M1, M10).

## 2. Missed mechanisms (not in the report)

- **M1 — silent tap death (extends L1/S6).** `translateSinglePage` returns with
  zero UI on: manga null (2203-2206), source cast fail (2214), no byte source
  (2263-2268), lazy download failure (2292-2298). These are no-late-stage
  either — the page stays dead permanently, not just late.
- **M2 — stranded-page sweep UI write on chapter open.**
  `ReaderViewModel.kt:2732` → 2603-2660 rewrites previous-generation
  RUNNING/PENDING entries to CANCELLED before the first collect. Bounded
  (runGeneration fence, batch-retention guard, age > SINGLE_PAGE_TIMEOUT_MS)
  but it is a UI-visible stage rewrite on re-entry that the emission inventory
  omits.
- **M3 — foreground resume re-kick.** `ReaderActivity.kt:284` →
  `resumeTranslationsOnForeground` (`ReaderViewModel.kt:2535-2546`):
  reconcile + warm-window refresh + auto kick (through the 150ms debounce).
  Report covers onPause cancel only.
- **M4 — preference re-entry storms.** Collectors at 600-618 (auto toggle),
  623-631 (prefetch slider), 635-647 (engine change), 657-688 (master enable:
  cancel-all + toast + merged-state reset 677-683) all mutate/reset stage UI
  outside the report's chain map.
- **M5 — `awaitingFreshSnapshot` stall.** `observeAutoSnapshot`
  (1436-1462) DISCARDS the current coordinator snapshot after identity/window
  rebind until a strictly newer ownerVersion/windowVersion arrives — a stage
  display stall after mid-run coordinator replacement that the fencing
  description omits.
- **M6 — cancel affordance runs `runBlocking` store write on caller (main)
  thread** (`TranslationScheduler.cancelPageTranslation` →
  `markPageCancelled`) for immediate UI truth — jank/ANR surface under store
  mutex contention; absent from the report.
- **M7 — warm-window image-refresh path.** `updateTranslationWorkingSet`
  (771-828) runs on EVERY `onPageSelected` (1238, dispatchRefresh=true) and on
  store emissions (2806): attaches/evicts `translatedStream`, dispatches
  `RefreshTranslationPages`, and `attachTranslatedStreamIfWarm` (836-837)
  bypasses the warm window entirely while batch is active — a batch-vs-manual
  render difference the report never lists.
- **M8 — third latch trigger.** A non-PROGRESS `ManualTruth` also sets
  `terminalDisplayed` (`ReaderTranslationFeedback.kt:181-186`); the terminal
  latch has three triggers, not two (matters for S4/R4/R7).
- **M9 — batch terminal reconstruction.** After tracker close, the sheet falls
  back to terminal cache then `reconstructDurableTerminalSnapshot`
  (`BatchProgressProjector.kt:229-294`) — restart-retry truth path unmentioned.
- Rotation/chapter transition: benign beyond I3 — VM-scoped collectors survive
  recreation; holders re-seed from VM-maintained `page.translation`.

## 3. Delay-inventory check

Confirmed: 150 (`ReaderViewModel.kt:1275`), 120
(`ReaderTranslationFeedback.kt:266`), 900 (`ReaderPageImageView.kt:1178`),
250 scrim (674, 693-697), 800 pulse (`TranslationProgressSheet.kt:551-556`),
400 tween (:118), 250 persist debounce
(`store/StorePersistenceScheduler.kt`, PERSIST_DEBOUNCE_MS=250).
**Uncounted:**
1. **180ms translated-image crossfade** — `TRANSLATION_CROSSFADE_DURATION_MS`
   (`ReaderPageImageView.kt:186,1177`); it is the stage→result visual
   transition, squarely in scope.
2. `WhileSubscribed(5_000)` stop timeouts (`ReaderViewModel.kt:183,218`) —
   governs UI subscription lifetime, not stage latency.
3. `PERSIST_JOIN_TIMEOUT_MS = 2_000L` (StorePersistenceScheduler) —
   durability-side join bound.
4. 500ms landscape-zoom `postDelayed` (`ReaderPageImageView.kt:816`) — zoom
   UX, out of scope but in the same file.

## 4. Recommendation sanity-check (1-8)

1. **R1 Queued/admission — AGREE-WITH-NUANCE.** As literally written, "write
   `ocrStatus = PENDING`" renders NOTHING: the durable mapper has no PENDING
   branch (`ReaderTranslationFeedback.kt:86-87`). The `manualOutcomes.Admitted`
   half is mandatory. The admission write also routes through `publishLocked`
   first-registration I/O (1884) — acceptable (moves latency to tap time) —
   and interacts benignly with M2 (aged placeholder → CANCELLED heal). No
   invariant break.
2. **R2 publish-before-persist for non-durable — AGREE-WITH-NUANCE.** Feasible:
   placeholders have no committed content, `promoteDisplayIfReadyLocked` is a
   no-op for them, and fencing lives in the precondition checks inside
   `persistArtifactMutationLocked`, not the publish order. Caveat: today a
   rejected write is rolled back BEFORE publication (`restorePageLocked`,
   1899-1903); publish-first leaks a transient state that is then silently
   rolled back (coalescer reset flicker). Keep persist-first for durable, as
   stated.
3. **R3 non-resetting auto policy — AGREE.** Coordinator trigger is CONFLATED
   (192) and windowVersion-fenced; the debounce guards no correctness
   invariant. Preserve the stale-cross-chapter drop (1249-1259). Cost: more
   `updateWindow`/reconcile churn while scrolling, bounded by conflation.
4. **R4 REFRESHING truth — CONFLICT (understated risk).** Not a feed-in fix:
   (a) `selectReaderPageFeedback` puts durable Translated/Failed above
   everything but an active attempt (101-102) — REFRESHING needs a NEW
   precedence tier, against the documented T917 guarantee that durable
   terminal retains precedence; (b) `beginAttempt` fires only when
   `isPageBeingTranslated` flips true (`PagerPageHolder.kt:248-250`), but
   under committed-first resolution `page.translation` IS the committed
   bundle, so the latch never opens and the coalescer (193) eats every
   REFRESHING stage. Requires redesigning the attempt-open signal + precedence;
   "low risk" is wrong.
5. **R5 unified vocabulary — AGREE.** No invariant conflict; interim reuse of
   `TranslationProgressStage` must keep manual Queued intentional (R1).
6. **R6 pending-flush — AGREE-WITH-NUANCE.** Preserves rank monotonicity;
   but flushing at "remaining < 1 frame" still breaks the 120ms minimum-display
   contract for the flushed stage (~104ms shown) and the ≤16ms heuristic is
   frame-rate/postDelayed-granularity dependent. Alternative — flush the old
   pending immediately and pend the newer — renders every transition but
   shortens each display; the feel trade must be stated.
7. **R7 dedup feedback — AGREE-WITH-NUANCE.** Works for mid-run ("durable
   active" owns the chip), but for an already-TRANSLATED page the terminal
   latch (`209`, and M8) swallows the re-emitted truth unless
   dismiss/beginAttempt is specified — R7 is silent exactly in the retry case
   users hit.
8. **R8 peekStore seed — AGREE.** Read-only registry hit with graceful miss;
   must return the same instance collectors resolve (manager activeStores,
   chapterId-keyed — it does). No fencing concern.

## Bottom line

Consensus: AGREE with the report's core architecture findings and all 18
numbered mechanisms (4 with nuances, 1 sub-claim contradicted). The report is
reliable as a map of the three chains and the L/S/I causes; it is INCOMPLETE on
silent manual-tap failures (M1), lifecycle/preference re-entry paths (M3/M4),
the stranded sweep (M2), the AI-lane vocabulary (I1 addendum), the 180ms
crossfade, and it materially understates R4's risk. Solutions Round 2 should
treat R1+R2+R3 as validated direction and R4 as a design problem, not a patch.
