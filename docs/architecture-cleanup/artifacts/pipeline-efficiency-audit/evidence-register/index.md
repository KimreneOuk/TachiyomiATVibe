---
title: "Audit Evidence Register"
kind: spec
comments: none
---

# Evidence Register

Append-only register of verified facts and claims. Every entry carries: claim, evidence (@line / measurement / source), classification, and who established it.

Classification legend: **FACT** (directly supported), **MEASURED** (controlled evidence), **SUSPECTED** (needs investigation), **PROPOSAL** (undemonstrated), **VALIDATED** (reproduced gain).

## F1. Manual-mode run scope includes pre-coroutine queue time

- **Claim:** Run `totalMs` for manual translations starts before the page coroutine runs, so it includes schedulerâ†’dispatch latency and the `LEASE_WAIT` span.
- **Evidence:** `scheduling/TranslationScheduler.kt:346-371` â€” `startSchedule`/`startRun` precede `scope.launch`; `LEASE_WAIT` span opened at line 364, ended at 370 inside the coroutine.
- **Classification:** FACT (orchestrator, committed revision 0f00a45f)

## F2. Instrumentation covers more stages than the historical report quoted

- **Claim:** Trace spans exist for decode, detect, segment, OCR, inpaint, translate, governor wait, layout, render, flush, commit, engine setup, fingerprint, store writes.
- **Evidence:** SinglePageOnnxPhase.kt:189; RoiPageRecognitionEngine.kt:514/540/594/921; SinglePageHttpRenderPhase.kt:102/497/524/736/745/956/995/1049; BatchChapterTranslator.kt:311/418/1067; ChapterTranslationStore.kt:837/1067.
- **Classification:** FACT (orchestrator)

## F3. Historical benchmark's "Total Wall" scope is not yet established

- **Claim:** The 17.3 s average wall vs â‰ˆ5.6 s stage sum discrepancy has no established cause; r10 (10.1 s wall with largest OCR 3,147 ms) shows wall is not monotonic in stage sums.
- **Evidence:** docs/paddle_ocr_performance_investigation_report.md Â§4.1 table.
- **Classification:** SUSPECTED gap composition; under investigation by Agents A+B.

## F4. Provider request governor exists and is lane-instrumented

- **Claim:** Provider requests pass through a governor that enters the PROVIDER trace lane.
- **Evidence:** engines/translator/ProviderRequestGovernor.kt:440.
- **Classification:** FACT (orchestrator)

## F5. Working-tree modifications belong to the concurrent OCR orchestrator

- **Claim:** Uncommitted changes: +970 lines across 6 OCR files + tests; diagnostics deltas are trivial (`LITERT_GPU` provider enum + 3-line diagnostics change). Trace-structure conclusions are valid for committed revision.
- **Evidence:** `git diff --stat` at audit start.
- **Classification:** FACT (orchestrator)

## F6. Duplicate manual requests do not restart work

- **Claim:** Repeated page-selection events no longer cancel+relaunch; dedup via `activePageJobs`/`inFlightPageKeys` prevents bitmap re-decode churn from double-taps.
- **Evidence:** scheduling/TranslationScheduler.kt:329-345 (comment documents prior cancel-restart bug and its fix).
- **Classification:** FACT (orchestrator) â€” historical fix, already in place.

## F7. Structured telemetry vocabulary and tooling already exist

- **Claim:** The app emits structured per-page telemetry: `budget_summary` events (`totalNativeMs`, `detMs`, `ocrMs`, `inpaintMs`, `budgetMet`, native-vision goal â‰¤ 2000 ms) and `arrival_wait_recorded` (auto-reader stutter waits). Analyzer: `scripts/analyze_latency_budget.py`; noise-free logcat capture: `scripts/trace_stream.ps1/.sh` (device default `app.kanade.tachiyomi.vibe.debug`).
- **Evidence:** scripts/analyze_latency_budget.py:22-95; scripts/trace_stream.ps1:48-64.
- **Classification:** FACT (orchestrator)

## F8. No system-level trace integration

- **Claim:** Translation diagnostics never touch `android.os.Trace`/Perfetto APIs; all timing is app-level logcat. System attribution (thread contention, GC, thermal, GPU) requires external profiling.
- **Evidence:** grep for `Perfetto|Trace.beginSection|beginAsyncSection` in translation/diagnostics â†’ no matches.
- **Classification:** FACT (orchestrator). Instrumentation gap.

