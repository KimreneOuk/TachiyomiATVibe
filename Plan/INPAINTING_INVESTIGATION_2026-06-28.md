# AOT Inpainting Investigation — SoC Detection / Backend Acceleration

Date: 2026-06-28
Branch: `Quality-Improvement`

## Origin of this investigation

Requested work: dynamically detect SoC and select an optimized ONNX backend
(QNN NPU for Snapdragon, XNNPACK/CPU elsewhere), with the goal of replacing the
current AOT model with Qualcomm's `qualcomm/AOT-GAN` for a performance boost,
while addressing that "AOT inpainting frequently fails."

**Outcome: the requested work was suspended.** The investigation disproved its
premises. This document records what was found, why the original plan would have
been ineffective or harmful, and what the higher-leverage work actually is.

This complements `docs/TRANSLATION_REMEDIATION_PHASE_PLAN.md` (which covers APK
size / dependency cleanup). The findings here are about *runtime correctness and
reliability of the neural inpainter*, a separate axis.

---

## TL;DR

1. **The neural AOT session is dead code in the live pipeline.** The model is
   loaded into native memory but its inference output is never read. All
   production inpainting is pure-Kotlin classical algorithms (Telea FMM,
   push-pull, solid fill). A backend router (QNN/XNNPACK) would accelerate a
   session whose result is discarded — zero benefit, plus added native-memory
   cost that worsens the real failure mode.

2. **The "frequently fails" symptom is memory-driven and backend-independent.**
   `TranslationMemoryBudget.canRunNeuralInpaint` gates on JVM heap
   (`Runtime.maxMemory()`, capped at 384 MiB), **not** the 6 GB physical-RAM
   target. On capable hardware the heap cap trips the gate, producing the
   visible "image gets blurry / worse" downgrade or a FAILED status.

3. **The Qualcomm `qualcomm/AOT-GAN` is not a drop-in replacement.** It ships
   as a SoC-specific QNN context binary (quantized, HTP-architecture-specific),
   not a portable ONNX. Replacing `aot.onnx` with it is not a file swap.

4. **The QNN Java API is not in the current artifact.** `addQnn` lives in
   `onnxruntime-android-qnn` (a separate Maven artifact). Calling it on the
   standard `onnxruntime-android:1.21.0` is a compile-time *unresolved
   reference*, not a runtime error — the original plan's "isolate behind a
   runtime guard" would not have compiled.

---

## Finding 1 — The neural AOT path is unreachable (dead code)

### Evidence
- `AOTInpainting.inpaintRegions` (`AOTInpainting.kt:184`) is the sole entry
  point. It routes:
  - Parented bubble groups → `bubbleCleaner.fillContained`
    (`AOTInpainting.kt:268`) — classical Telea/boundary-aware fill.
  - Unparented text → `bubbleCleaner.fillContained` (`:287`) — classical.
  - **All** free-text boxes (flat + small + neural) → `inpaintFreeTextLegacy`
    (`:336`) — classical push-pull.
- `inpaintFreeRegions` (defined `:426`) is the **only** method that calls
  `sess.run()`. A repo-wide search finds **zero call sites** for it.
- The neural `inpaint(sess, ...)` method (`:542`) is only reachable from
  `inpaintFreeRegions`, so it is likewise unreachable.

### Net effect
The ONNX session is created at `RoiPageRecognitionEngine.kt:189` (QUALITY mode)
and held in native memory for the engine's lifetime, but `sess.run()` is never
invoked. The session is a pure cost — native footprint with no output.

