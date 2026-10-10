# LaMa Manga Neural Inpainting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the LaMa Manga model (`Liiesl/lama-manga-onnx-quant`, 59.8 MB) into TachiyomiAT as the default, recommended neural inpainting model for `Balance` and `Quality` modes while keeping `AOT-GAN` as a legacy option.

**Architecture:** Introduce `NeuralInpaintModel` (`LAMA_MANGA` vs `AOT_GAN`) sub-preference; abstract model tensor interactions behind `NeuralInpaintModelContract`; implement `LamaMangaTensorContract` with single `[1, 4, 512, 512]` input tensor; share existing 448px overlapping tiling and edge-replication padding; update session lifecycle in `EngineLane` and publication route stamping in `CleanedPublication`.

**Tech Stack:** Kotlin, ONNX Runtime (Mobile), Android Jetpack Compose, JUnit 4, Robolectric.

## Global Constraints
- Preserve existing `AotBoxGeometry.tileBounds` (448px tiles, 64px overlap) and `AotPadPath.padSquareReplicateInto` contracts without behavioral regression.
- LaMa Manga model: `lama-manga.onnx`, size `59,809,275` bytes, SHA-256 `502ce98fbd8d030501040d4505daea0616003266f4eb82b1d27fd4fca5b55f4a`.
- Do not execute heavy full-suite Gradle builds unprompted to avoid Gradle daemon contention; execute targeted single-class unit tests.
- Maintain honest degraded route stamping (`QUALITY:LAMA_DEGRADED`, `QUALITY:AOT_DEGRADED`) and re-inpaint on model preference change.

---

### Task 1: Model Manifest and Model Store Integration

**Files:**
- Modify: `scripts/models.manifest`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/data/translation/OnnxModelStore.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/data/translation/OnnxModelStoreTest.kt`

**Interfaces:**
- Consumes: Manifest schema in `scripts/models.manifest`.
- Produces: `OnnxModelStore.getLamaMangaModelFile(): File` and resolution in `ensureModels()`.

- [ ] **Step 1: Write failing unit test for `OnnxModelStore` resolving `lama-manga.onnx`**

```kotlin
// app/src/test/java/eu/kanade/tachiyomi/data/translation/OnnxModelStoreTest.kt
@Test
fun testLamaMangaModelFileResolution() {
    val store = OnnxModelStore(context)
    val file = store.getLamaMangaModelFile()
    assertEquals("lama-manga.onnx", file.name)
}
```

- [ ] **Step 2: Run test to confirm failure**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.data.translation.OnnxModelStoreTest"` and verify unresolved method error.

- [ ] **Step 3: Update `scripts/models.manifest` and `OnnxModelStore.kt`**
Add manifest entry:
```
path: app/src/main/assets/models/inpainting/lama-manga.onnx
url: https://huggingface.co/Liiesl/lama-manga-onnx-quant/resolve/51d07e18caf9b1258585d3706fec8986ccbb8cfb/lama-manga_int8.onnx
size: 59809275
sha256: 502ce98fbd8d030501040d4505daea0616003266f4eb82b1d27fd4fca5b55f4a
license: Apache-2.0
```
In `OnnxModelStore.kt`, add:
```kotlin
fun getLamaMangaModelFile(): File = File(modelsDir, "lama-manga.onnx")
```
and ensure it is copied in `ensureModels()` when asset is present.

- [ ] **Step 4: Run test to verify passing**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.data.translation.OnnxModelStoreTest"`.

- [ ] **Step 5: Commit changes**
`git add scripts/models.manifest app/src/main/java/eu/kanade/tachiyomi/data/translation/OnnxModelStore.kt app/src/test/java/eu/kanade/tachiyomi/data/translation/OnnxModelStoreTest.kt`
`git commit -m "feat(inpainting): add LaMa Manga model to manifest and OnnxModelStore"`

---

### Task 2: Preference and UI Enums for Neural Model

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/engines/inpainting/NeuralInpaintModel.kt`
- Modify: `app/src/main/java/eu/kanade/domain/translation/TranslationPreferences.kt`
- Modify: `i18n-at/src/commonMain/moko-resources/base/strings.xml`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/NeuralInpaintModelTest.kt`

**Interfaces:**
- Consumes: `Preference<T>` from Tachiyomi core preference framework.
- Produces: `enum class NeuralInpaintModel { LAMA_MANGA, AOT_GAN }`, `translationPreferences.translationInpaintingNeuralModel(): Preference<NeuralInpaintModel>`.

- [ ] **Step 1: Write test for enum parsing and preference defaults**