## F9. Device available but measurement authorized static-only

- **Claim:** OnePlus PKG110 (SM8650, Android 16) reachable at adb 192.168.100.223:36539 with `app.kanade.tachiyomi.vibe.debug` installed; logcat buffer empty of telemetry at audit start. User directed: no device workloads this audit.
- **Evidence:** adb devices output; pm list packages; user decision Oct 13 2026.
- **Classification:** FACT (orchestrator). All quantitative conclusions remain UNVERIFIED until an authorized measurement session.

# Agent A findings (pipeline investigation, HEAD 0f00a45f; orchestrator-verified where marked [V])

## A0. Reconciliation verdict for the 11.7 s gap

- **Claim:** H2 (queueing/admission waits inside the wall, outside the quoted stage timers) is the primary mechanism; H1 (reader pacing) sets the bracket when wall is reader-side. The report's 6 columns are not a wall partition - 15 span classes sit invisibly inside the run bracket: LEASE_WAIT, NATIVE_QUEUE, PREPARED_QUEUE, PREPARED_SEND_WAIT, PROVIDER_WINDOW_WAIT, PROVIDER_GOVERNOR_WAIT, RENDER_PERMIT_WAIT, JOURNAL_CREDIT_WAIT, DEFERRED_STORAGE_DRAIN, CLEANED_PERSIST, STORE_COMMIT, STORE_FLUSH, ENGINE_SETUP, LAYOUT, RENDER. DevicePagePermitGate capacity pinned to 1 (AdaptiveKnobController.kt:80; TranslationPipeline.kt:281-282); single translate consumer; runs start at admission (manual TranslationScheduler.kt:352-363, auto RollingAutoCoordinator.kt:855-862). No fixed coordinator cadence exists (event-driven conflated channel; only 150 ms reader debounce, ReaderTranslationController.kt:396).
- **Classification:** SUSPECTED (mechanisms FACT, magnitudes unmeasured pending F9).
- [V] Orchestrator: consistent with F1/F11 and B1; adopted as the working verdict.

## A1. [V] Detached inpaint async - false overlap + unattributed CPU contention (top finding R1)

- **Claim:** Single-page inpaint runs on a detached CoroutineScope(Dispatchers.Default).async - no parent job (cancellation does not propagate), outside NativeRunQuarantine/DevicePagePermitGate - yet still serializes against the next page analyze() on RoiPageRecognitionEngine.nativeGuard. The overlap is false (serialized anyway) but its CPU contention is real and unattributed; possibly 10-30 % OCR-time inflation on dense pages (high uncertainty).
- **Evidence:** pipeline/SinglePageOnnxPhase.kt:1182 (detached async - VERIFIED by orchestrator), :1195-1200 (outer INPAINT span), RoiPageRecognitionEngine.kt:160/507/932 (nativeGuard); defensive cancel only at SinglePageHttpRenderPhase.kt:1068-1070.
- **Classification:** FACT mechanism / SUSPECTED waste magnitude. Requires careful redesign preserving nativeGuard semantics.

## A2. [V] Nested-span double counting - stageSumMs overcounts (R2)

- **Claim:** Outer OCR span wraps analyze() which emits its own DETECT/SEGMENT/OCR spans, so recorded OCR total = (det+seg+ocr) + ocr. Inpaint double-wrapped identically. Instrumentation defect, no runtime waste.
- **Evidence:** SinglePageOnnxPhase.kt:1110-1117 (verified verbatim), RoiPageRecognitionEngine.kt:514/540/594/921 (F2); batch BatchLaneWorkers.kt:544-563 same pattern.
- **Classification:** FACT. Fix = stop nesting or make stageSumMs a partition.

## A3. RenderColorEstimator recomputed twice per page (R3)

- **Claim:** recomputeFor scans the cleaned bitmap per block in the inpaint job (SinglePageOnnxPhase.kt:1203, verified) AND again at render (SinglePageHttpRenderPhase.kt:756). One full-bitmap pass/page redundant (~10-100 ms, low certainty). Render-side call is load-bearing (freshness after JPEG publish) - drop the inpaint-side one.

## A4. Batch inpaint path always re-decodes the page (R4)

