# Inpainting Three-Mode Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Fast/Balance/Quality inpainting modes — OpenCV NS bubbles + Telea free-text (Fast), NS bubbles + AOT free-text (Balance), AOT for both with >512px tiling (Quality) — plus push-pull decoupling and honest degradation stamping.

**Architecture:** Swap fill *kernels* behind an unchanged mask/geometry/feather contract. A mode→backend dispatch table (`RegionDispatch`) replaces scattered mode bools; bubbles gain a crop-scoped OpenCV NS path and (Quality only) a tiled neural path; AOT tensor padding switches from ring-median to edge-replicate; effective-route stamping fixes silent-degradation reuse.

**Tech Stack:** Kotlin/JVM, ONNX Runtime Android (QNN) 1.28.0, OpenCV 4.9.0 (`org.opencv:opencv`), kotest + mockk (JUnit 5), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-09-inpainting-modes-redesign.md` (approved; anchors verified). Test command shorthand: `./gradlew :app:testDebugUnitTest --tests "<FQCN>"`.

**Manual gate (Phase 0):** before Task 6 lands and before Task 9 ships, capture workbench baselines via `run_inpainting_workbench.bat` on the fixed corpus (see `Plan/inpainting-workbench-design.md`). Tasks below are code-only; the A/B gates after Task 6 and after Task 9 are manual verification points, not Gradle steps.

---

### Task 0: Model provenance attestation (spec §2.2-1 — zero risk, do first)

**Files:**
- Modify: `scripts/models.manifest:23-39`
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt` (comment only, near `initialize()`)

The audit established the HF repo `ogkalu/aot-inpainting@42ffc84f` **is** the incumbent manga-tuned model (zyddnys lineage); the pin is accidental today. Convert it into a documented, intentional one.

**Format caution:** `models.manifest` is strict JSON parsed by `json.loads` at `scripts/fetch_models.py:60` — comment lines would break every fetch. Use a JSON string field instead; the loader validates entries via `entry.get(...)` for known keys only, so an extra `"provenance"` key is ignored by the parser.

- [ ] **Step 1:** In `models.manifest`, add a `provenance` field to the two inpainting entries (inside each `{ }`, after `source_url`/`convert`):

```json
"provenance": "manga-tuned AOT-GAN trained by zyddnys for manga-image-translator; jit-traced re-upload ogkalu/aot-inpainting@42ffc84f (MIT, unchanged since 2025-10-16)"
```

For `inpainting/aot-512.onnx` append to the same string: `"; aot-512.onnx derived locally by scripts/converters/convert_aot_512.py (static-shape fold of aot.onnx)`.

- [ ] **Step 2:** In `AOTInpainting.kt`, directly above `fun initialize(...)`, add one comment block:

```kotlin
// Model: manga-tuned AOT-GAN (zyddnys / manga-image-translator) via
// ogkalu/aot-inpainting@42ffc84f — see scripts/models.manifest provenance note.
```

- [ ] **Step 3: Verify the manifest still parses**

Run: `python -c "import json; json.load(open('scripts/models.manifest', encoding='utf-8')); print('manifest OK')"`
Expected: `manifest OK` (extra keys are ignored by `fetch_models.py`'s `entry.get()` validation).

- [ ] **Step 4: Commit**

```bash
git add scripts/models.manifest app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt
git commit -m "docs(models): attest AOT manga-tune provenance in manifest and engine"
```

---

### Task 1: `BALANCE` mode value, pref parser, dispatch table

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingMode.kt`
- Create: `app/src/main/java/eu/kanade/translation/engines/inpainting/RegionDispatch.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/RegionDispatchTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `RegionDispatchTest.kt`:

```kotlin
package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RegionDispatchTest {

    @Test
    fun `mode table maps the three modes to their spec backends`() {
        FAST.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
        FAST.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)

        BALANCE.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.AOT_NEURAL)
        BALANCE.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)

        QUALITY.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.AOT_NEURAL, RegionBackend.AOT_NEURAL)
        QUALITY.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
    }

    @Test
    fun `usesNeural is true only when some class dispatches AOT`() {
        FAST.resolveDispatch(true).usesNeural shouldBe false
        BALANCE.resolveDispatch(true).usesNeural shouldBe true
        BALANCE.resolveDispatch(false).usesNeural shouldBe false
        QUALITY.resolveDispatch(true).usesNeural shouldBe true
    }

    @Test
    fun `pref parser accepts all three values and maps unknown to FAST`() {
        InpaintingMode.fromPref("FAST") shouldBe FAST
        InpaintingMode.fromPref("BALANCE") shouldBe BALANCE
        InpaintingMode.fromPref("QUALITY") shouldBe QUALITY
        InpaintingMode.fromPref("garbage") shouldBe FAST
        InpaintingMode.fromPref(null) shouldBe FAST
    }

    @Test
    fun `only FAST skips neural session init`() {
        FAST.initializesNeuralSessions shouldBe false
        BALANCE.initializesNeuralSessions shouldBe true
        QUALITY.initializesNeuralSessions shouldBe true
    }

    @Test
    fun `stamp name degrades only for neural modes without sessions`() {
        FAST.stampName(neuralAvailable = false) shouldBe "FAST"
        BALANCE.stampName(neuralAvailable = true) shouldBe "BALANCE"
        BALANCE.stampName(neuralAvailable = false) shouldBe "BALANCE_DEGRADED"
        QUALITY.stampName(neuralAvailable = false) shouldBe "QUALITY_DEGRADED"
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.RegionDispatchTest"`
Expected: COMPILATION ERROR (`BALANCE`, `resolveDispatch`, `fromPref`, `stampName` unresolved).

- [ ] **Step 3: Implement**

Replace `InpaintingMode.kt` entirely:

```kotlin
package eu.kanade.translation.engines.inpainting

enum class InpaintingMode {
    FAST,
    BALANCE,
    QUALITY;

    /** True when this mode asks the recognition engine to load AOT sessions. */
    val initializesNeuralSessions: Boolean
        get() = this != FAST

    companion object {
        const val DEGRADED_SUFFIX = "_DEGRADED"

        /**
         * Preference parse. Unknown/legacy values map to FAST so a stale value can
         * never select a mode that hard-fails pages when the model is unloaded
         * (prior audit F11 — was `else -> QUALITY`).
         */
        fun fromPref(value: String?): InpaintingMode = when (value) {
            "BALANCE" -> BALANCE
            "QUALITY" -> QUALITY
            "FAST" -> FAST
            else -> FAST
        }
    }
}

/**
 * Persistence stamp for this mode given current neural availability. A page
 * stamped `*_DEGRADED` re-inpaints on resume once sessions are healthy again
 * (prior audit F4), and does NOT loop while they stay unavailable.
 */
fun InpaintingMode.stampName(neuralAvailable: Boolean): String =
    if (initializesNeuralSessions && !neuralAvailable) name + InpaintingMode.DEGRADED_SUFFIX else name
```

Create `RegionDispatch.kt`:

```kotlin
package eu.kanade.translation.engines.inpainting

/**
 * Explicit region-class × backend table (spec §4.1). One source of truth for
 * what fills what — replaces mode bools scattered through the engine, so a
 * third mode can never silently alias onto the old QUALITY/FAST pair (F1/F3).
 */
enum class RegionBackend {
    /** OpenCV Navier–Stokes, crop-scoped — bubbles. */
    OPENCV_NS,

    /** OpenCV Telea fast-marching — classical free-text. */
    OPENCV_TELEA,

    /** AOT-GAN neural inference through the EP ladder. */
    AOT_NEURAL,
}

data class RegionDispatch(
    val bubble: RegionBackend,
    val freeText: RegionBackend,
) {
    val usesNeural: Boolean
        get() = bubble == RegionBackend.AOT_NEURAL || freeText == RegionBackend.AOT_NEURAL
}

/** Resolve the per-class dispatch once per run from mode + session reality. */
fun InpaintingMode.resolveDispatch(neuralAvailable: Boolean): RegionDispatch = when (this) {
    FAST -> RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
    BALANCE -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.AOT_NEURAL)
    } else {
        RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
    }
    QUALITY -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.AOT_NEURAL, RegionBackend.AOT_NEURAL)
    } else {
        RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.RegionDispatchTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingMode.kt app/src/main/java/eu/kanade/translation/engines/inpainting/RegionDispatch.kt app/src/test/java/eu/kanade/translation/engines/inpainting/RegionDispatchTest.kt
git commit -m "feat(inpaint): add BALANCE mode value, pref parser, and region dispatch table"
```

---

### Task 2: Mode wiring — EngineLane parse, PageInpaintingEngine gate, live fallback pref, cancellation safety

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt:341-346`
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt`
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt:386-480` (signature only in this task)
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngineTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `PageInpaintingEngineTest.kt`. **Contract note:** the engine deliberately isolates faults — the gate's `IllegalStateException` is thrown *inside* the try and caught by the existing `catch (e: Exception)` (`PageInpaintingEngine.kt:87-96`, codified by the existing test at `:48-60`). Gate failure therefore surfaces as `inpaintStatus = FAILED` + `errorMessage`, **never** as a propagated exception. Assert the observable contract, not a throw. No new imports needed — `RegionDispatch`/`RegionBackend` are in the test's own package and `verify` is already imported.

```kotlin
@Test
fun `BALANCE with no neural session fails the page unless fallback pref is enabled`() {
    val inpainter = mockk<AOTInpainting>()
    every { inpainter.isInitialized() } returns false
    val page = PageTranslation()
    page.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))

    PageInpaintingEngine(InpaintingMode.BALANCE, inpainter, qualityFallbackPref = { false })
        .inpaint(bitmap(), page) shouldBe null // gate throw caught → FAILED, not propagated

    page.inpaintStatus shouldBe StageStatus.FAILED
    page.errorMessage?.contains("unavailable") shouldBe true

    val cleaned = mockk<Bitmap>()
    every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned
    val page2 = PageTranslation()
    page2.inpaintMaskBoxes = listOf(InpaintMaskBox(10, 20, 40, 60, 2))
    PageInpaintingEngine(InpaintingMode.BALANCE, inpainter, qualityFallbackPref = { true })
        .inpaint(bitmap(), page2) shouldBe cleaned
    page2.inpaintStatus shouldBe StageStatus.READY
}

