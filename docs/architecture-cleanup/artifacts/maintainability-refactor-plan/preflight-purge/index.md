---
title: "Pre-refactor dead-code and test-reliability audit"
kind: review
comments: none
---

# Scope and status

Requested October 14, 2026 as the first step before the maintainability refactor. This is identification, not deletion or implementation. Production code, tests, build files, models and repository documentation remain unchanged at `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`.

**Status: primary source review recorded; test-reliability and OCR/native reports received and cross-checked; revisions requested for unsupported claims; dead-code agent report remains pending.** All three agents use GLM 5.3 Flash / maximum reasoning. The combined removal inventory is not yet final. No tests, builds or device measurements were run for this audit. Existing test reports were inspected; their checkout provenance is not established.

The user explicitly authorized these agents while the primary reviewer independently verifies findings. Each agent is restricted to source inspection and its own review artifact:

| Audit | Agent | Report |
| --- | --- | --- |
| Dead code, rendering and orchestration | `4c33bd25-ebd6-453f-9667-abec1ad3afa3` | [Independent dead-code audit](dead-code) |
| Test reliability and obsolete coverage | `9aadcbfd-8b04-4a52-9407-9fe6a73912bd` | [Independent test audit](test-reliability) |
| OCR, native backends and compatibility | `4f2576fb-a7aa-4b4c-9d44-0b250b806610` | [Independent OCR/native audit](ocr-native) |

Applied the Traycer review skill: inspect declarations, call-site wiring and removed-behavior risks; validate against actual source before recommending action. This review does not replace the [verified architecture findings](../../maintainability-verification) or [ownership plan](../ownership).

# What needs rebuilding

The verified architectural issue is **change amplification in shared page processing and unclear asynchronous operation ownership**. The relevant chain is `TranslationPipeline` → single-page phases / `BatchChapterTranslator` → `BatchLaneWorkers`. Shared recognition, retry and cleaned-image publication operations are exposed through multiple callback signatures and forwarding layers. A detached inpainting result crosses request/native lifetimes without an explicit registered-result ownership contract.

The target of a substantial redesign is that execution seam: stable shared capabilities, registered operation/resource lifetimes, and guarded publication. Manual, auto and batch scheduling remain distinct. Existing native quarantine, engine synchronization and `ChapterTranslationStore` authority remain protected. A whole subsystem rewrite is not established by the evidence.

Dead-code removal reduces misleading paths and the amount of code developers must reason about. Test reliability work makes subsequent regressions interpretable. Neither activity alone eliminates callback propagation or repairs native lifetime ownership.

# Primary-verified dead-code candidates

Paths below are repository-relative. A name search covered source and tests throughout the checkout, including hidden files while excluding generated build/Gradle/Git directories. Source bodies and local visibility were inspected. The declaration-only result is meaningful for these ordinary functions; it is not a general rule for serialized models, overrides, framework entry points or native callbacks.