- **Claim:** Overlap/serial inpaint drains re-decode because nativeHandoff only flows preflight-to-immediate (BatchLaneWorkers.kt:656-670; PreflightWorker.kt:607). Cost: ~19 ms + hash + byte copy per page + churn. Memory-safety motivated.

## A5. Double SHA-256 + runBlocking in fingerprint path (R5)

- **Claim:** Every decode hashes the whole buffered file even when a fingerprint exists (PageDecode.kt:53-65); LazySourceFingerprints hashes again via runBlocking(Dispatchers.IO) inside computeIfAbsent (BatchChapterTranslator.kt:1221-1228) - blocks a carrier thread. ~10-40 ms/page + carrier blocking. Low-risk fix: pass known fingerprint into decode.

## A6. Per-page full store.flush() before every batch OCR checkpoint (R6)

- **Claim:** PreflightWorker flushes the whole store drain (lazy JPEG tasks + journal barrier + fsync) before EVERY per-page checkpoint (PreflightWorker.kt:198-201). SUSPECTED seconds/page on SAF storage with backlog. Needs narrower flush or checkpoint-identity decoupling.

## A7. Per-page manifest CAS transactions (~360 KB JSON) (R7)

- **Claim:** Sidecar-pointer manifest CAS + inventory journal capture per page registration; run-record phases rewrite JSON+SHA-256+CAS (ChapterTranslationStore.kt:3824-3895; ChapterProfileBatchCoordinator.kt:1192-1230). Envelope hydration got coalescing (32-publication batching; historical ~340 rewrites per 206-page chapter - documented ANR contributor) but other seams did not.

## A8. [V] Concurrency pinned at floor=1 is deliberate (R8)

- **Claim:** appliedConcurrency = floor with comment "Applied E20a output. The E20b flip will deliberately remove this pin" (AdaptiveKnobController.kt:79-80, verified verbatim). DevicePagePermitGate capacity 1 serializes all native work. NECESSARY today (correctness-proofing phase); raising is gated on E21 measurement, not an audit action.

## A9. ~12 DataStore preference reads per page under rebuild mutex (R9)

- **Claim:** EngineLane.ensureEnginesBuiltFor reads ~12 prefs/page inside engineRebuildMutex (EngineLane.kt:280-298, 530-565 via SinglePageOnnxPhase.kt:345-347). Under 5 ms/page; small.

## A10. Always-on full-page panel detection, uninstrumented (R10)

- **Claim:** assignPanels -> panelDetector.detect(bitmap) is a full-page pass per page (skipped only for webtoon/LTR), inside the outer OCR span but in no inner span (RoiPageRecognitionEngine.kt:1057-1118). Webtoon sliding windows multiply det/seg passes per tall page (WebtoonSlidingDetector.kt:152-230). Measure first, then consider mode-gating.

## A11. Other verified facts from A trace

- criticalPathMs is literally wallMs (TranslationPipelineDiagnostics.kt:885) - misleading name, no critical-path computation.
- Engine init happens lazily INSIDE the first OCR/INPAINT span on reader paths (RoiPageRecognitionEngine.kt:478); ENGINE_SETUP span exists only in batch (BatchChapterTranslator.kt:311).
- ONNX calls are plain blocking calls in suspend fns; runInterruptible used nowhere in translation (only HttpPageLoader.kt:52) - native cancellation lag unmeasured.
- r10 anomaly: statically undecidable; candidates = last-page no-successor queue, sweep truncation (RollingAutoCoordinator.kt:350-371), or shorter traceTotalMs bracket (SinglePageOnnxPhase.kt:1245-1253).
- Mechanisms judged sound/necessary: EngineLane, NativeRunQuarantine, RollingAutoCoordinator, OverlapScheduler, BatchLaneWorkers (one waste: A4), TranslationMemoryBudget, MemoryGovernance, DevicePagePermitGate, PageWorkPlanner, PreparedPageBoundary. AdaptiveKnobController: dormant by design (E20a).

# Agent B findings (independent research & benchmark audit, HEAD 0f00a45f)

## B1. Historical wall time is flat while stage sums vary 2Ã— â€” wall is not a compute measurement