The code comment at `AOTInpainting.kt:334` ("Keep neural AOT for non-free-text
paths") describes an intent that is not implemented: bubble and unparented text
routes also use the classical cleaner, not the session.

### Consequence for the requested work
A SoC/backend router selects an EP for a session whose output is never read.
That is work on a dead path. It cannot improve quality or speed.

---

## Finding 2 — Failures are memory-driven, backend-independent

### The dominant failure path
`TranslationMemoryBudget.canRunNeuralInpaint` returns false → silent flat-fill
downgrade (`AOTInpainting.kt:473-491`) or, via `canStartInpaint` Defer, a FAILED
placeholder (`TranslationPipeline.kt:2756` → `:1195` / `:536`).

### The root disconnect
`TranslationMemoryBudget` (`TranslationMemoryBudget.kt:14-21`) computes the
per-page budget from the **JVM heap**, not physical RAM:
- `singlePageBudgetBytes()` = `availableHeapBytes × 50%`, clamped to
  `[96, 384] MiB` (`:343-348`).
- The 384 MiB ceiling (`MAX_SINGLE_PAGE_BUDGET_BYTES`) is binding regardless of
  the device's physical RAM. A 6 GB device has a typical app heap far below
  6 GB, so the "6 GB-RAM minimum" target does not relax this gate.
- The code itself documents this: the WARN at `AOTInpainting.kt:474-484` is
  logged *unconditionally* precisely because the downgrade was previously
  invisible and the heap-pressure root cause was never diagnosable.

### Failure taxonomy (all backend-independent)

| # | Root cause | Trigger | Recovery | EP-swap fixes it? |
|---|------------|---------|----------|-------------------|
| A | JVM-heap budget gate trips (≤384 MiB cap) | `canRunNeuralInpaint` false | Flat-fill / FAILED | **No** — heap accounting, not EP speed |
| B | Neural `sess.run()` path is dead code | unreachable | n/a | **No** — nothing is routed there |
| C | Model contract mismatch (input names) | `assertContract` `:165` | Fail-fast at init, engine disabled | **No** — same graph, same names |
| D | close()/run() lifecycle race | `nativeGuard.tryLock` fails `:541` | Leak-instead-of-SIGSEGV | **No** — session-lifetime sync |
| E | Engine not initialized (QUALITY, no model) | `PageInpaintingEngine.kt:67` | Throw → FAILED (unless fallback pref) | **No** — model still must load |
| F | Storage/persistence failure | `TranslationPipeline.kt:2065,2574,2722` | FAILED + retry | **No** — pure I/O |
| G | Cooperative `closed` flag mid-pipeline | `RoiPageRecognitionEngine.kt:475,496` | FAILED, retry | **No** — lifecycle |
| H | Real `OutOfMemoryError` | `TranslationPipeline.kt:2633` | `handleCriticalTranslationOom`, batch abort after 2 | **No** — possibly worsened (added native allocs) |
| I | Garbled/uniform neural output | `AOTInpainting.kt:724` | Fall back to classical | **No** — and currently dormant (dead path) |

The one backend-dependent item historically (NNAPI instability) is already
mitigated by hardcoding CPU-only (`OnnxRuntimeProvider.kt:24-29`,
`DeviceCapability.EpStrategy.CPU`).

### Consequence for the requested work
Every live failure mode is backend-independent. Switching to QNN/XNNPACK
addresses none of them and (per H) likely worsens the OOM vector on the 6 GB
target by adding native allocations.

---

## Finding 3 — `qualcomm/AOT-GAN` is not a portable replacement

- The HuggingFace / AI Hub `AOT-GAN` is shipped as a **QNN context binary**:
  pre-compiled, w8a8-quantized, and bound to a specific Snapdragon HTP
  architecture. It is not a generic ONNX graph that runs on arbitrary devices.
- The current `aot.onnx` (≈23 MB, asset `models/inpainting/aot.onnx`,
  `OnnxModelStore.kt:75`) is a portable CPU-ONNX model with contract
  `image[1,3,H,W]` + `mask[1,1,H,W]` ([-1,1], dynamic ≤512, 8-aligned), output
  read positionally. Replacing one with the other is a format change, not a
  file swap, and the binary would be Qualcomm-only.

---

## Finding 4 — The original backend-router plan was not implementable as written

Two fatal flaws, both confirmed against the `rel-1.21.0` source and Maven
metadata:

1. **QNN "runtime guard" is a compile-time impossibility.** `addQnn` is not a
   method on `ai.onnxruntime.OrtSession.SessionOptions` in the standard
   `onnxruntime-android:1.21.0` artifact. Calling it is an *unresolved
   reference* (compile error), not a `NoSuchMethodError` (runtime). The plan's
   promised "compiles on standard artifact, no-ops until `-qnn` is added" does
   not hold without reflection (`Class.forName` + `Method.invoke`) or a
   `compileOnly` stub artifact — neither was specified.

2. **XNNPACK availability is unverified and was assumed.** ORT's own XNNPACK-EP
   doc states it is not in the default prebuilt Android AAR. The plan's entire
   "no dependency change" thesis rested on this unverified assumption.

A corrected router would use **reflection-based EP registration** (compiles on
any artifact, decides availability at runtime, degrades to CPU if absent) and
defer the benchmark/cache/UI framework until an EP is proven to help. But per
Findings 1 and 2, that work has no value until the neural path is reconnected
*and* the memory budget is fixed — so it remains out of scope here.

---

## Recommended remediation (higher-leverage than backend acceleration)

Listed in dependency order. Each is independent enough to be a subagent quest
under the existing remediation plan's ground rules.

### R1 — Decide the fate of the neural AOT session (prerequisite to all EP work)

Pick one, then act on it:

- **Reconnect.** Wire `inpaintRegions` to call the neural session for QUALITY
  mode (bubbles via `inpaintFreeRegions`/`inpaint`, or a dedicated bubble neural
  path), keeping classical algorithms as the FAST mode and the failure
  fallback. This makes QUALITY mode actually produce AOT output. Only after
  this does backend selection have any meaning.
- **Remove.** Delete `inpaintFreeRegions`/`inpaint`/`assertContract`/the
  session load, the `aot.onnx` asset, and the `QUALITY` mode surface. Reclaims
  ~23 MB native + the heap-budget headroom the session consumes, directly
  reducing failure modes A and H. Honest if neural inpainting is not the
  intended direction.

Either choice is strictly better than the current state (loaded-but-unused).

### R2 — Reconcile the memory budget with the device-RAM target

The 384 MiB JVM-heap cap is the binding constraint behind the dominant symptom.
Options to evaluate (each needs on-device measurement):
- Gate neural inpaint on **system** memory (`ActivityManager.MemoryInfo.availMem`)
  in addition to / instead of JVM heap, so a 6 GB device is not penalized by its
  ~256–512 MiB heap cap.
- Tune `NEURAL_INPAINT_PEAK_MULTIPLIER` (currently 10) against real peak
  measurements from the reconnected neural path (R1) — the multiplier predates
  the current pooling and may over-count.
- Bound the crop size more tightly so `estimatedPeak` is dominated by the
  actual crop, not a worst-case page×3 term.

This is the direct fix for the "frequently fails / blurry" symptom.

### R3 — Make QUALITY-mode unavailability user-visible (already partially done)

`PageInpaintingEngine.kt:58-78` already throws on QUALITY-without-model unless
the `translation_inpaint_quality_fallback` pref is set. The remaining gap is
that the *silent flat-fill downgrade* at `AOTInpainting.kt:491` (failure mode A
under R2's budget) still degrades QUALITY output to classical with only a WARN.
Consider promoting that to a surfaced status (e.g. a `DEGRADED` stage state) so
users can distinguish "neural ran" from "neural was skipped."

### R4 — (Future, only after R1+R2) Backend acceleration

If R1 reconnects the neural path and R2 makes it reliable, *then* revisit SoC
detection / QNN-XNNPACK-CPU selection via reflection-based EP registration, and
evaluate the Qualcomm AOT-GAN context binary as a Snapdragon-only fast path.
Out of scope until then.

---

## Verification performed

- `AOTInpainting.kt`, `PageInpaintingEngine.kt`, `AotOutputGuard.kt`,
  `OnnxRuntimeProvider.kt`, `DeviceCapability.kt`, `OnnxModelStore.kt`,
  `TranslationMemoryBudget.kt`, `RoiPageRecognitionEngine.kt`,
  `TranslationPipeline.kt`, `PageTranslationState.kt` — read in full or in the
  cited ranges.
- Repo-wide search for `inpaintFreeRegions` / `inpaintFreeTextLegacy` /
  `inpaint(sess` confirms zero call sites for the neural path.
- Repo-wide search for `inpaintStatus = StageStatus.FAILED` enumerates all
  terminal-failure sites (table in Finding 2's source analysis).
- ORT `rel-1.21.0` `OrtSession.java` imports and Maven `onnxruntime-android`
  metadata confirm `addQnn` is absent from the standard artifact.

## Verification NOT performed

- No on-device measurement of actual heap usage vs. the 384 MiB cap during a
  real QUALITY inpaint. R2's tuning requires this.
- No binary inspection of the downloaded `onnxruntime-android:1.21.0` AAR to
  confirm/deny XNNPACK symbol linkage (the plan-mode session was read-only).
  This is the empirical check that would settle Finding 4 item 2 definitively.

## Risks / assumptions

- "Frequently fails" is interpreted from the failure taxonomy and the code's
  own documentation of the heap-pressure downgrade. Direct logcat capture from
  a failing device would confirm which of A–I dominates in practice; the
  investigation strongly implicates A.
- R1's "reconnect" branch assumes the neural model produces acceptable output.
  The dormant `AotOutputGuard` (`AOTInpainting.kt:724-730`,
  `AotOutputGuard.kt:5-48`) exists precisely because prior neural output had
  garbled/uniform-fill failure modes. Reconnection must keep that guard active
  and treat its trigger as a per-region fallback, not a silent pass-through.
