# T924 Stage 7 — Progress report (incl. wave-7c provider-package transport)

Task: T924 chapter-profile contextual-AI Batch translation pipeline (S7, WP7 + WP9 gate).
Branch: `t924/batch-profile-pipeline` · Worktree: `..\TachiyomiAT-t924-impl` · HEAD at report time: `571b9f1`.
Prior report: `../stage6/progress-report.md`. Wave-7a independent review: `../wave7-review.md` (ACCEPT).

## Process deviation — disclosed up front

Stage 7 and wave-7c ran WITHOUT independent review. The original W7b
implementer agent died mid-run at ~79 minutes ("Model request failed"),
and every subsequent Agent launch (implementer and reviewer probes) failed
instantly with `Model provider is not configured: builtin:zai-coding-plan`.
Under the Director's instruction ("Okay just do everything your own"), the
orchestrator (Main Leader) completed Stage 7 solo and implemented wave-7c
solo. Everything below the wave-7a review therefore carries NO independent
verification — the Director should treat this report as implementer-
authored only. The dead agent's partial work was resumed, root-caused, and
finished; nothing from it was discarded silently.

## Wave 7b — Stage 7 COMPLETE (commit `3dd1181`, 18 files, +1763/−75)

Implemented across a resumed effort (agent bulk + orchestrator completion,
both inside this one rollback commit):