@Test
fun `fallback pref is read live per inpaint call`() {
    val inpainter = mockk<AOTInpainting>()
    every { inpainter.isInitialized() } returns false
    every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
    val page1 = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
    val page2 = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
    var pref = false
    val engine = PageInpaintingEngine(InpaintingMode.QUALITY, inpainter, qualityFallbackPref = { pref })

    engine.inpaint(bitmap(), page1) // pref=false at call time → gate trips → FAILED (F5)
    page1.inpaintStatus shouldBe StageStatus.FAILED
    pref = true
    engine.inpaint(bitmap(), page2) // same engine instance, pref now true → no gate trip
    page2.inpaintStatus shouldBe StageStatus.READY
}

@Test
fun `engine passes a dispatch resolved from mode and session reality`() {
    val inpainter = mockk<AOTInpainting>()
    every { inpainter.isInitialized() } returns true
    every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns mockk()
    val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 0)) }

    PageInpaintingEngine(InpaintingMode.BALANCE, inpainter).inpaint(bitmap(), page)

    verify {
        inpainter.inpaintRegions(
            any(),
            any(),
            any(),
            RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.AOT_NEURAL),
            any(),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.PageInpaintingEngineTest"`
Expected: COMPILATION ERROR (no `qualityFallbackPref` param; `inpaintRegions` still takes `InpaintingMode`).

- [ ] **Step 3: Implement**

`EngineLane.kt:341-346` — replace `inpaintingModeFromPref` body:

```kotlin
internal fun inpaintingModeFromPref(): InpaintingMode =
    InpaintingMode.fromPref(translationPreferences.translationInpaintingMode().get())
```

`PageInpaintingEngine.kt` — replace the class header + gate section (`:14-32` latch fields and `:58-84` gate) with:

```kotlin
class PageInpaintingEngine(
    private val mode: InpaintingMode,
    private val inpainter: AOTInpainting = AOTInpainting(),
    private val qualityFallbackPref: () -> Boolean = {
        try {
            Injekt.get<TranslationPreferences>().translationInpaintQualityFallback().get()
        } catch (_: Throwable) {
            false
        }
    },
) {
```

and inside `inpaint(...)`, replacing lines `:58-84` (comment block, QUALITY gate, effectiveMode):

```kotlin
// Neural modes hard-fail without sessions unless the user opted into the
// classical fallback (read LIVE per call — F5: the old latch ignored toggles).
val neuralAvailable = inpainter.isInitialized()
if (mode.resolveDispatch(neuralAvailable = true).usesNeural && !neuralAvailable) {
    if (!qualityFallbackPref()) {
        throw IllegalStateException(
            "$mode inpainting unavailable (neural model not loaded); " +
                "set inpainting to FAST, load the AOT model, or enable the neural fallback in Translation settings",
        )
    }
    logcat(LogPriority.WARN) {
        "$mode inpainting: neural AOT model not loaded; classical fallback enabled by user setting"
    }
}
val dispatch = mode.resolveDispatch(neuralAvailable)
val cleaned = inpainter.inpaintRegions(
    image = bitmap,
    boxes = input.boxes,
    labels = input.labels,
    dispatch = dispatch,
    blocks = pageTranslation.blocks,
)
```

Also harden the catch (F6, one line before `pageTranslation.inpaintStatus = StageStatus.FAILED`):

```kotlin
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    ...
```

`AOTInpainting.kt` — change `inpaintRegions` signature (`:386-392`) from `mode: InpaintingMode = InpaintingMode.QUALITY` to:

```kotlin
dispatch: RegionDispatch = InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = true),
```

(import `eu.kanade.translation.engines.inpainting.RegionDispatch` / `RegionBackend` / `resolveDispatch`), and inside (`:466-477`) replace `mode == InpaintingMode.QUALITY` with `dispatch.freeText == RegionBackend.AOT_NEURAL` and the reason string `if (mode != InpaintingMode.QUALITY) "fast_mode" else "no_neural_session"` with `if (dispatch.freeText != RegionBackend.AOT_NEURAL) "fast_mode" else "no_neural_session"`. The `mode=$mode neural=…` log line at `:46-51` stays — `dispatch` is not in scope there and the mode remains meaningful context. **In this task QUALITY bubbles keep the legacy median fill**: the bubble branch still calls `AotReportBubbleFill.fillAndBlend` unconditionally; neural bubble dispatch arrives in Task 9. Add above `result = inpaintReportBubbles(...)`:

```kotlin
if (dispatch.bubble == RegionBackend.AOT_NEURAL) {
    logcat(LogPriority.WARN) { "[inpaint] neural bubble dispatch not wired yet (Task 9); using median fill" }
}
```

Fix the existing tests' `verify` blocks in `PageInpaintingEngineTest.assertMaskProcessed` (`InpaintingMode.FAST` arg → `InpaintingMode.FAST.resolveDispatch(neuralAvailable = false)`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.PageInpaintingEngineTest" --tests "eu.kanade.translation.engines.inpainting.RegionDispatchTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt app/src/test/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngineTest.kt
git commit -m "feat(inpaint): wire BALANCE mode gate with live fallback pref and dispatch pass-through"
```

---

### Task 3: Session-init gate + honest trace-span model label (F3)

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt:376-383,885-889`

- [ ] **Step 1: Update the init gate**

At `:376` replace `if (inpaintingMode == InpaintingMode.QUALITY) {` with:

```kotlin
if (inpaintingMode.initializesNeuralSessions) {
```

(the else-branch log becomes `"ONNX init: FAST inpainting mode; skipping AOT session initialization"` — already accurate).

- [ ] **Step 2: Fix the trace-span model label**

At `:885-889` replace

```kotlin
model = if (inpainting != null) TranslationTraceModel.AOT_GAN else TranslationTraceModel.NONE,
```

with

```kotlin
model = if (inpaintingMode.initializesNeuralSessions && inpainting?.isInitialized() == true) {
    TranslationTraceModel.AOT_GAN
} else {
    TranslationTraceModel.NONE
},
```

- [ ] **Step 3: Verify compile + related tests**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.HardwareSessionAuditTest" --tests "eu.kanade.translation.coexistence.*"`
Expected: PASS (no behavior change for existing FAST/QUALITY values — `initializesNeuralSessions` is true exactly for QUALITY today; BALANCE now also loads sessions).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt
git commit -m "fix(inpaint): init AOT sessions for BALANCE and report honest trace model label"
```

---

### Task 4: Settings UI + strings for three modes

**Files:**
- Modify: `i18n-at/src/commonMain/moko-resources/base/strings.xml:79-81`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt:124-148`

- [ ] **Step 1: Add the string**

After line 81 (`pref_inpainting_mode_fast`) insert:

```xml
<string name="pref_inpainting_mode_balance">Balance</string>
```

- [ ] **Step 2: Update the screen**

In `getInpaintingModeGroup` (`:128-131`) add the entry:

```kotlin
val modes = mapOf(
    "FAST" to stringResource(ATMR.strings.pref_inpainting_mode_fast),
    "BALANCE" to stringResource(ATMR.strings.pref_inpainting_mode_balance),
    "QUALITY" to stringResource(ATMR.strings.pref_inpainting_mode_quality),
)
```

and the fallback switch (`:140-145`):

```kotlin
Preference.PreferenceItem.SwitchPreference(
    pref = translationPreferences.translationInpaintQualityFallback(),
    title = "Neural → classical fallback",
    subtitle = "Use classical inpainting when the AOT model is unavailable (Balance/Quality)",
    enabled = inpaintMode == "BALANCE" || inpaintMode == "QUALITY",
),
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :app:assembleDebug :i18n-at:build`
Expected: BUILD SUCCESSFUL (moko regenerates resources).

- [ ] **Step 4: Commit**

```bash
git add i18n-at/src/commonMain/moko-resources/base/strings.xml app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt
git commit -m "feat(ui): surface BALANCE inpainting mode in translation settings"
```

---

### Task 5: Bubble component clusterer + OpenCV NS inpainter

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/engines/inpainting/bubble/BubbleOpenCvInpainter.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/bubble/BubbleOpenCvInpainterTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package eu.kanade.translation.engines.inpainting.bubble

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BubbleOpenCvInpainterTest {

    private fun mask(width: Int, height: Int, vararg boxes: IntArray): ByteArray {
        val m = ByteArray(width * height)
        for (b in boxes) {
            for (y in b[1] until b[3]) {
                for (x in b[0] until b[2]) {
                    m[y * width + x] = 1
                }
            }
        }
        return m
    }

    @Test
    fun `far-apart bubbles form separate clusters`() {
        val clusters = BubbleOpenCvInpainter.componentClusters(
            mask(200, 200, intArrayOf(10, 10, 50, 50), intArrayOf(150, 150, 190, 190)),
            width = 200,
            height = 200,
        )
        clusters.map { it.toList() }.shouldBe(
            listOf(listOf(10, 10, 50, 50), listOf(150, 150, 190, 190)),
        )
    }

    @Test
    fun `bubbles within the gap merge into one cluster`() {
        val clusters = BubbleOpenCvInpainter.componentClusters(
            mask(200, 100, intArrayOf(0, 10, 50, 50), intArrayOf(100, 10, 150, 50)),
            width = 200,
            height = 100,
            gap = 64,
        )
        clusters.size shouldBe 1
        clusters[0].toList() shouldBe listOf(0, 10, 150, 50)
    }

    @Test
    fun `diagonal adjacency within gap merges and empty mask yields no clusters`() {
        BubbleOpenCvInpainter.componentClusters(ByteArray(100 * 100), 100, 100) shouldBe emptyList()
        val clusters = BubbleOpenCvInpainter.componentClusters(
            mask(100, 100, intArrayOf(5, 5, 20, 20), intArrayOf(25, 25, 40, 40)),
            100, 100, gap = 8,
        )
        clusters.size shouldBe 1
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.bubble.BubbleOpenCvInpainterTest"`
Expected: COMPILATION ERROR (`BubbleOpenCvInpainter` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package eu.kanade.translation.engines.inpainting.bubble

import android.graphics.Bitmap
import eu.kanade.translation.engines.inpainting.aot.AotPixelOps
import eu.kanade.translation.engines.inpainting.opencv.OpenCvInpaintEngine
import eu.kanade.translation.engines.inpainting.bubble.BubbleMaskBuilder

/**
 * OpenCV NS fill for bubble regions (spec §3.1). Receives the SAME merged mask
 * the legacy median fill consumed (seg-mask rasterize + erosion + pills) — only
 * the fill kernel differs. Runs per component-cluster crop to bound memory
 * (prior audit F7: never page-scale OpenCV).
 */
object BubbleOpenCvInpainter {

    const val CLUSTER_GAP_PX = 64
    const val CROP_CONTEXT_PX = 24
    const val SMALL_CLUSTER_MAX_DIM = 256
    const val FEATHER_RAMP_PX = 12

    /**
     * Connected components of [mask] (4-connectivity), bounding boxes merged
     * while their edge gap <= [gap]. Pure JVM — unit-testable without Android.
     */
    fun componentClusters(
        mask: ByteArray,
        width: Int,
        height: Int,
        gap: Int = CLUSTER_GAP_PX,
    ): List<IntArray> {
        val visited = BooleanArray(mask.size)
        val boxes = ArrayList<IntArray>()
        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue
            var minX = width; var minY = height; var maxX = -1; var maxY = -1
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                val x = idx % width
                val y = idx / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[idx - 1] != 0.toByte() && !visited[idx - 1]) { visited[idx - 1] = true; queue.add(idx - 1) }
                if (x < width - 1 && mask[idx + 1] != 0.toByte() && !visited[idx + 1]) { visited[idx + 1] = true; queue.add(idx + 1) }
                if (y > 0 && mask[idx - width] != 0.toByte() && !visited[idx - width]) { visited[idx - width] = true; queue.add(idx - width) }
                if (y < height - 1 && mask[idx + width] != 0.toByte() && !visited[idx + width]) { visited[idx + width] = true; queue.add(idx + width) }
            }
            boxes.add(intArrayOf(minX, minY, maxX + 1, maxY + 1))
        }
        // Merge boxes while any pair is within gap on both axes (n is small).
        var merged = boxes
        while (true) {
            val combined = ArrayList<IntArray>(merged.size)
            var didMerge = false
            for (box in merged) {
                val join = combined.firstOrNull { other ->
                    val gapX = maxOf(box[0] - other[2], other[0] - box[2])
                    val gapY = maxOf(box[1] - other[3], other[1] - box[3])
                    gapX <= gap && gapY <= gap
                }
                if (join == null) {
                    combined.add(box)
                } else {
                    join[0] = minOf(join[0], box[0])
                    join[1] = minOf(join[1], box[1])
                    join[2] = maxOf(join[2], box[2]);
                    join[3] = maxOf(join[3], box[3])
                    didMerge = true
                }
            }
            merged = combined
            if (!didMerge) return merged.sortedBy { it[1] * 100000 + it[0] }
        }
    }

    /**
     * Fills every cluster crop with OpenCV NS and feathers the result back into
     * [image]. Returns the worst backend used (for route logging / degradation
     * stamping — F8: a push-pull emergency must be visible).
     */
    fun inpaintClusters(
        image: Bitmap,
        mask: ByteArray,
        clusters: List<IntArray>,
        width: Int,
        height: Int,
    ): OpenCvInpaintEngine.Backend {
        var worst = OpenCvInpaintEngine.Backend.OPENCV_NS
        for (cluster in clusters) {
            val x1 = (cluster[0] - CROP_CONTEXT_PX).coerceIn(0, width)
            val y1 = (cluster[1] - CROP_CONTEXT_PX).coerceIn(0, height)
            val x2 = (cluster[2] + CROP_CONTEXT_PX).coerceIn(0, width)
            val y2 = (cluster[3] + CROP_CONTEXT_PX).coerceIn(0, height)
            val cropW = x2 - x1
            val cropH = y2 - y1
            if (cropW <= 0 || cropH <= 0) continue
            val localMask = ByteArray(cropW * cropH)
            var any = false
            for (y in 0 until cropH) {
                val srcRow = (y1 + y) * width
                val dstRow = y * cropW
                for (x in 0 until cropW) {
                    val v = mask[srcRow + x1 + x]
                    localMask[dstRow + x] = v
                    if (v != 0.toByte()) any = true
                }
            }
            if (!any) continue
            val original = IntArray(cropW * cropH)
            image.getPixels(original, 0, cropW, x1, y1, cropW, cropH)
            val work = original.copyOf()
            val maxDim = maxOf(cropW, cropH)
            val backend = OpenCvInpaintEngine.inpaintPixelsWithBackend(
                pixels = work,
                mask = localMask,
                width = cropW,
                height = cropH,
                radius = if (maxDim <= SMALL_CLUSTER_MAX_DIM) 3.0 else 5.0,
                method = OpenCvInpaintEngine.INPAINT_NS,
            )
            if (backend == OpenCvInpaintEngine.Backend.PUSH_PULL_EMERGENCY) worst = backend
            val alpha = BubbleMaskBuilder.featherAlphaField(localMask, cropW, cropH, FEATHER_RAMP_PX)
            AotPixelOps.compositeInto(original, work, alpha, work)
            image.setPixels(work, 0, cropW, x1, y1, cropW, cropH)
        }
        return worst
    }
}
```

Note: `featherAlphaField` and `compositeInto` signatures verified at `AOTInpainting.kt:713-714` / `BubbleMaskBuilder`. JVM tests exercise `componentClusters` only (Bitmap paths need a device; `inpaintPixelsWithBackend` falls back to push-pull on host — see `OpenCvInpaintEngineTest` for the existing pattern).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.bubble.BubbleOpenCvInpainterTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/inpainting/bubble/BubbleOpenCvInpainter.kt app/src/test/java/eu/kanade/translation/engines/inpainting/bubble/BubbleOpenCvInpainterTest.kt
git commit -m "feat(inpaint): add bubble component clusterer and crop-scoped OpenCV NS inpainter"
```

---

### Task 6: Wire NS into the bubble branch (FAST + BALANCE)

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt:545-596`

**Manual gate:** run the workbench before/after this task on the bubble-heavy corpus pages; sign off NS-vs-median appearance before Task 7 deletes anything.

- [ ] **Step 1: Update `inpaintReportBubbles`**

Change the signature to take the dispatch and route the fill (mask construction `:549-590` stays verbatim):

```kotlin
private fun inpaintReportBubbles(
    image: Bitmap,
    boxes: List<IntArray>,
    blocks: List<eu.kanade.translation.model.TranslationBlock>?,
    dispatch: RegionDispatch,
): Bitmap {
    // ... existing mask construction (seg-mask rasterize + erode + pill) unchanged ...
    if (mask.none { it != 0.toByte() }) return image

    if (dispatch.bubble == RegionBackend.OPENCV_NS) {
        val clusters = BubbleOpenCvInpainter.componentClusters(mask, w, h)

        // F7 memory gate (spec §3.1 item 3): a merged cluster can approach page
        // scale. Classical crops need ~4 int arrays of cropW*cropH — require the
        // largest crop to fit a quarter of available heap (mirrors
        // neuralInpaintDecision's budget shape without the neural-only native
        // reserve). On trip, use the legacy median fill for the whole mask —
        // exactly today's behavior, so the gate can only bound memory, never
        // regress output.
        val largest = clusters.maxOfOrNull { c ->
            (c[2] - c[0] + 2 * BubbleOpenCvInpainter.CROP_CONTEXT_PX).toLong() *
                (c[3] - c[1] + 2 * BubbleOpenCvInpainter.CROP_CONTEXT_PX)
        } ?: 0L
        val availableHeap = EngineMemoryBudget.heapSnapshot().availableHeapBytes
        if (largest * 4L * 4L > availableHeap / 4L) {
            logcat(LogPriority.WARN) {
                "[inpaint] bubble ns memory gate tripped (largestCrop=$largest px, " +
                    "heapAvailable=${availableHeap / (1L shl 20)}MiB); using median fill"
            }
            val pixels = IntArray(w * h)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            AotReportBubbleFill.fillAndBlend(pixels, mask, w, h, REPORT_BUBBLE_SMOOTH_PASSES, FEATHER_RAMP_PX)
            image.setPixels(pixels, 0, w, 0, 0, w, h)
            lastBubbleRoute = "median_fill_memgate"
            return image
        }

        val started = System.nanoTime()
        val backend = BubbleOpenCvInpainter.inpaintClusters(image, mask, clusters, w, h)
        lastBubbleRoute = backend.routeLabel
        if (backend == OpenCvInpaintEngine.Backend.PUSH_PULL_EMERGENCY) lastRunDegraded = true
        logcat(LogPriority.INFO) {
            "[inpaint] bubble_route=${backend.routeLabel} clusters=${clusters.size} " +
                "mode=$dispatch totalMs=${(System.nanoTime() - started) / 1_000_000.0}"
        }
        return image
    }

    // QUALITY until Task 9: legacy median fill.
    val pixels = IntArray(w * h)
    image.getPixels(pixels, 0, w, 0, 0, w, h)
    AotReportBubbleFill.fillAndBlend(pixels, mask, w, h, REPORT_BUBBLE_SMOOTH_PASSES, FEATHER_RAMP_PX)
    image.setPixels(pixels, 0, w, 0, 0, w, h)
    lastBubbleRoute = "median_fill"
    return image
}
```

Add the two fields next to `lastAcceptedRoute` (`:113-115`):

```kotlin
/** Route label of the most recent bubble pass (opencv_ns / push_pull_emergency / median_fill / aot_neural). */
@Volatile
var lastBubbleRoute: String? = null
    private set

/** True when any region fell back below its requested backend this run (F4). */
@Volatile
var lastRunDegraded: Boolean = false
    private set
```

Reset `lastRunDegraded = false` at the top of `inpaintRegions`, and set it:
- in `inpaintReportFreeTextFast` when `reason != "fast_mode"` (genuine fallbacks: `no_neural_session`, `memory_*`, `neural_exhausted`), **and**
- in `inpaintReportFreeTextFast` right after the `:712` backend call, unconditionally on reason (F8 — a mode whose contract is "OpenCV" must not silently be push-pull; this also covers FAST-mode free-text):

```kotlin
if (classicalBackend == OpenCvInpaintEngine.Backend.PUSH_PULL_EMERGENCY) lastRunDegraded = true
```

(The variable is already captured at `:712` for the log at `:717` — this adds the stamp.) Update the caller at `:462` to pass `dispatch`. Import `eu.kanade.translation.engines.runtime.EngineMemoryBudget` if not already present.

- [ ] **Step 2: Verify compile + full inpainting test package**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.*"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt
git commit -m "feat(inpaint): route FAST and BALANCE bubbles through crop-scoped OpenCV NS"
```

---

### Task 7: AOT decoupling — edge-replicate tensor padding

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AotPadPath.kt`
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt:977-1004`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/aot/AotPadPathTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `AotPadPathTest.kt`:

```kotlin
@Test
fun `replicate padding extends border pixels outward with the crop centered`() {
    val size = 4
    val source = IntArray(size * size) { it } // 0..15
    val padded = IntArray(AotPadPath.SIZE * AotPadPath.SIZE)
    AotPadPath.padSquareReplicateInto(source, size, padded)
    val offset = AotPadPath.centeredOffset(size)

    // Corners take the nearest source corner.
    padded[0] shouldBe source[0]
    padded[AotPadPath.SIZE - 1] shouldBe source[size - 1]
    padded[(AotPadPath.SIZE - 1) * AotPadPath.SIZE] shouldBe source[(size - 1) * size]
    padded[AotPadPath.SIZE * AotPadPath.SIZE - 1] shouldBe source[size * size - 1]

    // Center is the source itself.
    for (y in 0 until size) {
        for (x in 0 until size) {
            padded[(offset + y) * AotPadPath.SIZE + offset + x] shouldBe source[y * size + x]
        }
    }

    // Band above the crop clamps vertically to source row 0 (x clamps to column 0).
    padded[(offset - 1) * AotPadPath.SIZE + offset] shouldBe source[0]
    padded[offset * AotPadPath.SIZE + (offset - 1)] shouldBe source[0]
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.aot.AotPadPathTest"`
Expected: COMPILATION ERROR (`padSquareReplicateInto` unresolved).

- [ ] **Step 3: Implement**

Add **inside `internal object AotPadPath`** (the file is one object; a top-level placement cannot see the private `PADDED_PIXEL_COUNT` and breaks the `AotPadPath.padSquareReplicateInto` call sites):

```kotlin
/**
 * Pads [sourceSize]² pixels into [destination] centered, extending the crop's
 * border pixels outward (clamp-to-edge). The AOT model then sees continuous
 * artwork context matching its training distribution, instead of an invented
 * flat ring-median field — and the sampling pass disappears from the hot path
 * (spec §3.2). Only the centered source square is ever decoded back.
 */
fun padSquareReplicateInto(source: IntArray, sourceSize: Int, destination: IntArray) {
    centeredOffset(sourceSize) // validates range
    require(source.size >= sourceSize * sourceSize)
    require(destination.size >= PADDED_PIXEL_COUNT)
    val offset = centeredOffset(sourceSize)
    val last = sourceSize - 1
    for (y in 0 until SIZE) {
        val sy = (y - offset).coerceIn(0, last)
        val srcRow = sy * sourceSize
        val dstRow = y * SIZE
        for (x in 0 until SIZE) {
            destination[dstRow + x] = source[srcRow + (x - offset).coerceIn(0, last)]
        }
    }
}
```

In `AOTInpainting.prepareFixedInput` (`:992-1004`) delete the `background` computation and flat fill:

```kotlin
// before:
// val background = PushPullGradient.localRingMedian(...)
// java.util.Arrays.fill(paddedPixels, background)
// AotPadPath.padSquareInto(sourcePixels, side, background, paddedPixels)
// after:
val paddedPixels = getImgPixels()
AotPadPath.padSquareReplicateInto(sourcePixels, side, paddedPixels)
```

Leave the legacy `inpaint()` fixed-shape branch (`:1295-1306`) untouched — it is unreachable today (`tryNeuralCandidate` is the only caller and passes `fixedShape = false`, `AOTInpainting.kt:862`); note this in the commit message rather than deleting in the same change.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.aot.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AotPadPath.kt app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt app/src/test/java/eu/kanade/translation/engines/inpainting/aot/AotPadPathTest.kt
git commit -m "perf(inpaint): replace ring-median AOT tensor padding with edge replication"
```

---

### Task 8: >512px tiler for neural bubbles (and F2 regression foundation)

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AotBoxGeometry.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/aot/AotBoxGeometryTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `AotBoxGeometryTest.kt`:

```kotlin
@Test
fun `small bounds yield a single tile`() {
    AotBoxGeometry.tileBounds(10, 20, 200, 300).map { it.toList() } shouldBe
        listOf(listOf(10, 20, 200, 300))
}

@Test
fun `oversized bounds are fully covered by overlapping tiles`() {
    val tiles = AotBoxGeometry.tileBounds(0, 0, 1300, 700)
    // Every pixel of the bbox is inside some tile.
    val covered = BooleanArray(1300 * 700)
    for (t in tiles) {
        (t[2] - t[0] <= 448 && t[3] - t[1] <= 448) shouldBe true
        for (y in t[1] until t[3]) {
            for (x in t[0] until t[2]) {
                covered[y * 1300 + x] = true
            }
        }
    }
    covered.all { it } shouldBe true
}

@Test
fun `adjacent tiles overlap so seams have context`() {
    val tiles = AotBoxGeometry.tileBounds(0, 0, 1300, 100)
    val xs = tiles.sortedBy { it[0] }.map { it[0] to it[2] }
    for (i in 1 until xs.size) {
        (xs[i].first < xs[i - 1].second) shouldBe true // next starts before prev ends
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.aot.AotBoxGeometryTest"`
Expected: COMPILATION ERROR (`tileBounds` unresolved).

- [ ] **Step 3: Implement**

Add **inside `internal object AotBoxGeometry`** (same single-object layout; the test calls `AotBoxGeometry.tileBounds(...)`):

```kotlin
/**
 * Splits a bbox into <=[tile]-px windows with [overlap] px shared context
 * (spec §4.2). Small bounds return a single tile; coverage of the input rect
 * is exact, so no masked pixel can escape the neural pass (F2).
 */
internal fun tileBounds(
    x1: Int,
    y1: Int,
    x2: Int,
    y2: Int,
    tile: Int = 448,
    overlap: Int = 64,
): List<IntArray> {
    require(tile > overlap) { "tile=$tile must exceed overlap=$overlap" }
    if (x2 - x1 <= tile && y2 - y1 <= tile) return listOf(intArrayOf(x1, y1, x2, y2))
    val xs = axisWindows(x1, x2, tile, overlap)
    val ys = axisWindows(y1, y2, tile, overlap)
    val out = ArrayList<IntArray>(xs.size * ys.size)
    for (wx in xs) {
        for (wy in ys) {
            out.add(intArrayOf(wx[0], wy[0], wx[1], wy[1]))
        }
    }
    return out
}

private fun axisWindows(lo: Int, hi: Int, tile: Int, overlap: Int): List<IntArray> {
    if (hi - lo <= tile) return listOf(intArrayOf(lo, hi))
    val step = tile - overlap
    val starts = ArrayList<Int>()
    var s = lo
    while (hi - s > tile) {
        starts.add(s)
        s += step
    }
    starts.add(s) // hi - s <= tile, so this last window reaches hi
    return starts.map { intArrayOf(it, min(hi, it + tile)) }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.aot.AotBoxGeometryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AotBoxGeometry.kt app/src/test/java/eu/kanade/translation/engines/inpainting/aot/AotBoxGeometryTest.kt
git commit -m "feat(inpaint): add overlapping tileBounds for oversized neural regions"
```

---

### Task 9: QUALITY neural bubble path

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt` (bubble branch from Task 6 + new function)

**Manual gate:** this is the highest-risk task — workbench A/B before merge on **large (>512px) bubble groups, edge-clipped bubbles, overlapping bubbles, and at least one sub-512 page** (the downscale-retry corpus has them; the side clamp above is what keeps those pages from throwing); QNN device + emulator both.

- [ ] **Step 1: Extract the reusable neural core**

From `inpaintReportFreeTextNeural` (`:722-924`) extract everything from the memory gate through the `AotExecutionCoordinator.run` call into a private function that takes an already-built mask:

```kotlin
/**
 * Runs the fixed-512 AOT path on an arbitrary local mask. Returns the decoded
 * candidate for the square crop, or null when every backend failed (caller
 * falls back to the classical path). Shared by free-text (pill masks) and
 * bubbles (real seg/pill merged masks) — spec §4.2.
 */
private fun neuralFillSquare(
    image: Bitmap,
    localMaskBytes: ByteArray,
    crop: IntArray,
    side: Int,
): IntArray? {
    // 1. memory gate (existing neuralInpaintDecision block, :732-751) — on
    //    !canRun return null (caller logs reason and uses classical).
    // 2. build ALPHA_8 maskBitmap from localMaskBytes (setAlphaMaskPixels, :1208-1214).
    // 3. prepareFixedInput → AotExecutionCoordinator.run (accelerator → cpu →
    //    dynamic; NO telea slot here — return null on total failure instead,
    //    the bubble/free-text callers own their classical fallbacks).
    // 4. decode + return candidate pixels (Neural result.value); return null
    //    for Result.Telea-equivalent (total failure).
}
```

Re-point `inpaintReportFreeTextNeural` to call `neuralFillSquare` with its pill-mask bytes; its compositing/feather tail (`:896-909`) stays. Keep behavior byte-identical for free text — same masks, same memory-gate log tags, same `lastAcceptedRoute` updates inside the extracted function.

- [ ] **Step 2: Implement the neural bubble branch**

In `inpaintReportBubbles` (Task 6's version), replace the QUALITY arm:

```kotlin
if (dispatch.bubble == RegionBackend.AOT_NEURAL) {
    val clusters = BubbleOpenCvInpainter.componentClusters(mask, w, h)
    var anyNeuralFailure = false
    for (cluster in clusters) {
        val tiles = AotBoxGeometry.tileBounds(cluster[0], cluster[1], cluster[2], cluster[3])
        for (t in tiles) {
            // Clamp to the page too, not just the tensor limit: a sub-512 page
            // (retryInpaintDownscaled exists for exactly this) would otherwise
            // make coerceIn(0, w - side) throw. Mirrors centeredReportCrop's
            // min(contextSize, min(width, height)) guard (AotBoxGeometry.kt:32).
            val side = (maxOf(t[2] - t[0], t[3] - t[1]) + 2 * REPORT_INPAINT_CONTEXT)
                .coerceAtMost(minOf(AotPadPath.SIZE, w, h))
            val sx1 = (t[0] - (side - (t[2] - t[0])) / 2).coerceIn(0, w - side)
            val sy1 = (t[1] - (side - (t[3] - t[1])) / 2).coerceIn(0, h - side)
            val crop = intArrayOf(sx1, sy1, sx1 + side, sy1 + side)
            val localMask = cropMask(mask, w, h, crop, side)
            if (localMask.none { it != 0.toByte() }) continue
            val candidate = neuralFillSquare(image, localMask, crop, side)
            if (candidate == null) {
                anyNeuralFailure = true
                continue
            }
            compositeNeuralTile(image, candidate, localMask, crop, side)
        }
    }
    if (anyNeuralFailure) {
        // Classical catch-up for the failed tiles (never partial-erase: F2).
        BubbleOpenCvInpainter.inpaintClusters(image, mask, clusters, w, h)
        lastRunDegraded = true
        lastBubbleRoute = "aot_neural_ns_catchup"
    } else {
        lastBubbleRoute = "aot_neural"
    }
    return image
}
```

Add the two small helpers (`cropMask` — crop full-page mask into the square, zero outside the tile region but keep the *mask* limited to the cluster∩tile: mask pixels only, matching what NS/neural see; `compositeNeuralTile` — the `:896-909` pattern: read originals, featherAlphaField ramp 3, `AotPixelOps.compositeInto`, setPixels). Sequential tile compositing over re-read originals gives smooth overlap bands (each tile blends against current page state).

- [ ] **Step 3: Dispatch-level regression test (JVM)**

The Bitmap/neural path needs a device; the enforceable JVM property is the tiler+coverage contract (Task 8 tests) plus dispatch wiring. Add to `RegionDispatchTest`:

```kotlin
@Test
fun `QUALITY dispatches bubbles to AOT only when sessions exist`() {
    QUALITY.resolveDispatch(true).bubble shouldBe RegionBackend.AOT_NEURAL
    QUALITY.resolveDispatch(false).bubble shouldBe RegionBackend.OPENCV_NS
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt app/src/test/java/eu/kanade/translation/engines/inpainting/RegionDispatchTest.kt
git commit -m "feat(inpaint): neural bubble path with >512 tiling and NS catch-up fallback"
```

---

### Task 10: Effective-route stamping end-to-end (F4/F5/F8)

**Design:** stored stamps are `MODE` or `MODE_DEGRADED`; resume reuse is decided by a **pure predicate**, not string equality against a desired stamp. This is loop-free by construction: a degraded page re-inpaints only when neural is *positively* known to be available again (unknown availability — engines not yet built — reuses instead of churning). FAST pages degraded by a push-pull emergency never re-inpaint on the stamp (the device cannot do better), but the honest stamp persists for diagnostics. Known tradeoff: if neural availability *flaps* (available at resume, degrades again during the run), the page re-inpaints once per resume — accepted, spec-conformant, and bounded by one re-inpaint per run.

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/RegionDispatch.kt` (add decision type + predicate)
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt` (markReady)
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt` (add `inpaintingStampDecision`)
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt` (expose availability)
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt:87,283-284,866`
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt:362,1222`
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt:88,495`
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumePlanner.kt:50,252-255,285`
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt:170,211,308,333`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/RegionDispatchTest.kt`, `PageInpaintingEngineTest.kt`
- Test wiring fakes: `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt:619`, `app/src/test/java/eu/kanade/translation/pipeline/BatchTelemetryTest.kt:421`, `app/src/test/java/eu/kanade/translation/pipeline/batch/BatchTerminalExitTest.kt:68,152`

- [ ] **Step 1: Write the failing tests**

Add to `RegionDispatchTest.kt`:

```kotlin
@Test
fun `resume predicate reuses clean and legacy stamps`() {
    val decision = InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = false)
    decision.stampNeedsReinpaint(stored = null) shouldBe false // legacy page
    decision.stampNeedsReinpaint(stored = "BALANCE") shouldBe false
}

@Test
fun `degraded stamp upgrades only on positive neural recovery`() {
    val degradedStored = "BALANCE_DEGRADED"
    InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = null)
        .stampNeedsReinpaint(degradedStored) shouldBe false // engines not built yet — no churn
    InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = false)
        .stampNeedsReinpaint(degradedStored) shouldBe false // still unavailable — no loop
    InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = true)
        .stampNeedsReinpaint(degradedStored) shouldBe true  // recovered — upgrade once (F4)
}

