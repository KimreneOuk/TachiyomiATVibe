# Translation Pipeline Efficiency Audit — 2026-08-17

Scope: the full auto-translate pipeline (`app/src/main/java/eu/kanade/translation/`): decode → text detection → bubble segmentation → OCR → inpainting → provider translation → render, plus scheduling (`scheduling/`), ONNX runtime configuration (`runtime/onnx/`), and memory lifecycle.

Method: three exploration passes mapped the pipeline; two independent adversarial reviewers then attacked every claim. Eight claims were dropped or reframed (see §5); only claims that survived with `file:line` evidence appear below as findings. Baseline checkpoint before code changes: commit `dd42ec5`.

Priority scale: P0 = largest measurable waste, P1 = clear win, low risk, P2 = worthwhile but needs design care, P3 = minor / note.

---

## 1. Memory management

### MEM-1 (P0) — N × full-page dense bubble masks per segmentation call — FIXED
`BubbleSegmentationDecoder.reconstructMask` allocated a `ByteArray(pageWidth * pageHeight)` per accepted candidate and `decode` materialized all of them at once (`kept.mapNotNull`). With N accepted bubbles on a 1440×2080 page that is N × ~2.9 MB held simultaneously (20 bubbles ≈ 60 MB) — the pipeline's largest transient. The dense form was immediately superseded: the only consumer was `BubbleMaskRle.encode` in `RoiPageRecognitionEngine.analyze` (`RoiPageRecognitionEngine.kt:286-287`), and inpaint later consumes only the RLE (`AOTInpainting.inpaintReportBubbles` → `rasterizeOnto`).

Fix applied: `BubbleSegmentationDecoder.decodeRle(...)` emits `BubbleMaskRle` runs directly from the existing row-major scan (no dense array ever exists); `OnnxBubbleSegmenter.segment` now returns `List<BubbleMaskRle>`; `analyze` consumes it directly. Equivalence to the dense-encode path is pinned by tests (full-box, mid-row-close, and threshold-drop cases).

### MEM-2 (P1) — `OnnxBubbleSegmenter` was the only unpooled ONNX engine — FIXED
Per `segment()` call it allocated: a fresh 640×640 `ARGB_8888` bitmap, a fresh ~4.8 MiB direct `ByteBuffer` (native memory reclaimed only via GC cleaner — the exact hazard `DirectBufferPool.kt:17-39` documents from the June-2026 489-stranded-buffer OOM), and a fresh `IntArray(640*640)`. It was also absent from `RoiPageRecognitionEngine.reclaimPooledMemory()` and `forceReleaseNativeBuffers()` fan-outs, so even pooled state would have escaped OOM reclamation.

Fix applied: `BitmapPool` for the letterbox bitmap, an engine-owned `DirectBufferPool(maxPoolSize = 2)` for the tensor buffer (released only after `result.close()`/`tensor.close()`, mirroring `OnnxPageTextDetector`), and registration in both release fan-outs.

### MEM-3 (P1) — Double full-page `getPixels` in the bubble fill — FIXED
`AOTInpainting.inpaintReportBubbles` read the full page into `pixels`, filled in place, then read the *same unmodified bitmap* again into `original` for the feather blend (`AOTInpainting.kt:401-411` pre-change). The bitmap is not touched between the reads (`setPixels` happens only at the end), so the second read returned byte-identical data.

Fix applied: `AotReportBubbleFill.fillAndBlend` captures `original = pixels.copyOf()` before the fill — one full-page native read saved per page, pixel-identical output (pinned by test).

### MEM-4 (P2) — Dense masks retained through the OCR loop — FIXED BY CONSTRUCTION
`bubbleMasksRaw` stayed in scope (and GC-reachable in debug builds) through the whole per-ROI OCR loop after RLE copies superseded it. MEM-1's fix removes dense masks entirely, so this no longer exists.