- **Claim:** Across r2â€“r9, reported wall is 17.0â€“20.4 s (mean â‰ˆ17.6 s Â± 1.7) while stage sums vary 3.8â€“7.5 s (2Ã—). Wall correlates weakly with page density. Flat wall over 2Ã— workload = fixed-cadence or scope-inclusive measurement, not pipeline compute. r10 (10.1 s wall with the largest OCR) is a scope break, not noise.
- **Evidence:** Agent B recomputation from docs/paddle_ocr_performance_investigation_report.md Â§4.1.
- **Classification:** MEASURED (from historical data; interpretation). Strongly constrains Phase II: the gap is dominated by waiting/pacing/scope, not unmeasured compute.

## B2. Historical report mixes timing scopes and understates variance

- **Claim:** Â§6 "Vision ~2.5 s" uses waitMs (2.54 s) not durationMs (2.79 s); Â§4.2 overhead claim 130â€“170 ms is contradicted by r3 (86 ms) and r9 (82 ms); "verified linear" on n=9 is not demonstrated; exec-summary "~900 ms moderate" rests on n=2.
- **Evidence:** report Â§4.1/Â§4.2/Â§6 cross-check.
- **Classification:** FACT (arithmetic). The historical report is not comparable as a baseline without scope re-establishment.

## B3. Decode 19 ms is implausible for a full-page decode â€” decode cost likely outside the timed scope

- **Claim:** 19 ms cannot cover full JPEG decode + ARGB_8888 allocation of a 1000â€“2000 px manga page (1080p ARGB_8888 â‰ˆ 8.3 MB); the 19 ms "Decode" likely measures copying an already-decoded/downsampled reader bitmap; the real decode lives outside the timed scope.
- **Evidence:** Agent B prior from Android bitmap docs; needs Agent A code confirmation of SOURCE_DECODE span scope (question 11).
- **Classification:** SUSPECTED (high plausibility).

## B4. Hypothesis ranking for the ~11.7 s gap