@Test
fun `mode switch or junk stamp re-inpaints, FAST emergency never does`() {
    InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = true)
        .stampNeedsReinpaint("QUALITY") shouldBe true
    InpaintStampDecision(InpaintingMode.FAST, neuralAvailable = null)
        .stampNeedsReinpaint("FAST_DEGRADED") shouldBe false // push-pull emergency: honest, not looped
}
```

Add to `PageInpaintingEngineTest.kt`:

```kotlin
@Test
fun `degraded neural run stamps mode with DEGRADED suffix`() {
    val inpainter = mockk<AOTInpainting>()
    val cleaned = mockk<Bitmap>()
    every { inpainter.isInitialized() } returns true
    every { inpainter.lastRunDegraded } returns false
    every { inpainter.inpaintRegions(any(), any(), any(), any(), any()) } returns cleaned
    val page = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }

    PageInpaintingEngine(InpaintingMode.BALANCE, inpainter).inpaint(bitmap(), page) shouldBe cleaned
    page.inpaintingModeUsed shouldBe "BALANCE" // healthy run: clean stamp

    every { inpainter.lastRunDegraded } returns true // a region fell back this run
    val page2 = PageTranslation().apply { inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 2)) }
    PageInpaintingEngine(InpaintingMode.BALANCE, inpainter).inpaint(bitmap(), page2) shouldBe cleaned
    page2.inpaintingModeUsed shouldBe "BALANCE_DEGRADED"
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.RegionDispatchTest" --tests "eu.kanade.translation.engines.inpainting.PageInpaintingEngineTest"`
Expected: COMPILATION ERROR (`InpaintStampDecision` unresolved) and FAIL (`inpaintingModeUsed` null).