### MEM-5 (P2, report-only) — Full-chapter deep copy on every store publish
`ChapterTranslationStore.publishLocked` → `snapshotPages()` → `detachedCopy()` of *every* page in the chapter (`ChapterTranslationStore.kt:618-621, 641-642`), on each of ~5-8 page-update events per page. A 200-page batch re-copies the entire chapter state thousands of times, and the `_state` snapshot permanently doubles retention alongside the `pages` map.
Recommendation: per-page copy-on-write — publish only the changed page's detached copy and share untouched page references (pages are already replaced-not-mutated via `ownedPage`), or snapshot lazily per subscriber.

### MEM-6 (P3, report-only) — Redundant compressed-byte copies per decode
Each page decode materializes the archive entry twice: `getChapterPages`' streamFn does `readBytes()` (`TranslationPipeline.kt:3780`) and `decodePageBitmapForTranslation` does another full `readBytes()` (`:3651`); with two decodes per batch page (OCR stage `:1490`, inpaint re-decode `:1590`) that is 2 archive reads + 2 redundant full byte copies per page. The re-decode itself is a deliberate memory trade (bitmap recycled between stages to respect the 48 MiB held-bitmap ceiling — do not "fix" that part). `loadPersistedCleanedBitmap` (`:2769-2791`) also bypasses the `TranslationMemoryBudget` preflight other decodes use.

### MEM-7 (P3, report-only) — Three resident AOT sessions
QUALITY mode keeps fixed-XNNPACK + fixed-strict-NNAPI + dynamic sessions alive (`AOTInpainting.kt:93-101`); the strict-NNAPI session duplicates the fixed model's 22 MB weights. A memory-vs-speed trade the budget code already accounts for (`neuralSessionCount()`); worth revisiting only if the strict-NNAPI route proves rarely healthy.

### What is already good (keep)
`BitmapPool`/`DirectBufferPool` with cleaner-based reclamation, held-bitmap ceilings (4 bitmaps / 48 MiB), decode/analyze/inpaint memory preflights (`TranslationMemoryBudget`), cooperative native teardown (`nativeGuard` + leak-instead-of-SIGSEGV), OOM recovery (`handleCriticalTranslationOom`), and the actively-evicted `ActiveChapterStoreRegistry` (verified: cleared on chapter switch/close; practical growth 1-3 entries).

---

## 2. Execution providers (CPU / GPU / NPU)

Current state per model (verified in code; all models in `assets/models/`, copied by named file in `OnnxModelStore`):

| Model | File | EP today | Input shape | Dtype |
|---|---|---|---|---|
| Text detector (RT-DETR) | detector-v4-s_int8 (11 MB) | NNAPI attempt → CPU retry (Split-op compile failure documented) | fixed 1×3×640×640 | int8 |
| Panel detector (YOLO26-n) | manga_panel_detector_int8 (2.8 MB) | NNAPI attempt → CPU retry | fixed 1×3×640×640 | int8 |
| Bubble segmenter (YOLO11-seg) | manga109_bubble_int8 (3.3 MB) | NNAPI attempt → CPU retry | fixed 1×3×640×640 | int8 |
| Paddle det (DB) | det/inference (9.5 MB) | NNAPI attempt → CPU retry | fixed 1×3×736×736 | float32 |
| MangaOcr enc/dec_init/dec_step | 17/24/22 MB | CPU (explicit) | fixed 1×3×224×224 | float32 |
| Paddle rec (PP-OCRv6) | inference (21 MB) | CPU | 1×3×48×W dynamic (320..1600, align 16) | float32 |
| AOT fixed | aot-512 (22 MB) | XNNPACK preferred | fixed 1×3×512×512 | float32 |
| AOT fixed strict | aot-512 | strict NNAPI behind `NnapiCapabilityGate` + health monitor + NNAPI→XNNPACK→push-pull cascade | fixed 512² | float32 |
| AOT dynamic | aot (22 MB) | XNNPACK | 1×3×H×W dynamic (mult. of 8, ≤768) | float32 |

**The "consistent size" hypothesis is confirmed and already mostly implemented**: every accelerated or acceleration-candidate model has a fixed input shape; the fixed-512 AOT exists precisely to give NNAPI a static shape while the dynamic model stays on XNNPACK. No GPU (OpenCL) EP ships in `onnxruntime-android`; QNN requires the separate `onnxruntime-android-qnn` artifact.

