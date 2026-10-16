---
title: "Independent OCR/native audit: OCR engines, native inpainting backends, runtime providers"
kind: review
comments: none
---

# Scope and method

Read-only purge audit at baseline `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` (HEAD verified, working tree clean — all line numbers refer to this revision). Scope: translation-engine OCR surfaces (`VerticalLineOcr`, `PaddlePageOcrCoordinator`, `MangaOcrEngine`), native inpainting (`NeuralInpaintingCoordinator`, `PageInpaintingEngine`, `AotExecutionCoordinator`, hardware-override chain), the legacy ONNX runtime providers, quarantine, and the related tests. Method: whole-repo name search (source, tests, androidTest, benchmark), then reading every cited declaration and call site to confirm reachability, including lifecycle and rebuild-gate traces. No source/test/build edits, no Gradle/device runs, no subagents, no contact with other agents.

Parent artifact: [Pre-refactor audit](../index.md).

# Verdict table

| # | Symbol / lines (repo-relative from `app/src/main/java/eu/kanade/translation/`) | Classification | Production callers | Test coverage | Disposition |
| --- | --- | --- | --- | --- | --- |
| V1 | `engines/vision/ocr/MangaOcrEngine.kt:441` `writeCacheAtPos` | **Confirmed dead** | 0 | 0 | Delete alone; `writeCacheAtSlot` (:421) is live (decoder loop :302–303) — do not touch it |
| V2 | `engines/vision/ocr/VerticalLineOcr.kt:340` `addVerticalGlyphLeaves` | **Confirmed dead** | 0 (private) | 0 | Delete |
| V3 | `engines/vision/ocr/VerticalLineOcr.kt:521` `recognizeVerticalColumnPerChar` | **Confirmed dead** | 0 | 0 | Delete |
| V4 | `engines/vision/ocr/VerticalLineOcr.kt:563` `detectVerticalGlyphRows` | **Confirmed dead (transitive)** | Only V2+V3 | 0 | Delete together with V2+V3, not before |
| V5 | `engines/vision/ocr/VerticalLineOcr.kt:150` `composePlan` | **Confirmed dead** (near-duplicate) | 0 | 0 | Delete, or refactor coordinator to reuse it — see F5 |
| V6 | `engines/vision/ocr/PaddlePageOcrCoordinator.kt:262–312` fallback branch, gated by `needsUnpaddedReread` (:527, only construction `false` :446) | **Unreachable scaffolding** — redesign residue; owner parity review required | Unreachable | Planner fixture vocabulary only (`r5-unpadded-reread`); `recognizePage` itself untested | **Owner-gated** — see F1 (revised after cross-check) |
| V7 | `engines/inpainting/aot/NeuralInpaintingCoordinator.kt:178` `initialize` + ONNX session ladder | **Dormant contract** — no callers; sessions never set in production | 0 | Ladder exercised only via tests that also never call `initialize` | **Owner-gated purge** — see F2 |
| V8 | `engines/inpainting/InpaintingHardwareOverride.kt` chain (`ReaderViewModel.kt:558/810` → `EngineLane` gate :581–582 → `NeuralInpaintingCoordinator:57`) | **Production-inert** (always CPU, endpoint dead) | Executes, no effect | `InpaintingHardwareOverrideTest`, `EngineLaneNeuralModelTest` pin it | **Owner-gated** — see F3 |
| V9 | `engines/inpainting/InpaintingProviderRecovery.kt:7` `runWithInpaintingProviderRecovery` | **Test-orphan** | 0 | 4 calls (`InpaintingProviderRecoveryTest`) | Paired cleanup (helper + its test together); no flake claim |
| V10 | `engines/inpainting/PageInpaintingEngine.kt` | **Live** | `RoiPageRecognitionEngine.kt:470, 996–1005` | `PageInpaintingEngineTest`, androidTest | Retain — not an orphan |
| V11 | `pipeline/execution/NativeRunQuarantine.kt` | **Live correctness mechanism** | `TranslationPipeline.kt:132/242`, `EngineLane.kt:47/186` | `NativeRunQuarantineTest`, coexistence harness | Retain — must preserve |
| V12 | `engines/runtime/onnx/HardwareDiscoveryEngine`, `OnnxRuntimeProvider`, Paddle provider/batch policy | **Live** | 92 production refs: vision route `RoiPageRecognitionEngine.kt:255`, detectors, segmenter, `PaddleOcrSessionFactory`, rebuild gate `EngineLane.kt:586–593` | `HardwareSessionAuditTest:223/248` pins rebuild-on-change | Retain — cannot blanket purge; keep batch policy input |