- [ ] **Step 3: Implement**

Add to `RegionDispatch.kt`:

```kotlin
/** Resume-decision inputs: desired mode + neural availability (null = engines not built yet). */
data class InpaintStampDecision(
    val mode: InpaintingMode,
    val neuralAvailable: Boolean?,
)

/**
 * Loop-free resume rule (F4). Stored stamps are `MODE` or `MODE_DEGRADED`:
 *  - null stored (page persisted before the field existed) → reuse
 *  - stored == desired mode, clean → reuse
 *  - stored == desired + `_DEGRADED` → re-inpaint ONLY on positively known
 *    neural recovery (unknown availability reuses, so an unbuilt engine can
 *    never churn degraded pages; FAST's push-pull emergency is terminal)
 *  - anything else (mode switch, junk) → re-inpaint
 */
fun InpaintStampDecision.stampNeedsReinpaint(stored: String?): Boolean = when {
    stored == null -> false
    stored == mode.name -> false
    stored == mode.name + InpaintingMode.DEGRADED_SUFFIX ->
        mode != InpaintingMode.FAST && neuralAvailable == true
    else -> true
}
```

`PageInpaintingEngine.markReady` becomes (note: `markReady` runs both for the empty-plan early return at `:39` — outside the try — and the success path at `:85`):