Report-only recommendations (each needs on-device validation; do not blind-flip):

- **EP-OPT-1 (P2): NNAPI fp16.** The on-classpath ORT 1.23.0 Java API exposes `addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))` — currently unused (`addNnapi()` bare in `OnnxRuntimeProvider.kt:163`). Best first target: the strict-NNAPI fixed-AOT session, whose numerics are already policed by `AotOutputGuard` + `NnapiHealthMonitor` (guard mismatch → permanent disable). Int8 detection models gain little from fp16.
- **EP-OPT-2 (P2): XNNPACK thread option.** `addXnnpack` is called with an empty options map; XNNPACK uses its own thread pool, so the session-level `setIntraOpNumThreads` does not govern it. `addXnnpack(mapOf("intra_op_num_threads" to n))` is supported by this artifact.
- **EP-OPT-3 (P2): paddle-rec width buckets.** Dynamic width forces CPU-side re-planning per line (arena + mem-pattern are off by design). Padding every line to a fixed 1600 would be a 2-5× compute *regression* (typical lines land 320-800; the vertical-CJK per-glyph path already wastes the most at its 320 px floor — `RoiPageRecognitionEngine.recognizeVerticalColumnPerChar`). Recommended: 3-4 fixed buckets (e.g. 320/480/800) so shapes repeat, and revisit the per-glyph floor.
- **EP-OPT-4 (P3): per-session thread budgets.** ~10 concurrent sessions each get inter=intra=`cores/2` clamped [2,4] (`OnnxRuntimeProvider.kt:111,134-137`) → 20-40 resident ORT threads for engines that only ever run one at a time; `allow_spinning=0` is applied only to the AOT sessions. Small models (224² OCR) likely need intra=2. Note ORT sessions do share a global arena config here; thread pools are per-session.
- **EP-OPT-5 (P3): QNN artifact decision.** Real Qualcomm NPU support = swap to `onnxruntime-android-qnn` + QNN-converted models. Code comments already anticipate losing XNNPACK on that artifact (`createRequiredXnnpackSessionOptions` KDoc). A project decision, not a patch.
- Caveat: whether the int8 models actually compile on NNAPI per device is observable only at runtime via the "Session creation with NNAPI failed (graph compile); retrying on CPU only" log — worth capturing in diagnostics.

Doc rot fixed in this pass: `docs/TRANSLATION_MODULE.md` §11 claimed "All translation ONNX sessions are CPU-only" and `OnnxPanelDetector`'s KDoc said "CPU-only, like every other translation ONNX session" — both contradicted the NNAPI/XNNPACK usage above; both now describe the actual per-model EPs.

---

## 3. Scheduling

Architecture (context for the findings): a two-lane rolling pipeline — ONE native lane (decode/detect/OCR/inpaint) globally serialized by `NativeRunQuarantine`'s Mutex, ONE translate/render lane fed by a capacity-1 `Channel<PreparedWork>`; for REMOTE_IO translators the lanes overlap (page B's native phase runs while page A's network call is in flight), verified by `RollingAutoCoordinatorTest`. Event-driven throughout (conflated trigger channel, no polling/delay loops).

### SCH-1 (P1, report-only) — Engine rebuild inside the global native lane
`ensureEnginesBuiltFor` runs under `withNativeLane` (`TranslationPipeline.kt:2144`, batch setup slot `:1024`): any settings flip (or first build) compiles ~10 sessions *while holding the one-at-a-time lock*, stalling every translation path app-wide; the batch path even burns a 90 s-timeout lane slot labeled `<engine-setup>`. Aggravator: a reading-order flip triggers a full rebuild purely as a workaround for one cached `@Volatile` flag (`TranslationPipeline.kt:251-254` — the comment says so itself), and FAST↔QUALITY rebuilds everything although only the AOT sessions differ. Recommendation, in order: (a) invalidate the reading-order flag instead of rebuilding; (b) scope rebuilds to the affected engines (OCR-model change need not rebuild detector/panel/segmenter/AOT; inpaint-mode change need not rebuild OCR — note `AOTInpainting` holds a shared `paddleDet` reference that must be decoupled first); (c) move engine setup out of the lane with a handshake so `closeEngines()` cannot race it.