# Findings

## F1 (REVISED after primary cross-check) — coordinator fallback branch is unreachable scaffolding of a redesigned planning model, not an established bug

Unreachability stands: `needsUnpaddedReread` is a `val` (`:527`), constructed `false` at `:446` (the only `RegionState` construction), so the filter at `:262` is always empty and the fallback block (`:263–312`) never runs. No setter exists.

The earlier framing — "unfinished port with proven recall divergence vs the sequential path" — does not survive geometry analysis:

- Sequential reread trigger (`RoiPageRecognitionEngine.kt:829`): `rawText.isEmpty() && isVerticalLanguage && boxHeightPre > boxWidthPre`.
- Coordinator initial planning (`:371–375`): `isVerticalBubble = isVerticalLanguage && (boxWidth <= boxHeight * 1.5f)`. Since `h > w ⟹ w < h ≤ 1.5h`, **every** region the sequential fallback would rescue already enters the `isVerticalBubble` branch (`:407–421`), which crops UNPADDED and plans with `verticalFallback = true`.
- With no det lines, that branch already produces the identical ink-gap heuristic a reread would use. With det lines, wiring the reread would DEMOTE detector-guided leaves to heuristic — a different behavior that would need evidence, not a parity fix.
- `isVerticalBubble` additionally covers slightly-wide boxes (`h < w ≤ 1.5h`) that sequential read padded-only — divergence in the opposite direction of a recall gap.

The hardcoded `false` therefore proves unreachability only. The residue is port scaffolding of an intentionally different design: unpadded-first planning for vertical bubbles.

Sub-finding (dispatch gating): the sequential implementation this was assumed to be parity with is itself effectively unreachable for the current engine set. `RoiPageRecognitionEngine.kt:702` routes every `prefersHorizontalText == false` engine (MangaOcr, ML Kit — interface default `false`, `RoiOcrEngine.kt:56–57`) through the native-vertical batch path. The only `true` override is `PaddleOcrV6SmallEngine.kt:39`, and Paddle pages take the page-batch path (`:662–664`: `engine is PaddleOcrV6SmallEngine && coordinator != null`; coordinator created only in the Paddle branch `:390–394`, re-created `:526–531`, nulled in `close()` at `:1265` together with `roiOcrEngine`; teardown races throw at `:808` before reaching `:826`). The sequential Paddle branch (`:826–835`) is the coded general path for horizontal engines without page coordination — none exist today.

**Required disposition (replaces the earlier "wire the predicate" recommendation):** OCR-owner parity review of the current intended initial planning. If unpadded-first `isVerticalBubble` is confirmed intended, `needsUnpaddedReread`, the fallback block, and the stale comment at `:267–269` (which no longer describes any live path) are deletable scaffolding, with a coordinated decision on the `r5-unpadded-reread` fixture. If the owner wants padded-then-reread semantics restored, that is a design change, not a purge. Neither state is an established bug.

## F2 — NeuralInpaintingCoordinator: the whole ONNX ladder is dormant; the class itself is live and load-bearing

Verified facts:

- `initialize(fixedModelFile, dynamicModelFile)` (`:178–207`) has **zero callers** in main, test, androidTest, and benchmark sources (all `.initialize(` hits belong to detector/segmenter/OCR engines).
- Constructor sites: `RoiPageRecognitionEngine.kt:449–452`, `RoiPageRecognitionEngine.kt:998–1001`, `PageInpaintingEngine.kt:20` (default), plus three unit-test files and `PageInpaintingEngineOnDeviceVerificationTest` (androidTest). None call `initialize`.
- Sessions (`fixedSession`, `dynamicSession`, `fixedQnnSession`, `fixedNnapiSession`) are assigned only inside `initialize` (`:182–195`) and the detach helpers (`:1503`, `:1515`) / `close` (`:1998–2003`). In production they are permanently null.
- The engine's own comment states the design intent: `RoiPageRecognitionEngine.kt:455–467` — "BOTH selectable neural models execute through the shared LiteRT GPU runtime… the legacy ONNX AOT ladder is no longer instantiated or routed to."

Consequences (all verified against call graphs):