| ID | Location / symbol | Evidence and disposition | Removal boundary |
| --- | --- | --- | --- |
| DC-01 | `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt:140`, `translationOnnxEp()` | Declaration and its persisted key are the only repository matches. Its own comment says runtime ignores it. Confirmed unused accessor. | Remove accessor and obsolete comment. Leave existing installed preference values inert; do not introduce a storage migration merely to delete a key. Other ONNX/provider settings are separate and active. |
| DC-02 | Same file `:186`, `translationExperimentalQnn()` | Declaration and its key are the only matches; no current UI or reader. Confirmed unused accessor. | Same narrow boundary. The comment about old files deserializing does not establish that calling this accessor is required to read other preferences. |
| DC-03 | `engines/rendering/TextLayoutPlanner.kt:873`, `bandBeatsRectangleFit()` | Private function, declaration-only across the checkout. Confirmed uncalled code. | Remove function after checking any newly orphaned private helpers; preserve the active fit algorithms and tests. |
| DC-04 | Same file `:901`, `applySiblingFontHarmony()` | Private, declaration-only. Confirmed uncalled code. | Do not describe its removal as changing sibling font harmonization in the active renderer: this function currently performs no work. Validate active render output separately before changing algorithms. |
| DC-05 | Same file `:1212`, `dilateSpans()` | Private, declaration-only. Confirmed uncalled code. | Remove this function, not shared mask geometry or span types used elsewhere. |
| DC-06 | Same file `:1270`, `rescueCeilingSpans()` | Private, declaration-only. Confirmed uncalled code. | Keep active fallback and mask-budget behavior; do not remove shared helpers solely because this caller disappears. |
| DC-07 | Same file `:2258`, `buildSharedCellPlans()` | Private, declaration-only. Confirmed uncalled code. | Its substantial shared-cell implementation is a dead island root. Independently trace descendants before removing additional code or models. |
| DC-08 | Same file `:2426`, `withSharedCellMetadata()` | Private, declaration-only. Confirmed uncalled code. | Preserve metadata models and compatibility fingerprints that active publication or rendering consumes. |
| DC-09 | `pipeline/TranslationPipeline.kt:1265`, `markBatchTranslationFailed()` | Private suspend function, declaration-only; not part of the active constructor callback wiring directly above it. Confirmed uncalled code. | Remove function only. Preserve real batch failure routing, attempt charging and guarded store updates. |
| DC-10 | `engines/translator/analysis/AnalysisChunkExecutor.kt:353`, `isStructuralAnalysisRefusal()`; `:357`, `batchSublimitKeyFor()` | Wrapper comments claim test/diagnostic exposure, but both names occur only in their declarations. Confirmed unused wrappers. | Remove wrappers; retain `TranslationResponseFaithfulness` and `BatchProviderSublimit`, which implement the real behavior. |
| DC-11 | `engines/inpainting/InpaintingProviderRecovery.kt:7`, `runWithInpaintingProviderRecovery()` | Internal helper has no production caller; four calls exist only in `InpaintingProviderRecoveryTest`. Confirmed test-only production island, not proof that fallback behavior is unnecessary. | Eligible for paired helper/test removal only when this retired helper is accepted as outside the current contract. Preserve all active provider/backend fallback coverage. Its tests are obsolete-coverage candidates, not demonstrated flaky tests. |

Additional declaration-only candidates awaiting independent ownership assessment include `TextLayoutPlanner.fitTextUnmasked(:552)`, `LayoutPlanPublication.decodeColorPreparation(:179)`, `MangaOcrEngine.writeCacheAtPos(:441)`, `VerticalLineOcr.composePlan(:150)` and `recognizeVerticalColumnPerChar(:521)`. These public/internal seams require compatibility and intended OCR behavior review before admission to the removal list.

# Dormant paths requiring OCR/LiteRT coordination

| Candidate | Primary evidence | Required decision before removal |
| --- | --- | --- |
| Unpadded reread branch, `engines/vision/ocr/PaddlePageOcrCoordinator.kt:262` | `RegionState.needsUnpaddedReread` is an immutable Boolean (`:527`), set to `false` in the only construction (`:446`). No other name matches assign it. The `fallbackStates` list is therefore empty; branch-local fallback planning/dispatcher is unreachable. **Current vertical initial planning already uses an unpadded crop and `verticalFallback=true` (`:407–424`), unlike the historical sequential padded-first path.** | Determine with the OCR owner whether a distinct detector-to-heuristic retry is still required. A trivial flag change can repeat identical heuristic work when no detector lines were found. Neither unfinished-port intent nor an actual OCR recall regression is established. Gate removal on current parity tests; do not infer intended behavior only from an old comment or synthetic phase fixture. |
| Legacy ONNX initialization in `engines/inpainting/aot/NeuralInpaintingCoordinator.kt:178` | Current construction/call sites do not invoke `initialize()`. Legacy session fields are populated through that initialization ladder. Active inpainting uses LiteRT callbacks and classical fill through this same coordinator. | Trace the whole dormant subgraph, tests, model provisioning and compatibility expectations. Remove only legacy initialization/session paths after agreeing the supported backend contract. The coordinator itself is active and must remain. |
| `InpaintingHardwareOverride` forwarding chain | Current reader entry points set CPU; its ONNX backend conversion is consumed by the dormant initialization path. | Trace rebuild/signature/lifecycle effects before removing forwarding. This is broader than a declaration-only cleanup. Coordinate with engine ownership work; preserve live LiteRT model selection and fallback. |

# Test reliability: established evidence and candidates

**No currently flaky test has been demonstrated by this audit.** Flakiness requires different outcomes under controlled repetitions of the same checkout, variant, environment and relevant input, or a documented race reproduced in that context. A timeout, delay, skip or historical comment alone does not qualify.

Available local XML reports:

| Task directory | Suite files | Test cases | Failures/errors | Skipped | Report time |
| --- | --- | --- | --- | --- | --- |
| `app/build/test-results/testDevDebugUnitTest` | 25 | 182 | 0 | 1 | October 13, 2026 |
| `app/build/test-results/testStandardDebugUnitTest` | 5 | 19 | 0 | 0 | October 13–14, 2026 |