### SCH-2 (P2, report-only) — Capacity-1 prepared channel
When the provider is slow (10-30 s AI chunks vs seconds-per-page native prepare), the native lane fills the single buffered slot and then suspends in `send` (`RollingAutoCoordinator.kt:530`), idling until the translate lane takes a page. `PreparedPage` carries durable IDs only (the translate lane re-loads the cleaned bitmap from disk), so capacity 2-3 costs almost no memory and would keep the native lane busy; `BatchCoordinator` already uses an unbounded channel for the same reason. The real justification for depth-2 is scroll-staleness (fewer wasted prepares on rapid flips), not memory — a capacity increase should come with updated lane-invariant tests (`RollingAutoCoordinatorTest` pins `maxConcurrent == 2`).

### SCH-3 (P2, report-only) — Unbounded quarantine hold
A timed-out or hung native invocation keeps exclusive lane ownership until it *really* exits — no upper bound after the 90 s (`ONNX_PHASE_TIMEOUT_MS`) / 120 s timeouts fire (`NativeRunQuarantine.awaitExitAndLogLate` under `NonCancellable` inside the lock). This is a deliberate crash-safety bound (the stuck ORT call still owns native memory), but the app has no watchdog to escalate (e.g., `closeEngines()` after N minutes, or surfacing a "translation stuck — restart engines" action). The quarantine design itself is correct; the gap is user-visible recovery.

### SCH-4 (P3) — Failed slots never auto-retry
`isAdmissible` excludes Failed (`RollingAutoCoordinator.kt:566-570`) — prevents tight-loops but a transient failure needs a manual retry or window reset. Reasonable default; worth a one-shot auto-retry for network-class failures only.

### Justified by design — do not "optimize" (adversarially confirmed)
- **Global native Mutex (S1-original):** required by engine statefulness (shared pooled KV caches, input buffers, unlocked scratch arrays in `AOTInpainting.kt:64-76`) and the 6 GB memory target — cross-engine concurrency would multiply resident direct buffers, exactly the June-2026 OOM class. `OrtSession.run` itself is thread-safe; the engines are not.
- **Inline prepare in the reconcile loop:** the native lane is serial anyway; inline admission is what keeps the `markTranslateAdmitted`-before-`send` duplicate guard race-free. Not a cost.
- **LOCAL_COMPUTE (ML Kit) full serialization:** overlapping on-device translation with ONNX would double model residency and SoC load on the target device class. Documented and test-pinned.
- **Provider Mutex scope:** `SharedProviderRequestAdmission` covers only the batch streaming lane, RevisionDriver pass-2, and InactivityFlusher (streaming-chunk consistency); manual/auto reader translates do NOT hold it — the initial "one network translate app-wide" claim was wrong.
- **Page re-decode between stages:** deliberate memory trade (see MEM-6).

---

## 4. Changes applied in this pass

| Change | File(s) | Effect |
|---|---|---|
| `decodeRle` emits RLE directly from the scan; shared `selectCandidates`/`isMasked` | `BubbleSegmentationDecoder.kt` | Eliminates all page-sized dense mask arrays (MEM-1, MEM-4) |
| Segmenter returns `List<BubbleMaskRle>`, pooled bitmap + direct buffer, release hooks | `OnnxBubbleSegmenter.kt` | ~5-6 MB less churn per page; native buffer now reclaimable under OOM (MEM-2) |
| `analyze` consumes segmenter RLE directly; `bubbleSegmenter` added to both release fan-outs | `RoiPageRecognitionEngine.kt` | Dense retention gone; OOM reclaim covers the segmenter (MEM-2/4) |
| `fillAndBlend` single-source fill+feather+blend | `AotReportBubbleFill.kt`, `AOTInpainting.kt` | One full-page `getPixels` saved per page (MEM-3) |
| Equivalence + behavior tests | `BubbleSegmentationDecoderTest.kt`, `AotReportBubbleFillTest.kt` | Pin RLE-path ≡ dense-encode path and fillAndBlend ≡ legacy sequence |
| EP doc rot fixes | `docs/TRANSLATION_MODULE.md`, `OnnxPanelDetector.kt` | Docs now match actual NNAPI/XNNPACK usage |
| R8 keep rule for ONNX Runtime | `app/proguard-rules.pro` | Fixes JNI abort in minified builds — see below |