```kotlin
// app/src/test/java/eu/kanade/translation/engines/inpainting/NeuralInpaintModelTest.kt
package eu.kanade.translation.engines.inpainting

import org.junit.Assert.assertEquals
import org.junit.Test

class NeuralInpaintModelTest {
    @Test
    fun defaultModelIsLamaManga() {
        assertEquals(NeuralInpaintModel.LAMA_MANGA, NeuralInpaintModel.DEFAULT)
    }

    @Test
    fun parseOrFallbackReturnsDefaultOnInvalid() {
        assertEquals(NeuralInpaintModel.LAMA_MANGA, NeuralInpaintModel.fromPrefOrNull("INVALID") ?: NeuralInpaintModel.DEFAULT)
        assertEquals(NeuralInpaintModel.AOT_GAN, NeuralInpaintModel.fromPrefOrNull("AOT_GAN"))
    }
}
```

- [ ] **Step 2: Run test to verify failure**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.NeuralInpaintModelTest"`.

- [ ] **Step 3: Implement `NeuralInpaintModel` and register in `TranslationPreferences`**
Create `app/src/main/java/eu/kanade/translation/engines/inpainting/NeuralInpaintModel.kt`:
```kotlin
package eu.kanade.translation.engines.inpainting

enum class NeuralInpaintModel(val prefValue: String) {
    LAMA_MANGA("LAMA_MANGA"),
    AOT_GAN("AOT_GAN");

    companion object {
        val DEFAULT = LAMA_MANGA

        fun fromPrefOrNull(value: String?): NeuralInpaintModel? {
            return entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
        }
    }
}
```
Add preference getter to `TranslationPreferences.kt` and string resources in `strings.xml`.

- [ ] **Step 4: Run test to verify success**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.NeuralInpaintModelTest"`.

- [ ] **Step 5: Commit changes**
`git add app/src/main/java/eu/kanade/translation/engines/inpainting/NeuralInpaintModel.kt app/src/main/java/eu/kanade/domain/translation/TranslationPreferences.kt i18n-at/src/commonMain/moko-resources/base/strings.xml app/src/test/java/eu/kanade/translation/engines/inpainting/NeuralInpaintModelTest.kt`
`git commit -m "feat(inpainting): introduce NeuralInpaintModel preference and strings"`

---

### Task 3: Tensor Contract Abstraction & LaMa Contract

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/NeuralInpaintModelContract.kt`
- Create: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/LamaMangaTensorContract.kt`
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AotFixedTensorContract.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/aot/LamaMangaTensorContractTest.kt`

**Interfaces:**
- Consumes: ARGB pixel arrays and binary mask arrays from `AotPadPath`.
- Produces: `NeuralInpaintModelContract` interface, `LamaMangaTensorContract` object, `AotFixedTensorContract` implementing `NeuralInpaintModelContract`.

- [ ] **Step 1: Write failing unit test for `LamaMangaTensorContract`**

```kotlin
// app/src/test/java/eu/kanade/translation/engines/inpainting/aot/LamaMangaTensorContractTest.kt
package eu.kanade.translation.engines.inpainting.aot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer

class LamaMangaTensorContractTest {
    @Test
    fun testWriteInputsChannels() {
        val size = 512
        val img = IntArray(size * size) { 0xFF808080.toInt() } // gray
        val mask = IntArray(size * size) { if (it < 10) 255 else 0 } // first 10 pixels masked
        val buffer = FloatBuffer.allocate(4 * size * size)

        LamaMangaTensorContract.writeInput(img, mask, size, buffer)
        buffer.flip()

        // Masked pixels: RGB channels should be 0.0f
        assertEquals(0.0f, buffer.get(0), 1e-4f) // Ch0 Red
        assertEquals(0.0f, buffer.get(size * size), 1e-4f) // Ch1 Green
        assertEquals(0.0f, buffer.get(2 * size * size), 1e-4f) // Ch2 Blue
        // Mask channel: should be 1.0f
        assertEquals(1.0f, buffer.get(3 * size * size), 1e-4f) // Ch3 Mask

        // Unmasked pixels (index 10): RGB should be ~0.5f, mask should be 0.0f
        val unmaskedIdx = 10
        val expectedVal = 128f / 255f
        assertEquals(expectedVal, buffer.get(unmaskedIdx), 1e-3f)
        assertEquals(expectedVal, buffer.get(size * size + unmaskedIdx), 1e-3f)
        assertEquals(expectedVal, buffer.get(2 * size * size + unmaskedIdx), 1e-3f)
        assertEquals(0.0f, buffer.get(3 * size * size + unmaskedIdx), 1e-4f)
    }