These are partial selections, not the whole translation suite or repeated same-variant runs. **None of the seven reliability-candidate classes is covered by these XMLs.** The one skip is the dictionary-asset existence assumption in `PaddleOcrDynamicPageBatchPolicyTest:73`; it is an asset/provisioning coverage gap, not a failure or flake. Do not combine these reports into a claim of current baseline suite success or candidate stability.

| ID | Location | What is established | Disposition |
| --- | --- | --- | --- |
| TR-01 | `coexistence/BatchParallelWindowBudgetTest.kt:87` and `:152` | Two test methods define local `selectWaveCandidates` and `evaluateStandardLaneTerminal` implementations, then assert those local copies. They do not invoke the production wave-selection or terminal-routing implementation. The latter mirrors active `StandardLaneWorker:339`. Production can change without these copies noticing. | Replace with calls through the actual decision seam or integration path, then remove the mirrored tests. Keep the memory-budget tests in the same class. This is a verified coverage weakness, not flakiness. |
| TR-02 | `milestone/ProviderHeadroomTest.kt:225`, delays `:252/:262` | Concurrency assertion uses 100 ms lifetimes. Source review confirms distinct backend keys, immediate first admission into each separate bucket, and both children on the same `runBlocking` event loop. No concrete flake mechanism was established by the auditor or primary reviewer. | Retain the distinct-backend concurrency invariant. Explicit entered/release gates are an optional clarity improvement, not a proven reliability repair. |
| TR-03 | `coexistence/BatchParallelWindowBudgetTest.kt:63` | `delay(50)` stands in for acknowledgement that the third acquirer attempted admission, then a 1 s real-time join is used. The negative assertion can pass even if the third coroutine has not yet attempted to acquire. Shared process-wide budget is reset before/after. | Use deterministic entry/blocked/release evidence, guaranteed cleanup and deliberate fixture isolation. A blanket virtual-time conversion would not prove the real semaphore contention contract. |
| TR-04 | `engines/vision/segmentation/MaskGeometryOrderedRleTest.kt:259` | Functional budget-exceeded assertions coexist with host elapsed-time `<5,000 ms` and optional JVM allocation `<16 MiB` assertions. Timing/allocation vary with host/JVM conditions. No failure was reproduced. | Keep deterministic component/budget assertions in correctness CI; separate host performance evidence into an explicit measured job with baseline/noise reporting. Do not delete the bounded-processing invariant. |
| TR-05 | `scheduling/TranslationSchedulerTraceTest.kt:37–65`, `:93` | Test swaps process-wide diagnostic sink/settings and restores them; asynchronous scheduler completion is polled. Global overlap or unfinished publishers could contaminate capture if test execution changes. Current in-JVM parallel execution has not been established. | Inspect completion drain and capture isolation; retain exactly-once cancellation/trace coverage. `org.gradle.parallel=true` does not by itself prove test methods run concurrently. |
| TR-06 | `tachiyomi/ui/manga/MangaScreenModelTranslationDrawerTest.kt:75`, `:220`, `:417` | Comments document historical order-related global Voyager scope reuse and bootstrap initialization failure. Current source already evicts the cached scope and records a production bootstrap fix. Class uses an ordered shared fixture and polling. | Audit current isolation; characterize again before claiming an outstanding flake. Preserve regression assertions. An earlier failure found a real production bug, so deleting this coverage would remove useful evidence. |
| TR-07 | `coexistence/BatchDispatchResumeWiringTest.kt:57`, helper `:78` | Historical quarantine concern is explicitly followed by successful characterization on an older SHA. Helper retries typed pause/retryable outcomes as fresh user-style requests while preserving store state. | Retain dispatch/resume coverage. Distinguish a first-attempt completion assertion from explicit retry/resume semantics before altering helper behavior. Neither retries nor an old quarantine label prove present flakiness. |
| TR-08 | `diagnostics/SyntheticJournalReplayBenchTest.kt:21` | Explicit opt-in manual benchmark measures a synthetic framed-file format, not the production journal. Its pre-integration wording is obsolete as an explanation of current production architecture. | Label as a synthetic historical comparison or replace with a production-format benchmark. Do not cite its timings as production recovery measurements; not a flaky unit test. |

# Independent-review cross-check and corrections