**Build-config bug found during on-device deployment (P1):** the first `standardPreview` install crashed the process with
`JNI DETECTED ERROR IN APPLICATION: mid == null in call to NewObject from ai.onnxruntime.OrtSession.createSession`
(abort inside `checkOrtStatus` when the detector's expected NNAPI-compile failure tried to throw `OrtException`). Root cause: `proguard-rules.pro` had no keep rule for `ai.onnxruntime.**`, and `OrtException`'s constructors are only referenced from ORT's native code, so R8 tree-shaking removed them — turning the designed NNAPI→CPU fallback (`createSessionWithFallback`) into a process abort. This affects every minified build type (release/preview/benchmark) on any device where an ORT session creation fails; debug builds were unaffected (no R8), which is why it was never seen before. Fixed by adding `-keep class ai.onnxruntime.** { *; }`.

All changes are behavior-preserving (pixel/RLE-identical outputs); no scheduling or EP behavior changed.

## 5. Claims dropped after adversarial review (do not re-file without new evidence)

1. **"Wasteful unconditional full-page copy in `inpaintRegions`"** — the copy is the ownership contract (callers recycle the input, keep the output; it also creates the mutable ARGB surface), and the empty-boxes branch is unreachable behind `PageInpaintingEngine`'s guard.
2. **"Unbounded chapter-store registry"** — actively evicted on chapter switch/close; practical growth 1-3 entries.
3. **"Double decode is waste"** — deliberate held-bitmap-ceiling trade; only the byte-copy redundancy (MEM-6) survived.
4. **"Two crop IntArrays in `inpaintReportFreeTextFast` where one would do"** — mathematically required (pristine + filled needed simultaneously for the blend).
5. **"Inline prepare in the reconcile loop blocks throughput"** — refuted; serial native lane makes child-job admission equivalent, and inline admission protects ordering.
6. **"One network translate at a time app-wide"** — wrong scope; the provider Mutex covers batch/revision/flusher only.
7. **"First page pays NNAPI graph compile"** — NNAPI compiles in `createSession` (paid at engine build inside the lane); only XNNPACK plan setup is first-run.
8. **"`best_int8.onnx` dead asset bloats the APK/device"** — excluded from the APK by packaging rules and never deployed (named-file copy); it is a byte-identical repo duplicate kept for `overlay_lab`.

## 6. Validation

- `./gradlew spotlessCheck :app:testDevDebugUnitTest --tests 'eu.kanade.translation.segmentation.*' --tests 'eu.kanade.translation.inpainting.*'` — **BUILD SUCCESSFUL** (exit 0, 6m 56s; JDK = Android Studio JBR, `JAVA_HOME` not on shell PATH). 206 tests PASSED, 0 FAILED, including the new regression tests:
  - `decodeRle emits the same RLE as encoding the dense decode`
  - `decodeRle matches dense path when runs close mid-row`
  - `decodeRle matches dense path when a full-width mask spans row boundaries`
  - `decodeRle drops candidates whose mask never clears the threshold`
  - `fillAndBlend matches the legacy fill then blend sequence`
- One intermediate spotless failure was caught and fixed before the passing run (string-template style + an import made unused by the refactor). A first attempt whose output was piped through `tail` reported exit 0 spuriously; the recorded result above is from a run with the true exit code captured.
- Not run: full `testDevDebugUnitTest`, `assembleStandardRelease` (out of scope for this pass; focused suites cover all changed classes — `OnnxBubbleSegmenter`/`RoiPageRecognitionEngine` are device-bound and covered via the pure decoder/fill equivalence tests).
- Revert point: checkpoint commit `dd42ec5`.