    @Test
    fun testDecodeOutputRange() {
        val size = 512
        val buffer = FloatBuffer.allocate(3 * size * size)
        // fill with 1.0f (white)
        for (i in 0 until 3 * size * size) buffer.put(1.0f)
        buffer.flip()

        val decoded = LamaMangaTensorContract.decodeOutput(buffer, longArrayOf(1, 3, 512, 512), 512, 0, false)
        assertEquals(-1, decoded[0]) // 0xFFFFFFFF (opaque white)
    }
}
```

- [ ] **Step 2: Run test to verify failure**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.aot.LamaMangaTensorContractTest"`.

- [ ] **Step 3: Implement `NeuralInpaintModelContract`, `LamaMangaTensorContract`, and refactor `AotFixedTensorContract`**
1. Define `NeuralInpaintModelContract.kt`:
```kotlin
package eu.kanade.translation.engines.inpainting.aot

import java.nio.FloatBuffer

internal interface NeuralInpaintModelContract {
    val modelId: String
    val isSingleInputTensor: Boolean
    val inputTensorName: String
}
```
2. Implement `LamaMangaTensorContract.kt`:
Planar NCHW `[1, 4, 512, 512]` input packing:
- Channel 0: `(r / 255f) * (1f - m)`
- Channel 1: `(g / 255f) * (1f - m)`
- Channel 2: `(b / 255f) * (1f - m)`
- Channel 3: `m` (where `m = 1.0f` if `maskVal > 0` else `0.0f`)
Output decoding: `round(val * 255.0f).toInt().coerceIn(0, 255)` assembled into ARGB integer.
3. Update `AotFixedTensorContract.kt` to conform to `NeuralInpaintModelContract`.

- [ ] **Step 4: Run tests to verify success**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.aot.*"`.

- [ ] **Step 5: Commit changes**
`git add app/src/main/java/eu/kanade/translation/engines/inpainting/aot/NeuralInpaintModelContract.kt app/src/main/java/eu/kanade/translation/engines/inpainting/aot/LamaMangaTensorContract.kt app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AotFixedTensorContract.kt app/src/test/java/eu/kanade/translation/engines/inpainting/aot/LamaMangaTensorContractTest.kt`
`git commit -m "feat(inpainting): add NeuralInpaintModelContract and LamaMangaTensorContract"`

---

### Task 4: Unified Session Routing in `AOTInpainting` and `EngineLane`

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt`
- Modify: `app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt`
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngineTest.kt`

**Interfaces:**
- Consumes: `NeuralInpaintModel` from settings/lane configuration.
- Produces: Correct OrtSession dispatch (`lama-manga.onnx` vs `aot-512.onnx`), session reconstruction when `neuralModel` changes.

- [ ] **Step 1: Write unit test verifying `EngineLane` reloads session on neural model change**

```kotlin
// app/src/test/java/eu/kanade/translation/pipeline/EngineLaneNeuralModelTest.kt
@Test
fun testShouldRebuildRecognitionWhenNeuralModelChanges() {
    val lane = EngineLane(...)
    val rebuild = lane.shouldRebuildRecognition(
        oldMode = InpaintingMode.QUALITY,
        newMode = InpaintingMode.QUALITY,
        oldModel = NeuralInpaintModel.AOT_GAN,
        newModel = NeuralInpaintModel.LAMA_MANGA
    )
    assertTrue(rebuild)
}
```

- [ ] **Step 2: Run test to verify failure**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.EngineLaneNeuralModelTest"`.

- [ ] **Step 3: Integrate model selection in `AOTInpainting` and `EngineLane`**
- In `AOTInpainting.kt`, accept `NeuralInpaintModel` in constructor or initialization.
  - If `LAMA_MANGA`: Load `lama-manga.onnx`. Execute using `LamaMangaTensorContract`.
  - If `AOT_GAN`: Load `aot-512.onnx`. Execute using `AotFixedTensorContract`.
- In `PageInpaintingEngine.kt`, forward active `NeuralInpaintModel`.
- In `EngineLane.kt`, store active `neuralModel` and trigger session rebuild in `shouldRebuildRecognition` when it changes.

- [ ] **Step 4: Run tests to verify success**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.PageInpaintingEngineTest"`.