- `isInitialized()` (`:460–464`) is always false → `resolveFreeTextRoute` (`RegionDispatch.kt:155–163`) can never return `NEURAL_ONNX` → `inpaintReportFreeTextNeural` (`:1079`), `tryNeuralCandidate` (`:1531`), and the private `inpaint()` (`:1605–1877`) are production-unreachable.
- `neuralFillSquare` (`:1110`) **is** production-reachable — via the bubble-tile fallback `bubbleNeuralFill?.invoke(...) ?: neuralFillSquare(...)` (`:811–818`) when the LiteRT delegate returns null. With all sessions null, `AotExecutionCoordinator.run` (`AotExecutionCoordinator.kt:34–73`) skips the accelerator leg, gets `Attempt.Unavailable` on CPU, and returns `Telea(telea(request))` where the `telea` lambda returns null → `neuralFillSquare` returns null → caller falls back to solid median fill. **This null-return contract is load-bearing**: deleting the ladder without preserving it changes the failure path of QUALITY bubble inpainting when LiteRT degrades.
- The accelerator ladder (QNN HTP/GPU context-cache staging `:209–323`, strict NNAPI `:325–390`, XNNPACK/CPU session init `:392–437`, `PreparedFixedInput`/`prepareFixedInput`/`runPreparedFixedCandidate`/`fixedBackendAttempt` `:1314–1449`, `closeAndDetachQnn/Nnapi` `:1501–1529`) is exercised only when `initialize` has run — never in production.
- The coordinator instance itself is fully live and lifecycle-managed: `inpaintRegions` via `PageInpaintingEngine.kt:102/113/125`, `reclaimPooledMemory` via `RoiPageRecognitionEngine.kt:1348`, `forceReleaseNativeBuffers` via `:1381`, `close` via `PageInpaintingEngine.kt:149`. Classical fill, geometry, masking, pools, Paddle free-text refinement (`paddleDetector`, set at `RoiPageRecognitionEngine.kt:453`, consumed `:528`), telemetry (`lastAcceptedRoute`, read at `RoiPageRecognitionEngine.kt:981/1026/1032`) are all active.

**Do not delete the whole class.** The purge boundary, if the OCR/LiteRT owner confirms the ONNX ladder is retired, is: `initialize` + the three session-initializers + `readContract` + the ONNX attempt path inside `neuralFillSquare` (collapsing it to memory-gate → return null) + `inpaintReportFreeTextNeural` + `FreeTextRoute.NEURAL_ONNX` + the `onnxInitialized` parameter of `resolveFreeTextRoute` + `AotExecutionCoordinator` + `AotModelContract`/`AotFixedTensorContract`/`AotOutputGuard` if nothing else consumes them. Each of those last items needs its own caller check first.

A collapse of `neuralFillSquare` must preserve, in place or by equivalent relocation to the bubble call site:

- **Memory gate:** `EngineMemoryBudget.neuralInpaintDecision` + the skip/run `logSnapshot` lines + `onMemoryRejected` reason propagation (`:1127–1155`) — the reason string feeds `inpaintReportFreeTextFast(reason = …)` and the `lastRunDegraded` decision at `:1067–1069`.
- **Degradation/route telemetry consumed downstream:** `lastAcceptedRoute`, `lastBubbleRoute`, `lastRunDegraded`, `logPartialMaskSkip` are read at `RoiPageRecognitionEngine.kt:981/1026/1032` and `PageInpaintingEngine.markReady:153–161`.
- **Cleanup discipline:** `prepared.close()` + `BitmapPool.putALPHA8(maskBitmap)` in the `finally` (`:1301–1311`) and the neural-OOM `BitmapPool.releaseAll()` + `System.gc()` telea lambda (`:1264–1271`).

Any such removal must also preserve: the null-return contract above (bubble delegate-null → median), `isInitialized()` semantics for the trace gate (`RoiPageRecognitionEngine.kt:981–985`), and LiteRT-only routing.

## F3 — InpaintingHardwareOverride chain executes but has no production effect

Verified chain: `ReaderViewModel.kt:558` (init) and `:810` (post-reader-stop cleanup) are the only production setters, both hardcoding `CPU` → `TranslationManager.kt:801` → `TranslationPipeline.kt:368` → `EngineLane.kt:395` (sets `requestedInpaintingHardwareOverride` only) → rebuild gate `:562, 581–582` → `createRecognitionEngine` `:410–428` → `RoiPageRecognitionEngine` ctor `:70` → `NeuralInpaintingCoordinator` ctor `:451/:1000`.

