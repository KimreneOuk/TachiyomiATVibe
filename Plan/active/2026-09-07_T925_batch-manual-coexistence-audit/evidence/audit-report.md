# T925 — Batch ↔ Manual/Auto coexistence audit report

Date: 2026-09-07 · Base: `t924/batch-profile-pipeline` @ `25fe9fc` · Mode: read-only audit, no code changes.

Method: three parallel investigators (triggers/admission, shared resources,
state/data safety), each returning file:line-cited findings. Main Leader
personally re-verified the four load-bearing HIGH claims (marked **verified**
below). Cross-corroborated items were found independently by two slices.

---

## Verdict

The coexistence architecture is fundamentally sound: stage leases defer
batch-owned pages to the reader, manual taps attach to the batch owner's
terminal result, batch survives reader teardown, startup never auto-resumes,
and detector/OCR serialization holds across both modes. The dangers live at
four edges: the reader's cancel toggle, provider/native resource contention,
crash-shaped store state, and latent write fencing.

## HIGH findings

### H1 — Reader auto-translate toggle cancels a live batch's in-flight pages (the ungated drift writer) — **verified**

- ReaderViewModel.kt:603-609 (toggle OFF) → raw `TranslationScheduler.cancelAutoTranslations(chapterId)` — no batch-retained guard.
- TranslationScheduler.kt:534-546, :556 → `store.fastCancelInFlightStagesInMemory()`.
- ChapterTranslationStore.kt:752-778: lock-free wholesale `pages =` write from a `_state.value` read; flips EVERY running page to CANCELLED with no lease-origin filter and no mutex/CAS/generation bump. A batch commit landing between the read and the write is silently reverted (lost update).
- Also reachable from global auto-cancel (:556) and per-page cancel (:921, `markChapterCancelledSync` :873).
- Consequences: paid batch work poisoned to CANCELLED mid-OCR/inpaint; flipped pages count as stranded → false chapter ERROR (BatchProgressReconciler.kt:102-105); BatchWriteGate resync (BatchWriteGate.kt:107-126) heals some drift but the window is real.
- This is the ungated drift writer enumerated (owed item from the Chapter 21 investigation — it was CAS-rejecting failure persists).

### H2 — Provider governor can starve the reader behind batch envelopes — **verified**

- ProviderRequestGovernor.kt:70 `maxInFlight = 1` (default; :675 desktop), one process-wide `SharedProviderRequestGovernor` (:684-699); batch requests are BACKGROUND in the same bucket (ProfileEnvelopeExecutor.kt:561, AnalysisChunkExecutor.kt:152).
- selectWaiter (:533-546): a BACKGROUND waiter older than `interactiveMaxAgeMs` (30 s) is selected FIRST, ahead of any INTERACTIVE waiter — so during a multi-envelope batch, an old background waiter repeatedly beats the reader.
- waitOrDefer (:484-503): the reader request is Deferred after `maxForegroundWaitMs` (15 s) → manual tap shows Paused/no progress while a 436 s envelope runs (field-observed envelope duration).
- The interactive token reserve (:449-461) only shrinks background limits; it never preempts an admitted reservation.

### H3 — Native lane has no foreground priority; a hung batch native call blocks reader OCR for minutes

- NativeRunQuarantine.kt:43,67 — one process-wide Mutex shared by batch OCR AND inpaint AND reader requests (TranslationPipeline.kt:200-211, 1224-1233; BatchLaneWorkers.kt:923,1106). Released only after real native exit (:96-123) — the field's 428 s inpaint held the lane the whole time.
- Reader request suspends with no deadline; manual taps are typed Stalled only after the 90 s watchdog (TranslationPipeline.kt:403-405, NativeStallWatchdog.kt:62-77); the AUTO path has no stall check at all (TranslationPipeline.kt:874-924) — it queues silently.
- No preemption/priority hook exists anywhere on this lane (absence confirmed).

### H4 — Post-crash RWW masking survives via a different route than the Chapter 21 fix — **verified (structure)**

- StoreStatusProjector.kt:84-101: rehydrated RUNNING/candidate pages count as "in flight"; if trusted expectedPageCount > 0 OR any readable/partial artifact exists → READY_WITH_WARNINGS, even with zero readable pages.
- Rehydration source: LegacyChapterMigrationSource.kt:289-313, 382-408 rehydrates candidate snapshots as RUNNING.
- Same masking class the Director ruled against (priority 1: any unresolved page ⇒ retryable ERROR), but via the in-flight clause, not the partial-count path fixed at `25fe9fc`. The interrupted-store test fixture (TranslationManagerArtifactReadTest) pins ERROR because reopen fails RUNNING pages; the candidate-rehydration crash shape is the residual exposure.