```kotlin
private fun markReady(pageTranslation: PageTranslation) {
    pageTranslation.inpaintStatus = StageStatus.READY
    pageTranslation.errorMessage = null
    // Effective-route stamp (F4): `_DEGRADED` records that some region fell
    // below its requested backend; the resume predicate upgrades the page once
    // neural is healthy again without looping while it stays degraded.
    pageTranslation.inpaintingModeUsed = if (inpainter.lastRunDegraded) {
        mode.name + InpaintingMode.DEGRADED_SUFFIX
    } else {
        mode.stampName(neuralAvailable = inpainter.isInitialized())
    }
    pageTranslation.updatedAt = System.currentTimeMillis()
}
```

**Update the existing strict-mock tests in `PageInpaintingEngineTest.kt`** (markReady now reads `lastRunDegraded`/`isInitialized`; unstubbed reads on strict mockk throw):
- `textless page with no erase regions…` (`:35-45`): add `every { inpainter.isInitialized() } returns false` and `every { inpainter.lastRunDegraded } returns false`, and change `verify { inpainter wasNot Called }` to `verify { inpainter.inpaintRegions(any(), any(), any(), any(), any()) wasNot Called }` (property reads in markReady are now expected).
- `assertMaskProcessed` (`:62-81`): add `every { inpainter.lastRunDegraded } returns false`.
- The Task 2 tests already stub `inpaintRegions`; add the same `lastRunDegraded` stub to any of them that assert READY.