The test auditor confirms that TR-01's production policies are still active: wave selection in `workflow/ChapterTranslator.kt:418–450` and terminal gap routing in `pipeline/batch/StandardLaneWorker.kt:338–351`. Replace the test-local copies with production-path coverage; do not retire their contracts. No deletion is justified among the seven reliability classes audited. A separate test-only orphan (DC-11) concerns a helper outside those seven classes.

The quarantine tag exclusion exists in `buildSrc/.../ProjectExtensions.kt:115–122`, but no checked-in test uses `quarantined-flaky`. No in-repository JUnit parallel configuration was found. This does not establish that all external test invocations or background publishers are isolated. JUnit concurrent methods share process globals; Gradle `maxParallelForks` launches separate JVMs and does not itself cause cross-fork mutation of those globals. Before/after resets alone cannot protect a singleton from overlapping methods in one JVM.

The primary reviewer rejected the reported `MangaScreenModelTranslationDrawerTest` null-key/precedence defect: `key.toString()` on `Any?` uses Kotlin's nullable conversion and returns the string `"null"` for a null receiver. Its predicate deliberately supports string and typed cache keys, as the source comment states. No null-key NPE or incorrect precedence was demonstrated. Best-effort reflection can still conceal lookup incompatibility; that is an observability concern requiring evidence, not the claimed defect.

The OCR auditor independently confirms the mechanical candidates `MangaOcrEngine.writeCacheAtPos`, `VerticalLineOcr.composePlan`, `addVerticalGlyphLeaves` and `recognizeVerticalColumnPerChar`. `detectVerticalGlyphRows(:563)` has callers only in the latter two uncalled helpers and can be considered as part of that dependency-ordered island. Preserve active `writeCacheAtSlot`, `detectVerticalColumns` and whole-line rotation/filtering contracts. Public/internal visibility does not rule out reflection; resources, reflection and native/compatibility entry points still need checking before a final removal list.

The OCR auditor's stronger assertion that the disabled reread is an unfinished port with proven recall loss was not accepted. The current initial crop/geometry path differs from the historical sequential one. `RoiPageRecognitionEngine.kt:662–694` also selects the coordinator when the Paddle engine and coordinator are present, so reachability of the alternate Paddle-specific sequential branch must be established before calling it a live baseline comparator. The OCR report is being revised accordingly.

# Retain: false purge candidates

- `PageLifecycle`, `PageView` and overlay fields: production projections, data-class equality and flow emissions use them. The previous fragmentation/dead-code claim was corrected.
- `PageInpaintingEngine` and `NeuralInpaintingCoordinator`: active mode/fallback, LiteRT callback and classical-fill consumers exist.
- `NativeRunQuarantine`: intentional native completion/admission safety; independent scope is not proof of dead code.
- ONNX hardware discovery, vision routes and Paddle fallback provider/batch configuration: still live, even when the normal OCR route is LiteRT.
- Store synchronous JVM compatibility opening and existing serialized fields/types: test-only compatibility or durable-schema contracts require separate treatment.
- `ChapterTranslationStatusOffMainThreadTest`: latch-based regression guard for caller suspension. Bounded timeouts are not grounds for purging it.
- Inpaint-time color estimation: potentially redundant but currently active and observable; retire only through the all-mode parity/result-join work in MR-03, not a dead-code purge.

# Execution gate for the first cleanup

1. Finish independent source audits and primary cross-checks. Record removals separately from intended-feature restoration and compatibility decisions.
2. Freeze an exact symbol/file removal list. Separate small dead-code removals from test-harness repair and backend retirement. Do not bundle native lifetime redesign into this cleanup.
3. Run appropriate baseline build/tests before removing code. For suspected flakes, characterize the same SHA/variant in isolated, package and wider-suite contexts without automatic retries hiding outcomes. Record first-attempt outcomes, seeds/order, logs and cleanup completion.
4. Repair valuable flaky coverage; delete a test only when its contract is retired or equivalent production-path coverage replaces it. Quarantine an unavoidable environment-bound case only with an owner, reproduction record and restoration condition.
5. Verify compilation, focused regressions, variant coverage and discovery after cleanup. Confirm no active serialization, fallback, cancellation, lease or display contract was silently removed. OCR/native path removals require the existing OCR/LiteRT ownership handoff.

Completion evidence is a smaller verified reachability graph and reliable regression signals, not a quota of deleted files/tests or an improved subjective score. Cleanup implementation is not authorized by this audit request; all changes so far are outside the production repository in these planning/review artifacts.
