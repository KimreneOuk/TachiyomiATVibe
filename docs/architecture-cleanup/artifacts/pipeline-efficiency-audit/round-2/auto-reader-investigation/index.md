---
title: "Round 2 — Auto-mode & reader display investigation (Agent C)"
kind: review
comments: none
---

# Agent C — Auto-Mode & Reader Display Investigation (Round 2)

HEAD 4de121bf, branch inpainting-modes-redesign. Read-only static analysis; no builds/device runs. All @line valid at HEAD.

## T1 — End-to-end auto lag map

Chain (page visible → overlay/clean swap visible):

1. Page select: `ReaderViewModel.onPageSelected` (app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:1086-1159) — arrival stamp + `user_arrival` (:1099-1107), working-set update (:1134), then `handleAutoTranslation` (:1149).
2. Debounce 150 ms: `ReaderTranslationController.handleAutoTranslation` (ReaderTranslationController.kt:395-413, `delay(150L)` :396). Rapid flips cancel/restart; only then does the window update.
3. Window update: `handleAutoTranslationOnIo` (:416-465) → `TranslationManager.updateAutoWindow` (TranslationManager.kt:1944-1987; suppressed while batch active) → `TranslationScheduler.updateAutoWindow` (TranslationScheduler.kt:152-229) → `RollingAutoCoordinator.updateWindow` (RollingAutoCoordinator.kt:197-261): new WindowSpec (version++), synchronous anchor snapshot, poke (:260). Then explicit `reconcileAutoWindow()` (ReaderTranslationController.kt:464) — the only path that also releases matured translation pauses (RollingAutoCoordinator.kt:746-758).
4. Reconcile pass (RollingAutoCoordinator.kt:766-1019): desired = visible (if not display-ready) + consecutive forward prefix (computeDesiredSet :1046-1068; AutoWindowState.kt:121-138). Memory gate defers all but foreground (:799-816; gate = `hasHeadroomForPrefetch`, TranslationMemoryBudget.kt:306-316). Missing stream → SourceUnavailable deferral (:828-840), re-poked by loader readiness callback (ReaderTranslationController.kt:473-528).
5. Native lane admission — ONE page at a time, inline in the loop (:844): `withNativeLane` → DevicePagePermitGate capacity 1 (DevicePagePermitGate.kt:28; AdaptiveKnobController.kt:160 `DEFAULT_FLOOR = 1`) + native result timer.
6. ONNX phase (SinglePageOnnxPhase.kt:249-638): plan (PageWorkPlanner.plan :297), OCR RUNNING publish (:324-337), engine-build mutex (:344-347), decode + memory preflight (:484-518), analyze = det+seg+per-ROI OCR+panel detect under `nativeGuard` (RoiPageRecognitionEngine.kt:564).
7. Inpaint: detached `CoroutineScope(Dispatchers.Default).async` (SinglePageOnnxPhase.kt:1182) → CLEANING event (:1184) → `nativeGuard.withLock` inpaint (RoiPageRecognitionEngine.kt:989) → PageInpaintingEngine → LiteRT GPU-first (PageInpaintingEngine.kt:89-108; LiteRTMangaInpaintingEngine 512² FP16).
8. `processSinglePage` returns; cancellation checkpoint (:1259); budget telemetry (:1271-1282).
9. **prepareSinglePage awaits the inpaint job before the handoff**: TranslationPipeline.kt:956-963. Note the permit/quarantine boundary: `withNativeLane` closes at :927 — the deferred-publication drain (:939-947), inpaint await (:956-963), cleaned persist (:969-992), and the flush inside `publishPreparedPageFromOcr` (:1000-1012 → PreparedPageBoundary.kt:71-73) all run OUTSIDE the native permit. The serialization that matters is the PRODUCER: the coordinator's inline reconcile loop runs one prepare at a time, so these tail steps still block prepare N+1 from starting.
10. Cleaned persist: `persistOnnxCleanedImage` (TranslationPipeline.kt:969-992) → lazy live patch sets `cleanedImageName` immediately; JPEG q90 + SHA-256 + sidecar enqueued on the store persistence worker (CleanedPublication.kt:294-433, 440-507).
11. `publishPreparedPageFromOcr` (PreparedPageBoundary.kt:33-93): mergeOcr, then **`store.flush()` joins the lazy JPEG worker on the producer's serial critical path** (:71-73) — outside the native permit, but still gating the handoff.
12. Handoff: `preparedChannel.send` (RollingAutoCoordinator.kt:963), capacity 2/4/6 by memory tier (TranslationMemoryBudget.kt:40-44). If full, the **producer parks on send** — the native permit is already released at :927, but no new prepare starts until the consumer picks up.
13. Translate consumer (RollingAutoCoordinator.kt:507-669): pickup → `NonCancellable` + `withTimeout(210_000)` drain (:545-566; constant :1398) → attempt-cap check fail-open (:453-476) → `translatePreparedPage` (TranslationPipeline.kt:1069-1212): Translation-stage lease, stale-ref checks (gen/version/fingerprints :1101-1116), cleanedImageName required (:1124-1129), then HTTP+render under `withTimeoutOrNull(120_000)` (:1179; `SINGLE_PAGE_TIMEOUT_MS` :174): governor wait → contextual provider call → ≤2 PARTIAL retries (:186) → color-estimate "render" under render permit (SinglePageHttpRenderPhase.kt:731-787; permit starvation → `renderStatus=PENDING` + Paused, :298-314) → optional flush (:955-973) → guarded `patchPage` commit with `render_tail_breakdown` (:1000-1025).
14. Display publication: commit → `promoteDisplayIfReadyLocked` (ChapterTranslationStore.kt:3706/3747 → 4142-4166; requires `hasRenderedResult || isTextlessTerminal`) → `_display.update` (:4176-4188).
15. Reader swap: `store.display` → holder cold flow `observePageView` (ReaderTranslationController.kt:1606-1674) → `attachTranslatedStreamIfWarm` (lazy stream factory :297-309) → `showTranslatedImage=true` + RefreshTranslationPages event (:1653-1667) + `display_attach`.
16. Pager/Webtoon holder: `refreshTranslation` → `setImage` → **full cleaned-JPEG decode** (PagerPageHolder.kt:366-408, 281-364; stream lease TranslationStreamRegistry.kt:164-198); overlay bound via `selectReaderTranslationOverlayBinding` (ReaderTranslationOverlayBinding.kt:42) → `setTranslationBlocks` on UI.