`EngineLane` — add next to `inpaintingModeFromPref` (`:341`):

```kotlin
/** Resume decision inputs. Availability is null until the recognition engine is a
 *  RoiPageRecognitionEngine (placeholder before build) — the predicate treats
 *  unknown as reuse, so no spurious re-inpaint cycles. */
internal fun inpaintingStampDecision(): InpaintStampDecision {
    val mode = inpaintingModeFromPref()
    val neuralReady = (recognitionEngine as? RoiPageRecognitionEngine)?.neuralInpaintAvailable()
    return InpaintStampDecision(mode, neuralReady)
}
```

`RoiPageRecognitionEngine` — add:

```kotlin
fun neuralInpaintAvailable(): Boolean = inpainting?.isInitialized() == true
```

`SinglePageOnnxPhase.kt` — add a sibling to the private delegate at `:87`:

```kotlin
private fun inpaintingStampDecision(): InpaintStampDecision = engines.inpaintingStampDecision()
```

and replace `:283-284`:

```kotlin
val stampDecision = inpaintingStampDecision()
val modeMatches = !stampDecision.stampNeedsReinpaint(resumeTranslation?.inpaintingModeUsed)
```

At `:866`, only fill a missing stamp (markReady owns the effective one):

```kotlin
if (pageTranslation.inpaintingModeUsed == null) {
    pageTranslation.inpaintingModeUsed = currentInpaintingMode.name
}
```