- [ ] **Step 5: Commit changes**
`git add app/src/main/java/eu/kanade/translation/engines/inpainting/aot/AOTInpainting.kt app/src/main/java/eu/kanade/translation/engines/inpainting/PageInpaintingEngine.kt app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt`
`git commit -m "feat(inpainting): support LaMa Manga and AOT-GAN switching in AOTInpainting and EngineLane"`

---

### Task 5: Publication Route Stamping & Resume Rules

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt`
- Modify: `app/src/main/java/eu/kanade/translation/pipeline/InpaintStampDecision.kt`
- Test: `app/src/test/java/eu/kanade/translation/pipeline/InpaintStampDecisionTest.kt`

**Interfaces:**
- Consumes: Target `InpaintingMode` + target `NeuralInpaintModel`.
- Produces: Composite route stamps (`QUALITY:LAMA`, `BALANCE:AOT`, etc.) and `stampNeedsReinpaint(existingStamp, desiredMode, desiredModel): Boolean`.

- [ ] **Step 1: Write test for composite stamps and re-inpaint predicate**

```kotlin
// app/src/test/java/eu/kanade/translation/pipeline/InpaintStampDecisionTest.kt
@Test
fun testStampNeedsReinpaintWhenNeuralModelDiffers() {
    val existingStamp = "QUALITY:AOT"
    val needsReinpaint = InpaintStampDecision.stampNeedsReinpaint(
        existingStamp = existingStamp,
        desiredMode = InpaintingMode.QUALITY,
        desiredModel = NeuralInpaintModel.LAMA_MANGA
    )
    assertTrue(needsReinpaint)
}

@Test
fun testStampDoesNotNeedReinpaintWhenMatching() {
    val existingStamp = "QUALITY:LAMA"
    val needsReinpaint = InpaintStampDecision.stampNeedsReinpaint(
        existingStamp = existingStamp,
        desiredMode = InpaintingMode.QUALITY,
        desiredModel = NeuralInpaintModel.LAMA_MANGA
    )
    assertFalse(needsReinpaint)
}
```

- [ ] **Step 2: Run test to verify failure**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.InpaintStampDecisionTest"`.

- [ ] **Step 3: Implement route stamp composition and evaluation**
Update `CleanedPublication.kt` to format stamp strings with neural model tag when mode is `BALANCE` or `QUALITY`.
Update `InpaintStampDecision.kt` to parse the neural model suffix from stamps and check against `desiredModel`.

- [ ] **Step 4: Run test to verify success**
Run `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.InpaintStampDecisionTest"`.

- [ ] **Step 5: Commit changes**
`git add app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt app/src/main/java/eu/kanade/translation/pipeline/InpaintStampDecision.kt app/src/test/java/eu/kanade/translation/pipeline/InpaintStampDecisionTest.kt`
`git commit -m "feat(inpainting): add composite neural model stamps and resume invalidation"`

---

### Task 6: Settings UI and Reader Translation Sheet Integration

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/reader/TranslationSettingsSheet.kt`

**Interfaces:**
- Consumes: `translationPreferences.translationInpaintingNeuralModel()`, `translationPreferences.inpaintingMode()`.
- Produces: Conditional UI item displaying neural model selection dropdown/list when inpainting mode != `FAST`.

- [ ] **Step 1: Add neural model preference in `SettingsTranslationScreen.kt`**
When `inpaintingMode` is `BALANCE` or `QUALITY`, display `ListPreference` for `translationInpaintingNeuralModel`.
Options:
- "LaMa Manga (Recommended)" (`LAMA_MANGA`)
- "AOT-GAN (Legacy)" (`AOT_GAN`)

- [ ] **Step 2: Add neural model selector in `TranslationSettingsSheet.kt`**
Display the dropdown selector for `Neural Model` immediately beneath the Inpainting Mode selector if mode is `BALANCE` or `QUALITY`.

- [ ] **Step 3: Verification**
Verify that selecting `Fast` hides the dropdown, while `Balance` or `Quality` reveals it.
Run `./gradlew :app:assembleStandardDebug -x lint` (or compile check).

- [ ] **Step 4: Commit changes**
`git add app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt app/src/main/java/eu/kanade/presentation/reader/TranslationSettingsSheet.kt`
`git commit -m "feat(ui): expose neural inpainting model selector in settings and reader sheet"`

---

### Task 7: Full Verification and Regression Gate

**Files:**
- All touched files.

- [ ] **Step 1: Execute targeted inpainting test suite**
Run:
```bash
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.engines.inpainting.*"
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.pipeline.*"
```
Ensure all tests pass cleanly.

- [ ] **Step 2: Verify git status and clean working tree**
Confirm no unintended modifications or stray files remain.