### Ranked lag mechanisms (reading-speed paging)

1. **Serial producer critical path** — one prepare at a time, with inpaint-await + deferred drain + cleaned persist + flush all on the producer's serial path even though the native permit closes at TranslationPipeline.kt:927. Per-page cost not measured this round (prior audit carried ~5-6 s including GPU inpaint; treat as unverified here). A newly visible page queues behind whatever prepare occupies the producer — the dominant `arrival_wait_recorded` contributor.
2. **Single translate consumer + 210 s drain** (:545-566): one slow/hung provider call parks all later pages' translation; when the 2-6 slot channel fills, the producer parks on send and new prepares stop — total stall up to 210 s.
3. **Render-permit starvation tail**: `deferRenderForPermit` leaves the translated page invisible (`renderStatus=PENDING`) until a pause→external-reconcile→retry→permit cycle completes (SinglePageHttpRenderPhase.kt:298-314).
4. 150 ms debounce per turn (small, every turn).
5. LOCAL_COMPUTE gate (Semaphore(1), RollingAutoCoordinator.kt:423, 550-557) serializes translate behind prepare — window starves on ML-Kit configs.
6. UI swap cost: one extra full-page JPEG decode of the cleaned file + event/dispatch hops. Not measured in this round; no bound asserted.

Hidden vs experienced: debounce/queue/ahead-native work hide behind dwell; visible-page lane wait + provider + render-permit + commit are experienced.

## T2 — Target policy evaluation + proposal

Facts: ahead target = user pref coerced 1..5 (ReaderTranslationController.kt:431); window = consecutive forward prefix only (no backward desire, no direction bias); no preemption or keep-alive on turn — in-flight pages run to completion because `isWorkCurrent` checks identity/generation/store only (RollingAutoCoordinator.kt:1099-1105) and `evictObsolete` deliberately protects `translateAdmitted` (:1082); memory demotion defers non-foreground; channel capacity 2/4/6 is independent of the ahead target.