## MEDIUM findings

| # | Finding | Evidence |
|---|---------|----------|
| M1 | TOCTOU: auto window can arm after batch admission → duplicate paid OCR/translate work (no corruption; batch defers AUTO pages, nothing shuts the rogue window down) | TranslationManager.kt:1554-1558, TranslationScheduler.kt:200-227 |
| M2 | `startTranslation` check-then-act can launch two translator jobs (leaked job, duplicate spend; dedup rests on `inFlightPageKeys` CAS) | TranslationManager.kt:780,847; ChapterTranslator.kt:274-289,368-371; PageStageLeaseTable.kt:99-114 |
| M3 | Download-completion rekey `runBlocking { cancelTranslatorJobAndJoin() }` cancels the GLOBAL translator job — interrupts a different chapter's live batch (state stays retryable) | TranslationManager.kt:1287; ChapterTranslator.kt:285,464-475 |
| M4 | Sheet Retry is a silent no-op on a retained PAUSED entry (`queueChapter` early-return; PAUSED excluded from pending; no feedback — Retry uses a different path than Resume's cooldown snackbar) | MangaScreenModel.kt:1024-1028; ChapterTranslator.kt:503,279-284; MangaScreen.kt:339 |
| M5 | Every heap-constrained batch decode trims the reader's ENTIRE Coil memory cache to 0 → reader re-decodes from disk, visible jank during long batches | PageDecode.kt:55,70,80-83 → MemoryGovernance.kt:75-82 |
| M6 | Legacy batch lane holds ~7 decoded full-page bitmaps across the provider envelope (6+1 lookahead, handoff released per-page finally) + up to 4 held cleaned bitmaps — contradicts the one-bitmap model; T924 profile preflight path is clean | SequentialBatchCoordinator.kt:73,575-597,987; HeldBitmapRegistry.kt:28-38; ChapterProfileBatchCoordinator.kt:423-427; OverlapScheduler.kt:36-38 |
| M7 | Reader single-page path holds the cleanedBitmap across the provider HTTP call (bounded to one bitmap; source bitmap correctly recycled before boundary) | SinglePageOnnxPhase.kt:493-505,994; SinglePageHttpRenderPhase.kt:325,544-583,677-683 |
| M8 | Wholesale-replace persists bypass block-level merge fencing — latent until a user-edit producer exists (none in main sources today); exactly the hole an edit UI would fall into | PageStoreWriter.kt:247-256; BatchRenderJoin.kt:185,336; merge CAS at ChapterTranslationStore.kt:1177-1206 |
| M9 | `markPageTimedOut` bumps store generation unconditionally → CAS-rejects subsequent batch writes; BatchWriteGate resync deliberately does NOT heal generation mismatches (suspected: depends on timeout firing on an attached job) | PageStoreWriter.kt:120; BatchWriteGate.kt:117; ChapterTranslationStore.kt:1418 |

## LOW findings

- Surface softener re-labels fresh ERROR with readable pages as "Translated with warnings" (TranslationUiTruth.kt:390-411 — documented spec §3.1 intent, but it is a mask; Retry truth unaffected).
- Provenance waiver: pages with NO recorded fingerprint are reused across provider/model switches (`provenanceRequired=false` default, PageWorkPlanner.kt:497-499). User edits always safe.
- Restored QUEUE entries can be picked up by any later queue mutation while the translator runs (admit-without-start only holds while idle) — TranslationManager.kt:626-634, ChapterTranslator.kt:371-410.
- New same-source start silently evicts earlier requested QUEUE entries (documented bug-3 design).
- Batch admission shuts down the reader's live auto window even for no-op admissions; retained entry keeps auto suppressed for the chapter (T917 D4, log-only).
- Manual tap on a batch-owned page attaches and can wait up to 210 s with no preemption (TranslationPipeline.kt:151,729-771) — typed and bounded, but appears unresponsive.
- Native ONNX/OCR work runs on `Dispatchers.IO` (retained through quarantine) + `System.gc()` pauses (NativeRunQuarantine.kt:69-71; MemoryGovernance.kt:82).
- Trace misattribution: the batch "provider" span includes ~1 s/SAF admission writes and commit store writes (BatchLaneWorkers.kt:338-343,420-440,606-435) — store stalls inflate provider-lane time in translation_trace_v1.
- Download rekey orphans sidecar files named for old keys (state stays consistent).

## Checked, no issue found

- Detector/OCR never parallel across pages — both modes serialize through the single quarantine; OverlapScheduler runs inpaint only inside remote-wait windows.
- No inpaint during OCR preflight (profile path terminal before TRANSLATE).
- Cleaned images: versioned new files, refcount retirement after reader streams close; store docs tmp→validate→rotate→rename — no partial-write-while-reading hazard.
- Batch survives reader teardown (app-lifetime SupervisorJob; eviction filters batch-retained chapters; stranded sweep is batch-guarded).
- Startup restore never auto-starts; no unrequested implicit starts (download hook is generation-fenced to an existing pending request).
- Manual-during-batch: page tap DEDUPES via lease attach (observes owner's terminal); batch defers reader-owned pages; no corruption path outside H1.
- Post-`25fe9fc` reconciler/projector coherence for the fixed paths holds.

## Not found (explicit absences)

- No per-page priority or preemption hook on the native lane (H3's root cause).
- No per-mode provider client separation — all modes share one governor (H2's root cause).
- No production writer of `userEditedAt` — edit UI does not exist yet; M8 is latent.

---

# POST-CHALLENGE REVISION (supersedes severities above)

An adversarial challenger re-read every cited file and attacked all findings.
The Main Leader independently re-verified each decisive counter against
source before accepting it. Rule: a claim whose defense fails is dropped.

## Dropped

- **M8 (wholesale-replace bypasses block-merge fencing)** — DEAD. No reachable
  writer exists today (`userEditedAt` has no producer), so no current
  coexistence defect. Retained only as a hardening remark: if an edit UI is
  ever added, this fencing hole must be closed first.

## Demoted

- **H4 HIGH → LOW (reframed).** Counter verified: `loadOrMigrate` runs
  `recoverInterruptedStages` on every ARTIFACTS-authority load
  (ChapterArtifactStore.kt:178-196), converting persisted RUNNING stages into
  FAILED_RETRYABLE durable failures (:1461-1507); artifactStatus then returns
  retryable PAUSED (StoreStatusProjector.kt:92) before the RWW branch. Common
  crash shapes are NOT masked. Residual: RWW only when a crash leaves a
  candidate snapshot with no durably-recorded RUNNING stage record, or when
  recovery publication fails. Narrow window, not the headline claim.
- **M1 → LOW.** Both sides guarded (arm-time suppression + admission shutdown);
  ms-scale interleave; batch defers AUTO leases so duplicate spend is
  speculative.
- **M2 → LOW.** Real check-then-act across threads, but a two-instruction
  window and damage contained to a leaked job (dedup via lease re-grant +
  inFlightPageKeys).
- **M4 → LOW.** No-op mechanism real, but no demonstrated UI state shows Retry
  on a retained-PAUSED entry (sheet wires Retry for terminal-failed dialogs;
  PAUSED presents pause/resume). Reachability unproven.
- **M7 → LOW.** Deliberate single-page envelope: source bitmap recycled before
  the boundary, exactly one cleanedBitmap held across the HTTP call and
  recycled after. Letter of the bitmap-barrier rule, not a memory regression.

## Upgraded

- **M9 → HIGH.** Counter verified and stronger than originally reported: the
  batch lane wires `onTimeout = markPageTimedOut` unconditionally
  (BatchLaneWorkers.kt:928 OCR, same at the inpaint lane), and
  `markPageTimedOut` bumps the shared store generation unconditionally
  (PageStoreWriter.kt:120). One page's native timeout therefore CAS-rejects
  every subsequent fenced write for the REST of the run —
  BatchWriteGate resync deliberately does not heal generation mismatches
  (BatchWriteGate.kt:117) — driving the run to PERSISTENCE_REJECTED.
  Field tie-in (hypothesis, checkable against the Chapter 21 trace): if the
  428 s inpaint crossed its lane timeout, this generation bump would have
  caused the anchor failure-persist CAS rejection recorded in that trace.
  A NATIVE_QUEUE timeout event preceding the CAS rejection in the trace
  would confirm it.

## Survived unchanged

- **H1, H2, H3, M3, M5, M6** and all LOW items — challenger's best attacks
  failed on mechanism, guard absence, and consequence chains. H1's toggle
  chain independently re-verified (ReaderViewModel.kt:600-618 →
  TranslationScheduler.cancelAutoTranslations :495/:535 →
  ChapterTranslationStore.kt:752-778, no batch guard at any hop).

## Final counts

HIGH: H1 (auto-toggle cancels live batch), H2 (provider governor starves
reader), H3 (native lane no priority), M9-promoted (page timeout poisons the
run's store generation). MED: M3 (download rekey stops global job), M5 (Coil
cache wipe), M6 (legacy lane bitmap lookahead). LOW: 13 items (demoted H4,
M1, M2, M4, M7 + original LOWs, minus dropped M8).