Effects traced to ground:

- Since the value is always `CPU` and `CPU` is already every default (`EngineLane.kt:71, 232, 361`), the rebuild-gate comparison never diverges — the plumbing executes but cannot change anything.
- `toAotBackend()` (`InpaintingHardwareOverride.kt:19–22`) is consumed only by `initialize:184` (dead per F2) and the init log `:204`.
- `toHardwareRoute()` (`:13–17`) has **no production callers** — only `InpaintingHardwareOverrideTest.kt:12–14`.
- The vision hardware route is independent: `RoiPageRecognitionEngine.kt:255` derives it from the `translationVisionGpuAcceleration` preference via `HardwareDiscoveryEngine.resolveVisionGpuRoute`, not from this override.

**Not a safe mechanical delete:** the rebuild-gate semantics are pinned by `EngineLaneNeuralModelTest.kt:52–63`, and the plumbing is the seam that would make a non-CPU override meaningful. Its purpose died with the ONNX ladder (F2). Owner decision: retire the whole chain together with F2, or keep it as the documented re-entry seam. Do not remove only `ReaderViewModel:558/810` and leave the enum + gate — that leaves the worst kind of residue (a gate for a value nothing can set).

## F4 — Confirmed-dead OCR members (mechanical, dependency-ordered)

Whole-repo name search over source, tests, androidTest, benchmark:

| Symbol | Lines | Callers | Notes |
| --- | --- | --- | --- |
| `MangaOcrEngine.writeCacheAtPos` | `:441–443` | 0 | Pure delegating wrapper over `writeCacheAtSlot` (`:421`), which is live at `:302–303` (decoder KV-cache loop). Delete the wrapper only. `MangaOcrEngine` class is live (Japanese OCR). |
| `VerticalLineOcr.addVerticalGlyphLeaves` | `:340–384` | 0 | Private, uncalled even within its own object. Compiler warns; no reflection surface (private member of an internal object). |
| `VerticalLineOcr.recognizeVerticalColumnPerChar` | `:521–554` | 0 | Internal, declaration-only repo-wide. Its KDoc (`:510–520`) cites `docs/ocr-engine-notes.md` for the maintainer-validated per-glyph vertical-CJK workaround — see F6 before deleting. |
| `VerticalLineOcr.detectVerticalGlyphRows` | `:563–614` | Only the two above | Transitive orphan; delete in the same change as `addVerticalGlyphLeaves` + `recognizeVerticalColumnPerChar`. **Keep `detectVerticalColumns` (`:625`) — it is live** (planHeuristicColumns `:289`, recognizeHeuristicColumns `:468`). |
| `VerticalLineOcr.composePlan` | `:150–164` | 0 | Near-duplicate of `PaddlePageOcrCoordinator.compose` (`:501–512`); see F5. |

## F5 — Duplication: `composePlan` vs coordinator `compose`

`VerticalLineOcr.composePlan` (`:150–164`) and `PaddlePageOcrCoordinator.compose` (`:501–512`) implement the same groups → `filterRecognizedText` → join pipeline; they differ only in result lookup (positional list vs `PaddleOcrLeafIdentity` map). Either delete `composePlan` (simplest) or refactor the coordinator to reuse it (removes the duplication but adds an adapter). First option is the lower-risk purge; the duplication finding stands either way.

## F6 — Test-pinned contracts that must not be silently retired