Evaluation: depth is not the core problem — order and waste are. (a) Strictly ascending scan means a stale ahead prepare can occupy the lane when the new visible page arrives. (b) On fast flips, up to 5 pages get full native+provider work the user already scrolled past (results commit durably, so it is waste, not corruption). (c) Skip-back into unread territory gets no bias.

Proposals (benefit/risk):

- P1 Visible-first lane discipline — **NOT RECOMMENDED as originally framed (high risk)**: gating all non-foreground admission on foreground readiness would destroy the designed remote-overlap/prefetch (native B while translation A runs) and, worse, can starve the whole window behind a FAILED visible page that never becomes Ready. If foreground discipline is wanted at all, narrow it to "at most one ahead prepare in flight while the foreground is neither Ready nor terminal-Failed" — still medium risk; do not ship without telemetry.
- P2 Window-version staleness drop: at consumer pickup (or in `isWorkCurrent`), drop PreparedWork whose page is no longer desired (insertion RollingAutoCoordinator.kt:517-524). Benefit: eliminates most flip-past waste; risk: re-prepare churn on flip-back — bounded by existing `reprepareAttempts` cap.
- P3 Dwell-scaled effective depth: cap effective ahead by observed arrival cadence (telemetry already exists). Medium complexity; low risk.
- P4 Channel capacity 1 for LOCAL_COMPUTE (capacity is per-coordinator at :424-425). Minor.

## T3 — Failure behavior + skip-ahead design