1. **`pipeline/batch/OverlapScheduler.kt` (new, 371 lines)** — the
   EXISTING serial native inpaint lane runs INSIDE the single in-flight
   remote request window (ST-13). A `WindowSignallingGate` wraps the
   shared `BatchRequestSublimitGate` (now `open`; admission semantics
   unchanged — DR-C one allowance still holds). Strictly ONE native
   inpaint at a time (Never rule #1); BATCH lease attaches behind MANUAL
   (denial defers the page for the pass); identity registration fences
   the lane's guarded merges; TX-06 lease release after settle.
   Gate-6.5 counters: `overlapInpaintsExecuted/Failures`,
   `serialInpaintsExecuted`, `overlapWindowsCount/Ms`, `serialFallbacks`
   (per-page accounting lives in `inpaintOne`; the accounting is
   per-page-serial, not per-window).
2. **Per-page persisted-layout publication** —
   `BatchRenderJoin.publishPersistedLayoutForCompletedPage`
   (T924-TX-23): FF-02-gated, idempotent, evidence-fenced (translation
   READY/PARTIAL + inpaint READY + drawable blocks + ARTIFACTS
   authority); ANY failure keeps the async planner fallback — reader
   display never breaks, R041 no rasters. Fired from the overlap commit
   hook AND a FINALIZE sweep.
3. **Reader bridge install behind FF-02 (default OFF)** —
   `ReaderViewModel` installs the chapter hydration source on store open
   (null on close); `pageKey` plumbed
   `ReaderTranslationOverlayBinding → ReaderPageImageView →
   TranslationOverlayView`; the overlay consults
   `PersistedLayoutReaderBridge` BEFORE the async planner; every
   non-Resolved outcome falls back. Flags OFF = today's behavior.
4. **`runFinalizeAndComplete`** — FINALIZE record → `drainSerial` (the
   legacy post-translate keep-serial arm) → layout sweep → stranded
   sweep → NonCancellable flush + retention → COMPLETE record.
5. **T924 stranded predicate** — the legacy `BatchProgressReconciler`
   terminal (`hasRenderedResult`) is WRONG for the T924 pipeline (T924
   terminal pages are READY/PARTIAL/FAILED/SKIPPED/TEXTLESS or
   fully-user-edited without a rendered result yet). The coordinator's
   FINALIZE stranded sweep uses a T924-specific predicate; legacy
   reconciler untouched.
6. **F1 `decideResume` production wiring** —
   `resumeCompletedOutcome` + `activeRunRecordOrNull`: a COMPLETE run
   record short-circuits re-entry (the legacy schedule treats COMPLETE
   as finished); flag-ON with no COMPLETE record keeps dispatch
   byte-identical.
7. **Gate 7.8 display-ready completion encoded OFF** —
   `GATE_7_8_DISPLAY_READY_COMPLETION_ENABLED = false` (companion
   constant): device-gated, no activation without Director evidence.
8. **Counter-budget fix found by test bring-up**: COMPLETE records with
   36 phaseCounter keys were silently rejected by the durable store's
   `MAX_PHASE_COUNTER_KEYS = 32` cap (rejection is log-only), so the
   overlap counters moved to the FINALIZE record; COMPLETE stays under
   the cap.
9. Tests (+3 files / +2 updated, 406-line `Stage7FinalizeCoordinatorTest`
   incl. ST-12 typed-PAUSE-on-missing-block semantic: a missing planned
   block pauses the run typed — it is NEVER drained to COMPLETE;
   terminals updated to Stage-7 completion semantics).

Verification at `3dd1181`: 5-package targeted suites **873/0** (one
`StandardLaneMultiPageCompletionTest` timing flake under first-run load —
isolated + full rerun green); full `eu.kanade.translation.*` tree
**1739/0**.

## Wave 7c — provider-package transport + V8 feasibility (commit `571b9f1`, 12 files, +450/−6)

1. **V8 infeasibility FIXED at the request side.** The wave-3 validator
   demands each evidence anchor echo a 16-hex SHA-256 prefix of the cited
   block text — impossible for an LLM to compute. The request wire now
   carries per-block `excerptHash` (`"e:" + 16-hex` prefix of
   `StageFingerprints.sourceExcerptHash(text)`), computed at build time in
   `AnalysisChunkExecutor.toRequestPages` and emitted per block by
   `AnalysisRequestBuilder`. The model echoes it VERBATIM (echo discipline
   stated in the transport's framing prompt); the validator recomputes.
   Byte-determinism of the request document is preserved (pinned).
2. **`AnalysisEngineTransport` (new, 101 lines)** — adapts
   `AiTranslator.postStructuredAnalysisRaw` to the T924-AP-02
   `AnalysisTextTransport` seam. Identity triple
   (providerId/modelId/credentialSignature) from engine hooks; AP-01
   framing prompt constants; typed failures only (IO → NETWORK/PAUSE;
   typed pass-through; cancellation never swallowed); ONE raw attempt per
   call — admission/retry stay in `AnalysisChunkExecutor` behind the
   shared 15-RPM sub-limit gate.
3. **`AiTranslator` analysis hooks (4 open members)** — default = typed
   CONFIGURATION/TERMINAL pause ("engine has no structured-analysis
   transport"). `GeminiTranslator` overrides all four (backend `gemini`,
   model `modelName`, credential = `ShortHash.hash(apiKey)` 16-hex —
   never a raw key, T924-FP-04). `OpenAiCompatibleTranslator` overrides
   with `providerBackend`/`providerModel`/`providerCredentialScope` plus
   `analysisEndpointUrl()`/`analysisHeaders()` seams; DeepSeek, OpenRouter
   and LM Studio subclasses supply endpoint + Bearer headers (LM Studio:
   base-URL credential, empty auth headers — its credential is the URL).
4. **F-W6-4 spelling alignment** — `BatchChapterTranslator` derives the
   providerKey engine part from `aiEngine?.analysisBackendId` (governor
   spelling, e.g. `lm_studio`) instead of enum lowercase (`lmstudio`),
   so the envelope work builder's `substringBefore(':')` derivation and
   every Batch admission key share ONE spelling per backend.
5. **Production wiring** — `analysisChunkRunner` (the
   `AnalysisChunkExecutor.runner()` over the engine transport) is passed
   into `ChapterProfileBatchCoordinator`; engines with no raw completion
   keep the typed CONFIGURATION pause. The seam is no longer test-only.
6. Tests: `AnalysisEngineTransportTest` (identity triple, framing +
   budget, construction fence, IO→typed matrix, pass-through,
   cancellation); `AnalysisChunkValidationTest` RequestBlocks carry real
   hashes.

Verification at `571b9f1`: full `eu.kanade.translation.*` unit tree
**1872/0 across 255 suites** (0 failures, 0 errors, 0 skipped). This is
orchestrator-run only — not independently verified (see disclosure).

## Build artifact (Director-requested)

`./gradlew :app:assembleStandardDebug` at `571b9f1` — BUILD SUCCESSFUL.
APKs in `TachiyomiAT-t924-impl/app/build/outputs/apk/standard/debug/`:
`app-standard-arm64-v8a-debug.apk` (302 MB) plus armeabi-v7a, x86, x86_64
and universal variants. Flags FF-01/FF-02 are compiled default-OFF; the
build is behavior-identical to main for every current user path.

## Owed items (unchanged or newly due; explicitly NOT done)

- **Device evidence (Director's own verification)** — gates 7.5/7.8 and
  the bridge device rows (wp9-report §6); the FIRST old-vs-new A/B at
  gate 5.7 with the F-W7-4 dense-page caveat and F-W7-3 built-vs-sent
  counter caveat.
- **FP-06** durable provenance home + reuse-invalidation matrix row 6.
- **DB-10** small-chapter bypass threshold (Stage 8).
- **F-W6-3** store strict-mode — layout commits now exist at Stage 7;
  reconsider strict mode when the bridge is device-verified.
- **Independent review debt**: Stage 7 (`3dd1181`) and wave-7c
  (`571b9f1`) have NO independent review. When the subagent provider is
  restored, a review pass over both commits is the top process debt.

## Rollback state

FF-01/FF-02 default OFF unchanged; OFF branches byte-identical; zero
schema-version changes in both commits; TX-20 rejections never advance
the frontier; the reader bridge is a consult-then-fallback overlay
source with no committed-display surface touched. One rollback commit
per slice: `3dd1181` (Stage 7), `571b9f1` (wave 7c).

---

## Device fix — 2026-09-06 evening (commit `73bbb0c`, on-device at 21:28)

**Director-reported live failure** (Chapter 21, Rawkuma JA, legacy lane —
FF-01 OFF): whole chapter batch FAILED with no provider call. Diagnosis
from logcat + `Chapter 21.manifest.json`: the translate-admission CAS
write for `001.jpg` was rejected (`pageVersion expected=27 actual=50` —
the gate's cached write identity drifted 23 versions behind ungated store
writes), and the admission handler classified that LOCAL CAS conflict as
PROTOCOL/TERMINAL for the whole 22-page envelope: `001.jpg` durably
FAILED_TERMINAL, 21 siblings cancelled (OCR work wasted), "batch stopped
before tail reconciliation". The observed "42.7s translate bottleneck /
providerBusyMs" was store-mutex serialization of the admission loop
continuing after the reject (~1.1s SAF publication per page), NOT provider
time — an observability caveat for any counter-based A/B.

Fix (three layers, shared store/gate code — covers legacy AND T924 lanes):
1. `BatchWriteGate.guardedBatchUpdate` — same-lease drift heal: on
   rejection, if the live snapshot still carries our generation+leaseToken,
   re-sync the identity and retry ONCE. Foreign lease/generation keeps
   rejecting (T917 manual fence never preempted).
2. `BatchWriteGate.persistBatchPageWithOomRecovery` — refresh identity on
   accept (OOM-recovery retry can commit under a rewritten precondition).
3. `BatchLaneWorkers` translate admission — STOP at the first rejected
   page; a still-rejecting admission is retryable PAUSE (T918 affordance,
   siblings keep committed work), never TERMINAL Failed.

Tests: `BatchWriteGateHealTest` (same-lease heal / foreign-lease fence /
lease-missing fast reject, real store + real gate). Full suite **1875/0**
(1872 + 3). APK rebuilt (`73bbb0c`) and reinstalled on the Director's
device (arm64, 21:28:58). FF-01/FF-02 remain OFF on device.

Open (not fixed, logged): the exact writer that bumped `001.jpg` +23 in
~105s was not conclusively identified (candidates: reader stranded-page
sweep via ungated `updatePageFromCurrentSnapshot`, OOM-recovery retries);
the heal makes the batch robust regardless, but the writer enumeration is
still worth one instrumented pass if it recurs. Also noted: the
trace's provider-busy attribution during store-only stalls, and the
native-queue drain (~47s) after a terminal envelope failure.

---

## Device fix 2 — retry affordance survives app restart (commit `aace869`, on-device 22:00)

**Director field report:** the progress sheet's Retry button appeared while
a failed batch's session was alive, but vanished after restarting the app —
a failed chapter had no in-app restart path.

Root cause (three stacked defects on the post-restart path):
1. `TranslationProgressSnapshot.compute`/`computeSnapshot` defaulted
   batchPhase to IDLE for an ERROR chapter; the sheet truth rule needs
   ERROR+FINISHED, which only the LIVE tracker ever emitted → default now
   FINISHED for ERROR (durable error = a run that ended).
2. `BatchProgressProjector.observeBatchProgress` hard-coded the no-queue
   fallback state to NOT_TRANSLATED — after restart (no tracker/queue)
   the terminal state was hidden even with an open store → callers pass
   `durableStateHint`; queue/live statuses still always win.
3. `MangaScreenModel` observed progress only for queued/requested chapters
   → `translationProgress` stayed null after restart and the sheet
   rendered an empty snapshot → durable-ERROR chapters now observe
   (read-through projection with the persisted state as the hint).

Tests: T918SheetRetryTruthTest reconstruction-shaped pin;
BatchProgressProjectorDurableReconstructionTest restart case. Full suite
**1877/0** (one known under-load drawer flake — isolated + rerun green).

Also this session: the Director's post-fix verification run on a new
chapter (24 pages, legacy lane) completed `outcome=success` (~5.5 min,
208s provider work, 114s overlap savings) — the admission fix holds in
the field.

---

## Device fix 3 — "completed with 4 images, no retry" (commit `25fe9fc`)

**Director field report (Chapter 21, post-22:00 build):** the chapter
failed mid-run yet the badge read as completed, with only 4 of 26 pages
rendered, and no Retry affordance anywhere.

**Device trace (USB, sid=f7e90997, `schedule_end … pages=26 …
outcome=persistence_rejected`):** one 163-item legacy translate envelope
failed with a provider contract error after 436s; the anchor's failure
persist was then CAS-rejected by the write gate (the known ungated-drift
writer); `BatchChapterTranslator` stopped with
`status=PERSISTENCE_REJECTED` and skipped tail reconciliation.
ChapterTranslator's `nonDurableFailure` branch then set the in-memory
chapter status to **READY_WITH_WARNINGS**, which the badge renders as the
same filled translated icon (warning tint only) and the sheet as
"Ready (Warnings)" — no Retry because the T918 truth only accepted ERROR.

**Director rule (priority 1, correctness):** READY_WITH_WARNINGS means
every expected page is displayable. Any real unresolved page (partial
never rendered, cancelled, stranded, persistence-rejected stop) must
resolve the chapter to a retryable ERROR.

Changes in `25fe9fc`:
1. `BatchProgressReconciler.reconcile` — a PARTIAL candidate with no
   rendered result counts as stranded/failed, never done+partial.
2. `BatchProgressReconciler.reconcilePaused` — PERSISTENCE_REJECTED
   resolves ERROR while keeping `nonDurableFailure=true` (no durable tail
   failures synthesized).
3. `StoreStatusProjector.artifactStatus` — the ERROR→RWW softener now
   applies only when every stranded key is a synthetic
   `__missing_expected_page_*` placeholder (upgrade residue), never for a
   real stranded page.
4. Affordance parity with the ERROR path: `forSheetRetryAction` accepts
   durable RWW+FINISHED; both compute sites default RWW → batchPhase
   FINISHED; MangaScreenModel surfaces durable-RWW terminal progress.

Tests: reconciler pins updated/new (persistence-rejection → ERROR,
partial-unrendered → ERROR), sheet truth pins (durable RWW offers Retry;
TRANSLATED never does), artifact-read pin flipped to the new truth
(interrupted store → retryable ERROR). Full suite **1880/0** (one
`ActiveChapterStoreRegistryTest` uncaught-exception flake under full-suite
load — isolated green, same pattern as the prior drawer/completion flakes).

Immediate unblock for the residue chapter (works on the installed build
today): long-press the chapter's filled translate badge → "Translate" —
the requeue reuses committed pages and re-runs only the remainder.

Still open from the trace: the 163-item/436s single-envelope contract
failure (translate-envelope sizing fragility, distinct from the T924
analysis chunking); the ungated drift writer behind the anchor's
failure-persist CAS rejection; native-queue 428s inpaint contract failure
on page 3 + its 145ms cleaned_persist failure.