`TranslationPipeline.kt` — add a sibling delegate at `:362`:

```kotlin
private fun inpaintingStampDecision(): InpaintStampDecision = engines.inpaintingStampDecision()
```

`BatchChapterTranslator.kt` — keep its `inpaintingModeFromPref: () -> InpaintingMode` field at `:88` (still used for telemetry at `:833`/`:880`), add a second constructor param after it:

```kotlin
private val inpaintingStampDecision: () -> InpaintStampDecision,
```

(imports: `eu.kanade.translation.engines.inpainting.InpaintStampDecision`, `stampNeedsReinpaint` — same imports at the other new-use sites). At `:495`, the existing forward `inpaintingModeFromPref = inpaintingModeFromPref` feeds BatchResumePlanner, which no longer takes that param — **replace** it with `inpaintingStampDecision = inpaintingStampDecision`.

`BatchResumePlanner.kt` — at `:50`, **replace** `private val inpaintingModeFromPref: () -> InpaintingMode,` with:

```kotlin
private val inpaintingStampDecision: () -> InpaintStampDecision,
```

Replace `:254-255` (the legacy comment at `:252-253` stays accurate — keep it):

```kotlin
val stampDecision = inpaintingStampDecision()
val inpaintModeMatches = !stampDecision.stampNeedsReinpaint(page?.inpaintingModeUsed)
```