Current behavior (verified; agrees with orchestrator's CoordinatorTest 259/570 that ordinary failures already skip within the fixed window):

- Typed retryable provider failure → `translationStatus=PARTIAL` + `translationError`, outcome `Paused` → `deferTranslationRetry` (RollingAutoCoordinator.kt:596-609, 719-731) → slot `Deferred(Network)`; `isAdmissible` false (:1027-1032). The admission scan `continue`s past it — **pages n+1/n+2 are NOT blocked at admission today**.
- Generic consumer exception → `Failed(retryable=true)` slot (:652-659) which is NEVER re-admitted while the page stays in the window (isAdmissible excludes Failed) — a permanent in-window skip for transient errors (inverse bug, see T5/B6).
- Attempt ledger: `MAX_CONSECUTIVE_UNRESOLVED = 3` (ChapterArtifactManifest.kt:230); entries written before the call and resolved on ANY completed call (typed failure included — SinglePageHttpRenderPhase.kt:351-374; RollingAutoCoordinator.kt:503-505); the counter only increments at startup reconcile (ChapterAttemptLedger.kt:128-139). So in-session provider-failure retries are UNCAPPED **while the page remains desired / not display-ready**: with `RETRY_AT_NEXT_RECONCILE`, each external reconcile (typically a page turn) releases the failing page first (ascending scan) and it re-pays a full resume-prepare (cleaned-bitmap load + repersist, cost unmeasured) plus a doomed provider call ahead of fresh pages. The storm is bounded by the window: forward navigation past the page evicts it via `evictObsolete` (leaving desired removes slot/pause state, RollingAutoCoordinator.kt:1070-1091), so only pages at/after the anchor keep retrying.
- `WAIT_FOR_DEPENDENCY` is batch-only (PageWorkPlanner.planChapter:135-180); the single-page auto plan has no cross-page chain — it does not gate rolling auto.
- Remaining true stalls: consumer drain ≤210 s; channel-full parking the producer (send at :963, permit already released); LOCAL_COMPUTE gate.

Skip-ahead design (minimal, lease/ledger/fence-consistent):

- S1 Backoff: in the Failed/Paused branches, compute `eligibleAt = now + min(2^k × 10 s, 120 s)` from a per-page in-memory counter (new map mirroring `reprepareAttempts`), passed to the existing `deferTranslationRetry` timestamp parameter (RollingAutoCoordinator.kt:719-731; release path already respects timestamps :746-758). Insertion: :596-609.
- S2 Fresh-first ordering: sort `orderedDesired` (:789) so previously-failed pages trail fresh pages.
- S3 Session skip cap: at k ≥ 3 flip the slot to `Failed(retryable=false)` or a new `AutoSlotState.Skipped`, clear the pause entry. No store lies: the durable page stays `translationStatus=PARTIAL` + `translationError`; `shouldSurfaceError` is false for PARTIAL (PageTranslationState.kt:226-230) so the reader shows the original image without red-error spam; the user's per-page tap resumes (force resolves FAILED; PARTIAL resumes untranslated blocks, ReaderTranslationController.kt:898-907). The attempt entry is already resolved by the typed outcome; leases were released in `translatePreparedPage`'s finally; generation fences untouched — the skip is purely coordinator state.
- S4 Keep the skipped page in `desired` so the snapshot shows its state; `evictObsolete` cleans up on window exit. UI insertion: `projectReaderAutoTranslationUiState` label in ReaderViewModel.

## T4 — Display gate map + proposal

Gates between "page translated" and "cleaned+overlay visible":

- G1 `displayImageName = cleanedImageName.takeIf { isTranslationDisplayReady }` (PageTranslationState.kt:20-21). The cleaned swap is FULLY gated on full display readiness. This confirms the orchestrator's correction: `tier2Finished` (ReaderTranslationController.kt:1653) depends on `displayImageName`, so there is no early clean swap. FACT.
- G2 `isTranslationDisplayShapeReady` (PageDisplayProjection.kt:86-90): requires (cleaned-ready OR `originalImageFallback`) AND translation READY/PARTIAL AND `renderStatus == READY` AND ≥1 non-blank translation. OCR-only or translate-only display is impossible by policy (doc PageTranslationState.kt:36-40). Inpaint completion therefore gates overlay display.
- G3 Render = color estimation under a render permit; starvation → `renderStatus=PENDING` + Paused (SinglePageHttpRenderPhase.kt:298-314, 731-787) — invisible until the pause→retry cycle. Policy, and a real hidden tail.
- G4 Commit → promote → `_display` (ChapterTranslationStore.kt:3706/3747 → 4142-4188). Correctness (committed-pointer semantics); cheap.
- G5 Holder refresh: event → full cleaned-JPEG decode (PagerPageHolder.kt:366-408, 281-364; lease TranslationStreamRegistry.kt:164-198). Overlay bound under the same `showTranslatedImage && displayReady` condition (ReaderTranslationOverlayBinding.kt:42) — text and cleaned base flip together, atomically.
- The cleaned JPEG persist does NOT gate the reader swap directly — it gates the PREPARED handoff upstream via `store.flush()` (PreparedPageBoundary.kt:71-73). The owner's "seconds" is dominated by lane position + G3 + commit tail, not the UI swap.

Proposal (preview without lying about render state):

- Add a `PREVIEW` display state: when translation is READY/PARTIAL and `renderStatus == PENDING` (permit-starved) or cleaned durability is pending, bind the overlay on the ORIGINAL image using OCR-time per-block colors (already computed at RoiPageRecognitionEngine.kt:1056). Insertions: `PageDisplayProjection.singlePageProjection` (+ state), `selectReaderTranslationOverlayBinding`, holder `refreshTranslation`.
**Ghosting caveat (orchestrator correction, accepted):** the displayed base image is the ORIGINAL, which still contains the source text — this is true even after offscreen inpaint completes; `inpaintStatus == READY` does NOT remove the ghosting, because the base being shown is not the cleaned output. Any overlay-on-original preview therefore double-renders text until the cleaned base swaps. Options, in order of preference: (a) restrict preview to the narrow window where the cleaned file is already durable/verified but display promotion has not yet propagated (promotion is upstream of the holder, so this window may be too small to matter — measure first); (b) accept labeled ghosting as a transient; (c) drop preview and instead shorten G3. Do not gate preview on inpaint stage status — it does not bound the visual risk.
- Cheaper win: shorten G3 — tryAcquire-with-timeout on the render permit and estimate colors from blocks when the cleaned bitmap is still lazy (insertion SinglePageHttpRenderPhase.kt:731).
- Preserve committed result during candidate retries: ALREADY correct (`promoteDisplayIfReadyLocked` never demotes; `REFRESHING_WITH_COMMITTED_RESULT` PageDisplayProjection.kt:39; `resolveDisplayPage` :4391). No change needed.
- `originalImageFallback` is storage-failure policy only (CleanedPublication.kt:516-523), not a scheduling shortcut — confirmed; it is precedent for a preview flag but not usable as-is.

## T5 — Bugs

- B1 Uncancellable detached inpaint: `CoroutineScope(Dispatchers.Default).async` (SinglePageOnnxPhase.kt:1182) has no parent job; window cancel cannot stop it; it holds `nativeGuard` (RoiPageRecognitionEngine.kt:989) against the next page's analyze (:564) — wasted GPU + serialization after cancel. FACT.
- B2 AUTO prepare awaits inpaint before the handoff (TranslationPipeline.kt:956-963 — outside the native permit, which closes at :927, but on the producer's serial path) and OCR results are not durably published until inpaint finishes — the detached overlap only ever hides behind the previous page's provider call. FACT (design note).
- B3 Retry republish waste (orchestrator recheck confirms): a translate-only resume skips OCR/inpaint and loads the cleaned bitmap from disk (SinglePageOnnxPhase.kt:364-437), then prepare repersists it — NEW cleaned name, full JPEG q90 encode + SHA-256 + sidecar + retire-old (CleanedPublication.kt:294-433, 503-507) on every retry. FACT.
- B4 `isWorkCurrent` ignores window version (RollingAutoCoordinator.kt:1099-1105) + `evictObsolete` protects `translateAdmitted` (:1082): flipped-past pages still consume GPU/provider. FACT (waste, not corruption).
- B5 210 s `TimeoutCancellationException` rethrow (:637-644) fails the CONSUMER child; via the `coroutineScope` in `runCoordinationLoop` the producer is cancelled too, but that cancellation only lands at the producer's next suspension point — if the producer is parked in native/uncancellable work (mid-prepare) it keeps running, and `ensureCoordinationRunningLocked` (:380-403) will not start a replacement until the old coordination job actually completes, so `updateWindow` does not necessarily restart work promptly; `reconcile()`/poke cannot revive a dead loop at all. Reachability narrow (inner 120 s timer converts suspending stalls to IOException, TranslationPipeline.kt:1179-1190); residual risk in non-suspending store/permit/native gaps. QUALIFIED.
- B6 `Failed(retryable=true)` from generic consumer exceptions (:652-659) is a dead slot while the page remains in-window (isAdmissible excludes Failed) — permanent skip for transient errors. FACT; unify with the T3 design.
- B7 Pause loop on render-permit starvation leaves the page invisible indefinitely (policy). FACT.
- B8 120 s single-page timeout includes governor + render-permit waits, so contention can fail an otherwise healthy page as timed-out (TranslationPipeline.kt:1179-1190). SUSPECTED.
- B9 Trivial: double `syncTranslateButtonState` in `refreshTranslation` (PagerPageHolder.kt:396, 401).

## What changed in the 13 new commits (e5b2f49b..4de121bf)

Inpaint stack → LiteRT GPU-first: `LiteRTMangaInpaintingEngine` (both neural models, GPU-required for AOT, GZ flatbuffer, GPU→CPU fallback, ring-median pad), `PageInpaintingEngine` routes BALANCE/QUALITY through it and both Quality region classes through the selected neural model. OCR: Paddle GPU/CPU compute-device plumbing (`EngineLane` +33, rebuild triggers on device change) + provisioned optimized models. Reader-side deltas trivial (ReaderTranslationController: import whitespace only). NO changes to `scheduling/`, `model/` projections, `persistence/` store, `PreparedPageBoundary`, `TranslationStreamRegistry`, or `CleanedPublication` — every prior-audit structural fact re-verified at new line numbers (detached async :1182; nativeGuard serialization; permit floor 1; 210 s drain; 150 ms debounce; 2/4/6 channel).

## Limitations

Static read-only at HEAD 4de121bf; no device/telemetry runs. Per-page native cost (~5-6 s) is a prior-audit carryover and cleaned-image decode/UI-swap bounds are NOT measured in this round — no numeric claims in this report should be treated as measurements. CoordinatorTest claims taken from the orchestrator (tests not re-run here). T3 retry-storm behavior is bounded by window membership: retries occur only while the page remains desired/not display-ready and an external reconcile releases them; forward navigation evicts pages behind the anchor.