- **H1 (HIGH):** wall includes reader/arrival pacing (auto-window dwell or inter-page delta; reading cadence ~15â€“20 s/page).
- **H2 (HIGH):** queueing/admission inside wall, outside stage timers (EngineLane waits, permit wait; queueMs tracked but not quoted in report).
- **H3 (MED-HIGH):** provider governor quota waits (ProviderRequestGovernor pollIntervalMs=50, maxInFlight, WaitResult.Wait).
- **H4 (MED):** persistence on critical path (StoreWriteDrainCoordinator â‰¤2 s drain joins, PageStoreWriter, cleaned-image re-encode ~100â€“300 ms/page prior) â€” explains 1â€“3 s, not 12 s.
- **H5 (MED-LOW for gap; REAL for stage inflation):** bitmap re-decode/copies; HARDWARE-bitmap readback copies.
- **H6 (LOW):** session/model init (r2 not an outlier â†’ amortized before window).
- **H7 (LOW for 9 pages):** thermal (8 Gen 3 holds ~64% under 15-min sustained load â€” matters for 30+ page chapters, not 9).
- **H8 (LOW):** GC pressure (variance, not constant).
- **Classification:** SUSPECTED with discriminating-evidence plan (B's 15-question checklist routed to Agent A).

## B5. External findings with sources (Agent B)

- **ORT threading:** XNNPACK EP runs its own threadpool; ORT-recommended trio = XNNPACK intra_op = physical cores, ORT intra_op = 1, spinning disabled. If `PaddleOcrSessionFactory` doesn't apply this, CPU stage times carry pool-contention tax. XNNPACK op coverage is limited; unsupported nodes fall back. Sources: onnxruntime.ai thread-management + XNNPACK EP docs.
- **NNAPI:** FP16 relaxation trades accuracy; NNAPI CPU fallback often slower (disable it); NCHW worse than NHWC on NNAPI. Corroborates avoiding NNAPI for det/rec. Source: onnxruntime.ai NNAPI docs.
- **LiteRT GPU:** serialized kernels cut init up to 90%; FP16 GPU has a documented silent-miscompute class (issue #8619) â†’ any GPU path needs a numerical parity gate. Sources: developers.google.com/edge; GitHub.
- **LaMa on 8 Gen 3:** Qualcomm AI Hub lists LaMa-Dilated 512 supported; realistic per-call prior ~0.3â€“0.8 s pure inference â†’ the app's 1.1 s avg implies ~0.3â€“0.7 s pre/post/copy overhead = the optimizable part.
- **Stage numbers credible:** det 185 ms, seg 270â€“400 ms, rec ~90â€“140 ms/leaf-equivalent are within expected CPU ranges for this SoC â€” it's the wall that's broken, not the stages.
- **Comparable pipelines:** manga-image-translator (same stage order options, inpaint-size knob); BallonsTranslator (strictly sequential per page BUT first-class stage-result caching / partial re-runs). TachiyomiATVibe inpaints BEFORE translating (required for NPU batching) â†’ a translation failure still pays inpaint cost; worth an explicit design note.

## B6. GPU-rejection rationale in the historical report is stale

- **Claim:** Â§2.1 rejects GPU for OCR due to dynamic shapes forcing shader recompilation, but Â§3.2's own width bucketing creates exactly 5 fixed shapes; with disk-serialized GPU kernels (up to 90% init cut) the argument no longer holds. Re-test warranted with parity gate; do not assume the report's conclusion still holds. (Re-test execution belongs to the OCR orchestrator's domain â€” this audit only records the stale rationale.)
- **Evidence:** report internal contradiction; LiteRT/ORT docs.
- **Classification:** FACT (logic) + PROPOSAL (re-test). Route to OCR orchestrator as evidence-backed recommendation.

## B7. Highest-value instrumentation ask

- **Claim:** ONE page-level telemetry line decomposing wallMs into named scopes (queueMs, laneWaitMs, permitWaitMs, governorWaitMs, journalDrainMs, artifactWriteMs, arrivalWaitMs, decodeMs, translateMs, totalNativeMs) + per-stage monotonic [start,end] pairs for post-hoc Gantt would resolve H1/H2/H3/H5 in a single capture. Most counters already exist but are not correlated; `budget_summary` covers only det+seg+ocr+inpaint (NativeVisionTelemetry.kt:23-31).
- **Classification:** PROPOSAL (host-side analyzer extension is safe now; app-side emission requires authorization + coordination since diagnostics files are OCR-orchestrator-owned).

## F10. Orchestrator verification of B7 + extension: render-tail and dispatch telemetry already exist

- **Claim:** Confirmed `budget_summary` = det+seg+ocr+inpaint only (`totalNativeMs` sums those 4; translate invisible) â€” NativeVisionTelemetry.kt:13-41. ADDITIONALLY the same file emits `render_tail_breakdown` (`permitWaitMs`, `colorEstimateMs`, `commitMs`, `totalTailMs`; lines 61-77), `tap_to_dispatch` (`delayMs`; lines 79-89), and `display_attach` (lines 91-105). Permit wait is ALREADY measured â€” the historical report never quoted it and `analyze_latency_budget.py` never parses it.
- **Evidence:** pipeline/NativeVisionTelemetry.kt (committed revision 0f00a45f).
- **Classification:** FACT (orchestrator). Strengthens B7: correlation, not new counters, is the missing piece.

## F11. `schedule_end` already decomposes the full wall â€” the reconciliation requires NO app changes

- **Claim:** Each schedule emits `wallMs`, busy (`nativeBusyMs`/`providerBusyMs`/`renderBusyMs`/`workMs`), union/overlap (`unionActiveMs`, `overlapMs`, `concurrencySavingsMs`), and itemized waits: `leaseWaitMs`, `nativeQueueMs`, `preparedQueueMs`, `preparedSendWaitMs`, `providerWindowWaitMs`, `providerGovernorWaitMs`, `renderPermitWaitMs`, `deferredStorageDrainMs`, `cleanedPersistMs`, `storeCommitMs`, `storeFlushMs`, plus `maxQueueMs`, `slowestPage`, `bottleneck`. `run_end` emits `queuedMs`, `totalMs`, `stageSumMs`, `bottleneckMs`, `retries`. `stage_start`/`stage_end` emit lane/stage/queueMs/durationMs/totalMs â€” full Gantt material. Lines carry `schema=translation_trace_v1` on tag `TachiyomiAT.Translation` with `sid`/`rid`/`mode`/`page` identity.
- **Evidence:** diagnostics/TranslationPipelineDiagnostics.kt:826-949 (identityPrefix, scheduleEndRecord, runEndRecord, stageStartRecord), :166 (TAG), :1178 (SCHEMA).
- **Classification:** FACT (orchestrator). Consequence: the historical report's failure to reconcile is a CONSUMPTION gap (it quoted only budget_summary-class numbers), not an instrumentation gap. One logcat capture + analyzer parse of schedule_end/run_end resolves the 11.7 s question without touching app code.

## F12. Orchestrator verification of Q9: OCR sessions keep intra-op spinning enabled alongside XNNPACK's pool

- **Claim:** XNNPACK-route OCR sessions are built with `setInterOpNumThreads(1)`, `setIntraOpNumThreads((cpuCores/2).coerceIn(2,4))` (=4 on 8 Gen 3), spinning disabled ONLY when `disableIntraOpSpinning=true` is passed â€” but `PaddleOcrSessionFactory.createSession` never passes it (default `false`). The fixed-AOT path (line 577) does disable spinning unconditionally; OCR det/rec sessions do not. Combined with XNNPACK EP's internal threadpool, this matches ORT's documented contention anti-pattern (B5). Expected magnitude: stage-time inflation (tens of ms/stage), NOT the 11.7 s gap.
- **Evidence:** engines/runtime/onnx/OnnxRuntimeProvider.kt:589-643 (`disableIntraOpSpinning: Boolean = false` default; thread config), :577 (AOT path spins off), engines/runtime/onnx/PaddleOcrSessionFactory.kt:185-216 (createSession without the flag).
- **Classification:** SUSPECTED inefficiency (mechanism confirmed in code; magnitude unmeasured â€” device verification blocked by F9). Candidate fix belongs with the OCR orchestrator (files are in its ownership scope).

## T1. Host-side reconciliation analyzer built and self-tested

- **Claim:** `analyze_wall_budget.py` (stored beside [measurement plan](../measurement-plan/index.md), OUTSIDE the repo per read-only discipline) parses both event families, reconstructs schedule decomposition, run residue (`totalMs âˆ’ stageSumMs âˆ’ queuedMs`), stage overlap/nesting detection, quoted-baseline check, percentiles, CSV export. Self-test passes on synthetic lines matching exact committed formats.
- **Evidence:** script self-test `[selftest] PASS` against formats from TranslationPipelineDiagnostics.kt + NativeVisionTelemetry.kt + TelemetryTrace.kt.
- **Classification:** FACT (tooling ready); measurements still UNVERIFIED pending authorized device capture (F9).

# Cross-audit outcome (Agent B audits Agent A; orchestrator-adjudicated)

## X1. Reconciliation model settled: H2 = ledger, human cadence = clock

- **Claim:** Pure queue-saturation (H2 alone) would make wall track density with slope ~1.0; pure dwell-hiding would give slope ~0. Observed least-squares slope of wall on stage-sum across r2-r9 is ~0.30 (r-squared ~0.08). Therefore the wall bracket starts at reader-side page arrival/admission, the ~12 s sits in the 15 unquoted span classes, and the flatness comes from event-driven human reading cadence (no fixed timer exists; 150 ms reader debounce conceded as not-a-cadence).
- **Evidence:** Agent B regression on its own B1 table; consistent with A0 run-start-at-admission facts.
- **Classification:** MEASURED (from historical data) + FACT (mechanisms). EXP-0 gains admission_lag emission.

## X2. Governor (H3) eliminated as gap contributor in BOTH modes

- **Claim:** Agent B challenged: maxInFlight=1 + rpm=60 implies >=1 s between requests, so 9 sequential requests would need 8-9 s translate - contradiction with the 1.2-2.9 s table. Resolved by A-Q6: requests-per-page = 1 (translateOnce) or ~0.2 (AI envelopes), never 9. Governor never throttles given multi-second page work. Batch rate-bound concern does not materialize.
- **Classification:** FACT (code evidence). PROVIDER_GOVERNOR_WAIT / PROVIDER_WINDOW_WAIT spans already carry the per-page proof for EXP-0.

## X3. Backlog order settled: R2 (nested spans) first

- **Claim:** The nested-span defect corrupts every downstream number (including criticalPathMs == wallMs, a vacuous field). Fix spans before crediting any R-finding magnitude. Also required: per-page inpaint execution-target logging (LiteRT GPU-fallback vs HTP - determines whether R1 contention is bounded or large), admission_lag, governor-wait parsing. R5 reframed as robustness fix (runBlocking inside computeIfAbsent = thread-starvation hazard). R9 skipped; R3 only if trivial; webtoon sliding-window multiplication ranked MEDIUM (unmeasured structural cost); XNNPACK trio + GPU re-test stay with OCR orchestrator.
- **Classification:** ADOPTED (both agents concur; orchestrator adjudicated).