and fix the orphaned reference in the log line at `:285` in the same edit (`now=$desiredMode` → `now=${stampDecision.mode.name}`) — `desiredMode` no longer exists.

`TranslationPipeline.kt:1222` — add `inpaintingStampDecision = this::inpaintingStampDecision,`.

Test wiring fakes — four constructor sites pass the old mode lambda (found by grepping `inpaintingModeFromPref =` in `app/src/test`). **All four construct `BatchChapterTranslator`** (which keeps its mode param for telemetry), so at each site **keep** the existing mode arg and **add** the stamp arg:
- `TranslationCoexistenceHarness.kt:619` → keep mode line, add `inpaintingStampDecision = { engineLane.inpaintingStampDecision() },`
- `BatchTelemetryTest.kt:421` → keep mode line, add `inpaintingStampDecision = { error("not expected") },`
- `BatchTerminalExitTest.kt:68` → keep mode line, add `inpaintingStampDecision = { error("inpainting stamp not expected on this exit") },`
- `BatchTerminalExitTest.kt:152` → keep mode line, add `inpaintingStampDecision = { InpaintStampDecision(InpaintingMode.FAST, neuralAvailable = null) },`

**`CleanedPublication.kt` — stop overwriting the honest stamp (4 sites).** The publish-commit paths at `:170`, `:211`, `:308`, `:333` each write `inpaintingModeUsed = currentInpaintingMode().name` unconditionally, clobbering the `*_DEGRADED` stamp markReady just wrote on the same `pageTranslation`. Replace all four with the preserve form (`pageTranslation` is in scope at every site — each already reads `pageTranslation.inpaintFingerprint` next to it):

```kotlin
inpaintingModeUsed = pageTranslation.inpaintingModeUsed ?: currentInpaintingMode().name
```

**`PageDecode.kt:161-165` — deliberately unchanged, documented decision.** The batch inpaint *fingerprint* stays keyed on the selected mode name (`currentInpaintingMode.name`). Fingerprints encode *configuration identity* (mode switch / mask-revision change → invalidate); folding the degraded suffix into the expected fingerprint would make a still-degraded page mismatch every run and re-inpaint forever. Degradation recovery is owned solely by the stamp predicate above. Add a one-line comment above `:161`:

```kotlin
// Configuration identity only: degradation/recovery is handled by the
// inpaintingModeUsed stamp predicate (see InpaintStampDecision), NOT here —
// folding the degraded suffix in would re-inpaint degraded pages every run.
```

- [ ] **Step 4: Run the affected suites**

Run: `./gradlew :app:testDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.pipeline.*" --tests "eu.kanade.translation.coexistence.*"`
Expected: PASS.

**Spec sync (one line, same PR):** spec §4.3's stamp row says "make resume-reuse + PageDecode fingerprint consume it". Amend that row in `docs/superpowers/specs/2026-10-09-inpainting-modes-redesign.md` to record the implemented decision: *"resume-reuse consumes the stamp via the `InpaintStampDecision` predicate; the PageDecode fingerprint deliberately stays keyed on the selected mode (folding the degraded suffix in would re-inpaint still-degraded pages every run)"* — so spec and code don't drift.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/eu/kanade/translation app/src/test/java/eu/kanade/translation i18n-at docs/superpowers/specs/2026-10-09-inpainting-modes-redesign.md
git commit -m "feat(inpaint): effective-route stamps with loop-free degraded resume predicate"
```

---

### Task 11: Full verification pass

- [ ] **Step 1:** Run the whole unit suite: `./gradlew :app:testDebugUnitTest` — Expected: PASS (no regressions outside inpainting; watch `coexistence` and `persistence` suites that touch `inpaintingModeUsed`).
- [ ] **Step 2:** Assemble: `./gradlew :app:assembleDebug` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Device matrix smoke (manual): QNN HTP phone — all three modes over a 5-page chapter; emulator (push-pull emergency path) — FAST; verify logcat `[inpaint] bubble_route=` lines match the dispatch table and `inpaintingModeUsed` stamps in the chapter store show `*_DEGRADED` only when earned.
- [ ] **Step 4:** Commit any fixes; final message: `test(inpaint): three-mode verification pass`.

---

## Out of scope (spec §7 — separate decisions)

Successor model evaluation (LaMa-class), free-text NS-vs-Telea switch, F10 unread-block seg-mask rasterization, legacy `inpaint()` fixed-shape dead-branch deletion, dynamic-model `[−1,1]` unification with the fixed path's Qualcomm-AI-Hub `[0,1]` contract (verify via workbench first). Also considered-and-declined: folding the degraded suffix into the `PageDecode` batch inpaint fingerprint (would re-inpaint still-degraded pages every run — configuration identity stays keyed on the selected mode; degradation recovery is owned by the `InpaintStampDecision` predicate, see Task 10).