- `VerticalLineOcrPlanContractTest.kt:37–88` pins that vertical CJK detector lines rotate 90° CCW **as whole lines without glyph fragmentation** (`glyphIndex == null`). This pins the current plan shape that replaced the per-glyph path — which is exactly why `addVerticalGlyphLeaves`/`recognizeVerticalColumnPerChar` are orphaned. Deleting those functions is consistent with this test; reviving per-glyph splitting later would require revisiting it and `docs/ocr-engine-notes.md`.
- `VerticalLineOcrPlanContractTest.kt:10–34` pins the legacy low-confidence whole-region filter (`applyConfidence = false` skips the 0.5 threshold), matching `planWholeRegion` (`VerticalLineOcr.kt:192–197`). Do not "simplify" `filterConfidence` away.
- `PaddleOcrFallbackKind.GLYPH` (`paddle/batch/PaddleOcrLeafWork.kt:66`) is produced by no live planning path (only the dead `addVerticalGlyphLeaves`) but is validated at `:125` and produced by test fixtures (`PaddleOcrB1TestFixtures.kt:186/198`). It survives as planner-contract + fixture vocabulary; flag to the OCR owner before changing the enum.
- `PaddleOcrB1TestFixtures.kt:224–231` models an `r5-unpadded-reread` region with `stage = "unpad-reread"`. This proves the phase exists as planner/trace **vocabulary**, not that `recognizePage` must emit it as a recognized-page output contract. Any F1 scaffolding removal must decide the fixture's fate with the OCR owner.
- `HardwareSessionAuditTest.kt:223–271` pins that the recognition engine rebuilds when the Paddle provider or batch preference changes — the batch-policy input (`EngineLane.kt:586–593`, `RoiPageRecognitionEngine.kt:488–500`) is live contract, not purgeable.

## F7 — runWithInpaintingProviderRecovery: paired test-orphan, no flake claim

`InpaintingProviderRecovery.kt:7–30` has zero production callers; its only four references are `InpaintingProviderRecoveryTest.kt:17/40/64/87`. Eligible for paired cleanup (helper + its test file together). The test is deterministic (pure inline function, no timing/concurrency), so this is an orphan removal, not a flake remediation — no reliability claim should be recorded against it.

# Retention confirmations (verified live, do not purge)

- **PageInpaintingEngine** — constructed at `RoiPageRecognitionEngine.kt:470` and `:996–1005`; delegates LiteRT fills into the coordinator (`PageInpaintingEngine.kt:98–101`); drives all three dispatch paths. Not an orphan despite the default-constructor smell at `:20`.
- **NativeRunQuarantine** — wired in `TranslationPipeline.kt:132/242–244` and `EngineLane.kt:47/186`; documented as the cancellation-vs-native-exit correctness mechanism in `docs/translation-architecture.md:63`; consumed by `ChapterTranslator.kt:548` and `DeferredPagePublications.kt:21`. Preserve; independent of this purge.
- **HardwareDiscoveryEngine / OnnxRuntimeProvider / Paddle provider** — live for vision GPU routing (`RoiPageRecognitionEngine.kt:255–273`), all ONNX session creation, Paddle session factory and provider overrides (`PaddleOcrSessionFactory.kt`, `PaddleOcrProviderOverride.kt`). Blanket purge would break active vision and ONNX fallback paths; batch policy input must stay (F6).
- **Sequential `VerticalLineOcr` surface** — `recognizeMultiLine/recognizeDetColumns/recognizeHeuristicColumns/recognizeSingleLine/detectVerticalColumns` all live via `RoiPageRecognitionEngine.kt:1425–1431` → `:828/831/838`.

# Deletion dependency order (if owners approve)

1. `writeCacheAtPos` (standalone).
2. `addVerticalGlyphLeaves` + `recognizeVerticalColumnPerChar` + `detectVerticalGlyphRows` (one commit; keep `detectVerticalColumns`).
3. `composePlan` (standalone) or the F5 refactor.
4. `runWithInpaintingProviderRecovery` + `InpaintingProviderRecoveryTest` (paired).
5. Owner-gated, larger blast radius: F1 wiring decision; F2 ONNX ladder + FreeTextRoute.NEURAL_ONNX; F3 override chain (only together with F2).

# Limitations

Static reachability only — no build, no device run, no JIT/AOT profiling. The whole-repo name search covers **direct** references only; it does not establish reflective reachability. Internal visibility does not make reflection a non-issue: unit tests routinely reach internal members reflectively (MockK, kotlin-reflect, string-based lookup), and R8/consumer keep rules can target names. Deletion-readiness for the non-private `internal` surfaces (V1, V3, V4, V5, V9) therefore additionally requires: (1) compiling the full unit + androidTest source sets, (2) a reflective/string-reference scan (`Class.forName`, `getDeclaredMethod`/`Field`, literal name matches), (3) checking keep rules in build files. Only the private member (V2) is compiler-verifiable as uncalled without those checks. LiteRT internals (`LiteRTMangaInpaintingEngine`) were read only at the wiring boundary. Cross-audit reconciliation (dead-code and test-reliability reports) was not performed per instructions.
