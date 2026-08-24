# Subagent Test Contracts

**Date:** 2026-07-12
**Scope:** Companion to `MASTER_IMPLEMENTATION_PLAN_2026-07-12.md`. Defines the **test contract** for every subagent: the regression baseline that must stay green, the new test to write first (TDD — watch it fail), the assertions that define "done," and the testability workarounds when a fix isn't fully automatable.
**Status:** Awaiting approval. Not initiated.

---

## Why This Exists

The master plan says "tests must pass" but didn't specify *which* tests, *what they assert*, or *how a subagent knows it's done*. Without that, a subagent either:
- Flounders (no clear target), or
- Breaks something silently (no regression baseline to catch it).

This doc gives each subagent a concrete, falsifiable target: **write this test first, watch it fail, implement until green, and don't break these existing tests.**

---

## Test Stack (verified 2026-07-12)

- **JUnit 5** (`org.junit.jupiter:junit-jupiter:5.11.4`) — `@Test`, `org.junit.jupiter.api.Test`.
- **Kotest assertions** (`io.kotest:kotest-assertions-core:5.9.1`) — `shouldBe`, `shouldNotBe`, `shouldThrow`. **Match this style; do not introduce AssertJ.**
- **MockK** (`io.mockk:mockk:1.13.14`) — `mockk`, `every`, `verify`, `coEvery`. For mocking.
- **kotlinx-coroutines-test** — `runTest` (transitive dep; used in 6 existing files / 21 sites).
- **No Robolectric.** Bitmap/Canvas-coupled code cannot be JVM-tested without a refactor or adding Robolectric (not currently a dep).
- **No MockWebServer.** HTTP code cannot be tested without adding the dep.
- **No `androidTest/` dir.** Zero instrumented tests exist.

**Run command:** `./gradlew :app:testStandardDebugUnitTest` (full suite). Targeted: `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`.

**Existing test count:** 57 files under `app/src/test/java/eu/kanade/translation/`.

---

## Established Patterns to Copy

| Pattern | Exemplar | Use for |
|---|---|---|
| Pure helper extracted from heavy class for testability | `ShortHash` (extracted from `TranslationPipeline`) + `ShortHashTest` | Wave 4 copyIfNeeded hash; Wave 3 P2a preprocess extraction |
| Synthetic IntArray pixel testing (no Bitmap) | `AotOutputGuardTest`, `RenderColorEstimatorSamplingTest` | AOT pad path; color dedup |
| Pure int-math geometry testing | `AotBoxGeometryTest`, `BoxGeometryTest` | Wave 5.1M centeredReportCrop fix |
| Concurrent data-structure stress (no pipeline object) | `TranslationPipelineConcurrencyTest` (uses `ConcurrentHashMap.newKeySet` directly) | Wave 1 P0-3 inFlightPageKeys |
| `runTest` + scheduler/policy testing | `TranslationLifecyclePolicyTest`, `TranslationRetryTest` | Wave 1 P0-2, Wave 2B P1-6 |
| Backtick test names with descriptive phrases | All tests (`fun \`null page is schedulable...\``) | **Match this naming style** |

---

## Contract Format (per subagent)

Each subagent gets:
1. **Regression baseline** — existing tests that MUST stay green. If any fail, the subagent broke something.
2. **New test (write first, TDD)** — the test that defines "done." Write it, watch it fail (RED), implement, watch it pass (GREEN).
3. **Assertions** — exact `shouldBe` / `shouldThrow` statements.
4. **Testability workaround** — if the fix isn't fully automatable, what structural/behavioral test IS achievable.
5. **Manual verification** — what a human must check that automation can't.

---

# Wave 1 — Race-Condition P0

## W1-Phase 0: Test Infrastructure

**Subagent: Infra** (blocks P0-2/3/4 verification)

**Regression baseline:** all 57 existing translation tests pass.

**New deliverables (these ARE the task — no production code yet):**

### `MainDispatcherRule.kt`
```kotlin
// app/src/test/java/eu/kanade/translation/MainDispatcherRule.kt
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```
**Test for the rule itself** (`MainDispatcherRuleTest`):
- `fun \`rule pins and resets Dispatchers Main\`()` — apply rule, assert `Dispatchers.Main == testDispatcher`; after finished, assert reset.

### `InFlightPageKeysProbe` (internal accessor)
Add to `TranslationPipeline`:
```kotlin
@Suppress("unused") // test seam
internal fun testInFlightPageKeysSnapshot(): Set<String> =
    Collections.unmodifiableSet(inFlightPageKeys.toSet())
```
**Test:** `TranslationPipelineTest` (new) — assert the snapshot is unmodifiable and reflects adds/removes.

**Done when:** both infra pieces land, rule self-test passes, all 57 existing tests still green.

---

## W1-Phase 1 Subagent A: P0-1 (SIGSEGV guard)

**File:** `RoiPageRecognitionEngine.kt:725-731`

**Regression baseline:**
- `AotBoxGeometryTest`, `AotOutputGuardTest`, `AotPixelOpsTest`, `AotReportBubbleFillTest`, `BoxGeometryTest` — all green (these exercise the recognition engine's geometry/guard helpers).
- `TranslationPipelineConcurrencyTest` — green.

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/recognition/ForceReleaseNativeBuffersGuardTest.kt
class ForceReleaseNativeBuffersGuardTest {
    @Test
    fun `forceReleaseNativeBuffers acquires nativeGuard before clearing buffers`() {
        // Structural: assert the method body references nativeGuard.tryLock()
        // via reflection on the source OR a behavior test with a mocked engine.
    }

    @Test
    fun `forceReleaseNativeBuffers skips when nativeGuard is held`() {
        // Build a minimal RoiPageRecognitionEngine with mockk sub-engines.
        // Hold nativeGuard from another coroutine (launch + await on a Deferred).
        // Call forceReleaseNativeBuffers().
        // Verify: NO sub-engine forceReleaseNativeBuffers was called (verify(not())).
        // Release nativeGuard. Call again. Verify sub-engines WERE called.
    }

    @Test
    fun `forceReleaseNativeBuffers releases nativeGuard in finally even on exception`() {
        // Make a sub-engine throw. Assert nativeGuard is unlocked afterward
        // (next acquire succeeds immediately).
    }
}
```

**Testability workaround:** `RoiPageRecognitionEngine` takes heavy deps (ONNX sessions). Build it with `mockk` sub-engines (`detector`, `roiOcrEngine`, `paddleDet`, `inpainting`, `panelDetector`) — the `forceReleaseNativeBuffers` logic only calls `.forceReleaseNativeBuffers()` on each, which MockK can verify. The `nativeGuard` is `private val Mutex()` — expose via the same `internal` seam pattern as `InFlightPageKeysProbe`, OR test through a public method that exercises it.

**Manual verification (cannot be automated):**
- Emulator: start a chapter translation, mid-inference run `adb shell am send-trim-memory <pid> RUNNING_CRITICAL` repeatedly. Confirm no SIGSEGV in logcat. Compare against pre-fix (reproducible crash).
- Reader-backgrounded: start translation, press Home mid-inference. Confirm no SIGSEGV.

**Done when:** both new tests pass; manual emulator repro shows no crash under memory pressure.

**⚠️ Honest limit:** the *actual SIGSEGV* (native memory freed under in-flight ORT) cannot be reproduced in a JVM unit test — it's a native timing window. The tests above prove the guard logic and skip-when-held behavior. The crash-prevention claim rests on manual emulator verification + the structural argument (mirrors the proven-safe `close()` at 689-712).

---

## W1-Phase 1 Subagent B: P0-3 (clear inFlightPageKeys at top)

**File:** `TranslationPipeline.kt:449-465`

**Regression baseline:**
- `TranslationPipelineConcurrencyTest` — green (tests the underlying `ConcurrentHashMap.newKeySet`).
- All 57 translation tests green.

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/scheduling/CloseEnginesClearsKeysTest.kt
class CloseEnginesClearsKeysTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `closeEngines clears inFlightPageKeys even when permit is held`() {
        // Construct minimal pipeline (or extract closeEngines' clear logic
        // into a testable helper mirroring ShortHash pattern).
        // Pre-populate inFlightPageKeys via the test seam with 3 page keys.
        // Hold the permit: translatorPermit.acquire() from a background thread.
        // Call closeEngines().
        // Assert: testInFlightPageKeysSnapshot() shouldBe emptySet()
    }

    @Test
    fun `closeEngines clears inFlightPageKeys when permit is acquirable`() {
        // Same pre-populate. Do NOT hold the permit. Call closeEngines().
        // Assert: snapshot empty.
    }
}
```

**Testability workaround:** `TranslationPipeline` is a heavy singleton. Two options:
1. **Extract the clear logic** into a tiny `internal fun drainInFlightKeysOnClose()` helper — test the helper directly (ShortHash pattern).
2. **Mock the heavy deps** and construct the pipeline via a test-only constructor.

Recommend option 1 — matches the proven ShortHash pattern and keeps the test fast.

**Done when:** both tests pass; `TranslationPipelineConcurrencyTest` still green.

---

## W1-Phase 1 Subagent C: P0-4 (onPageStuck try/catch)

**File:** `TranslationPipeline.kt:286`

**Regression baseline:** all 57 translation tests green.

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/scheduling/PermitWatchdogCallbackGuardTest.kt
class PermitWatchdogCallbackGuardTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `onPageStuck throwing does not skip onForceRelease or permit release`() {
        // Set up the watchdog with:
        //   onTimeout = {}
        //   onPageStuck = { throw RuntimeException("boom") }
        //   onForceRelease = mockk(relaxed = true)
        // Trigger the watchdog (advance test dispatcher past timeoutMs).
        // Assert: onForceRelease was called (verify { onForceRelease() }).
        // Assert: permit is released (next acquire() returns true immediately).
    }

    @Test
    fun `onTimeout onPageStuck onForceRelease are all independently guarded`() {
        // Structural: read TranslationPipeline.kt source around line 275-296,
        // assert all three callbacks are wrapped in try/catch.
        // (Lightweight: assert via reflection that no invoke() is bare.)
    }
}
```

**Testability workaround:** the watchdog is a `launch` inside `permitWatchdogScope`. Either:
1. Extract the watchdog callback chain into a testable `internal fun runWatchdogFallbacks(onTimeout, onPageStuck, onForceRelease, releaseOnce)` helper.
2. Use `mainDispatcherRule.testDispatcher` to advance time and trigger the watchdog.

Recommend option 1 for the cleanest test.

**Done when:** both tests pass; `onForceRelease` is verified called even when `onPageStuck` throws.

---

## W1-Phase 2: P0-2 (synchronous CANCELLED flip)

**File:** `TranslationScheduler.kt:491-499`

**Regression baseline:**
- `TranslationLifecyclePolicyTest` — green (pins stage status semantics).
- `TranslationRetryTest` — green (6 sites, coroutine scheduler patterns).
- All 57 translation tests green.

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/scheduling/CancelPageTranslationSyncFlipTest.kt
class CancelPageTranslationSyncFlipTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `cancelPageTranslation flips page to CANCELLED within the same tick`() = runTest {
        // Set up a store with a page in RUNNING status.
        // Call cancelPageTranslation(chapterId, pageKey).
        // Advance dispatcher minimally (runCurrent()).
        // Assert: store page ocrStatus == StageStatus.CANCELLED.
        // (Pre-fix: this would require waiting for the worker finally,
        //  up to 90s — the test documents the bug.)
    }

    @Test
    fun `cancel on already-finished page is a no-op`() = runTest {
        // Page with hasRenderedResult=true. Call cancel.
        // Assert: status UNCHANGED (not flipped to CANCELLED).
        // (Mirrors markPageCancelled guard at TranslationScheduler.kt:446.)
    }

    @Test
    fun `re-translate after cancel overwrites CANCELLED with RUNNING`() = runTest {
        // Cancel a page. Then simulate translateSinglePageOnnx (resets to RUNNING).
        // Assert: status == RUNNING, not stuck on CANCELLED.
    }
}
```

**Testability workaround:** `TranslationScheduler` needs a `storeResolver` and `scope`. Inject test doubles:
- `storeResolver.resolve(chapterId)` returns a fake `PageTranslationStore` (in-memory map).
- `scope = TestScope(testDispatcher)`.

This pattern is established — `TranslationRetryTest` already builds scheduler-like fixtures.

**Done when:** all three tests pass; re-translate-after-cancel verified safe.

---

# Wave 2A — Inpainting Safe Wins

## W2A Subagent: P1 (spinning-off) + P1.5 (pool arrays) + P3 (ORT upgrade)

### P1 (spinning-off)

**File:** `OnnxRuntimeProvider.kt`, `AOTInpainting.kt:111`

**Regression baseline:** all 57 translation tests green (the AOT session is exercised by inpainting tests transitively; the 4 NNAPI models are not unit-tested but their session creation is logged).

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/runtime/onnx/AotSessionSpinningConfigTest.kt
class AotSessionSpinningConfigTest {
    @Test
    fun `AOT session options disable intra-op spinning`() {
        // Call OnnxRuntimeProvider.createSessionOptions(useAccelerator=false, useXnnpack=true)
        //   with the AOT-specific configure lambda.
        // Assert: the returned SessionOptions contains the config entry
        //   "session.intra_op.allow_spinning" == "0".
        // (OrtSession.SessionOptions exposes getConfigEntry — use it.)
    }

    @Test
    fun `NNAPI model sessions do NOT disable spinning`() {
        // Call createSessionOptions(useAccelerator=true) (detector path).
        // Assert: allow_spinning is NOT set (or is default).
        // (Scoped to AOT only — no global disable.)
    }

    @Test
    fun `spinning config is added BEFORE addXnnpack`() {
        // Structural: assert via source inspection that the configure lambda
        // runs before the EP branch (or that allowSpinning param is applied first).
    }
}
```

**Testability workaround:** `OrtSession.SessionOptions` is a real ONNX object, but it can be constructed in JVM tests (no native load until session creation). `getConfigEntry` is a real method. This is directly testable.

**Manual verification:** on-device, run ≥20 AOT inferences before/after, average the `[inpaint]` logcat timings. Confirm no regression (neutral acceptable per design).

**Done when:** all three tests pass; manual timing shows no regression.

### P1.5 (pool feeding IntArrays)

**File:** `AOTInpainting.kt:514, 516, 63-75`

**Regression baseline:** `AotOutputGuardTest`, `AotPixelOpsTest` — green (these test pixel math the feeding loop feeds).

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/inpainting/AotFeedingBufferPoolingTest.kt
class AotFeedingBufferPoolingTest {
    @Test
    fun `feeding reuses the same IntArray across calls`() {
        // Extract the feeding-loop pixel-read into a pure helper:
        //   internal fun readPixelsToBuffer(source: Bitmap, target: IntArray, ...)
        // (or a class AotFeedingBuffers with pooled img/mask arrays).
        // Call twice. Assert: same IntArray identity (===) both calls.
    }

    @Test
    fun `pooled array fully overwritten each call - no stale pixels`() {
        // Pre-fill the pooled array with sentinel (0xDEADBEF).
        // Run feeding with a known source pattern.
        // Assert: NO sentinel remains in the active region [0, width*height).
    }

    @Test
    fun `pooled array sized for max inference dim`() {
        // Assert: array.size >= 768 * 768 (the current MAX_TOTAL_PIXELS).
        // (Sub-512 and >768 paths must both fit.)
    }
}
```

**Testability workaround:** the feeding loop uses `Bitmap.getPixels` (Android). Extract the array management into a pure `AotFeedingBuffers` class that takes a pre-filled `IntArray` — test the pooling/reuse, not the Android call. The Android `getPixels` stays in `inpaint()`.

**Done when:** all three tests pass; output tensor identical to pre-fix (manual: run inpaint on a sample, diff output).

### P3 (ORT 1.21→1.23)

**File:** `gradle/libs.versions.toml:17`

**Regression baseline:** **ALL tests pass after the bump** — this is a dependency change, every test is the regression check.

**New test:** none (this is a build config change).

**Verification:**
1. `./gradlew :app:testStandardDebugUnitTest` — all 57+ tests pass.
2. `./gradlew :app:assembleDebug` — build succeeds.
3. **Manual on-device smoke:** load each model (detector, OCR, inpaint, panel, segmenter, paddle). Confirm no load-time crash. This is the gate — ORT upgrades can break native loading.

**Done when:** all tests pass, build succeeds, on-device smoke clean.

---

# Wave 2B — Race-Condition Display Fixes

## W2B Subagent D: P1-5 (index resolver)

**Files:** `TranslationProgressSnapshot.kt:146`, `TranslationBatchProgressTracker.kt:302`, `ReaderViewModel.kt:286-291`

**Regression baseline:**
- `TranslationProgressSnapshotTest` — green (existing snapshot tests).
- `TranslationBatchProgressTrackerTest` — green.
- `SplitTallPageKeyTest` — green (tests `009__002.jpg`-style keys — directly relevant).

**New test (RED first):** extend `TranslationProgressSnapshotTest`:
```kotlin
@Test
fun `index resolver returns real page index for split-tall key`() {
    val resolver = mapOf("009__002.jpg" to 8)  // 0-based → displays as 9
    val snapshot = TranslationProgressSnapshot.compute(
        pages = listOf(page("009__002.jpg", StageStatus.RUNNING)),
        indexResolver = resolver
    )
    snapshot.activePage shouldBe 9  // 1-based display
}

@Test
fun `index resolver returns real page index for dashed key`() {
    val resolver = mapOf("page-09.png" to 8)
    val snapshot = TranslationProgressSnapshot.compute(
        pages = listOf(page("page-09.png", StageStatus.RUNNING)),
        indexResolver = resolver
    )
    snapshot.activePage shouldBe 9
}

@Test
fun `index resolver falls back to insertion order when key absent`() {
    val resolver = emptyMap<String, Int>()
    val snapshot = TranslationProgressSnapshot.compute(
        pages = listOf(
            page("unknown.jpg", StageStatus.RUNNING),
        ),
        indexResolver = resolver
    )
    snapshot.activePage shouldBe 1  // insertionOrder + 1
}

@Test
fun `index resolver null falls back to insertion order`() {
    // Backward-compat: resolver param is nullable.
    val snapshot = TranslationProgressSnapshot.compute(
        pages = listOf(page("009.jpg", StageStatus.RUNNING)),
        indexResolver = null
    )
    snapshot.activePage shouldBe 1  // not 9 — graceful degradation
}
```

**Done when:** all four new tests pass; existing snapshot/tracker tests green; **all 3 regex copies deleted** (grep for `(\d+)(?!.*\d)` and `(\d+)$` returns zero hits outside test fixtures).

---

## W2B Subagent E: P1-6 (RUNNING vs QUEUED)

**File:** `TranslationProgressSnapshot.kt:125`

**Regression baseline:** `TranslationProgressSnapshotTest` — green.

**New test (RED first):**
```kotlin
@Test
fun `activePage picks permit-holder over queued pages`() {
    // Two pages RUNNING (permit-holder + prefetched). Snapshot should pick
    // the permit-holder only.
    val snapshot = TranslationProgressSnapshot.compute(
        pages = listOf(
            pageRunning("001.jpg", isPermitHolder = false),  // queued
            pageRunning("002.jpg", isPermitHolder = true),   // translating now
        ),
        permitHolderKey = "002.jpg"
    )
    snapshot.activePageKey shouldBe "002.jpg"
}

@Test
fun `queued pages display as QUEUED not RUNNING`() {
    val snapshot = TranslationProgressSnapshot.compute(
        pages = listOf(pageRunning("001.jpg", isPermitHolder = false)),
        permitHolderKey = null  // none currently translating
    )
    snapshot.pages.first().lifecycle shouldBe PageLifecycle.Queued
}
```

**Testability workaround:** the scheduler must expose permit-owner identity. Add an `internal val permitHolderKey: StateFlow<String?>` to the scheduler (or a snapshot accessor). Same `internal` seam pattern.

**Done when:** both tests pass; manual UI check confirms "translating now" vs "queued" labels.

---

## W2B Subagent F: P2-7 (enginesClosed observer) + P3-9 (@Volatile)

**Files:** `ReaderViewModel.kt:172`, `TranslationPipeline.kt:467-468`

**Regression baseline:** all 57 translation tests green.

**New test (RED first):**
```kotlin
// P2-7
@Test
fun `engine signature change sets enginesClosed without cancelling batch`() = runTest {
    // Observe the signature flow. Emit a new signature.
    // Assert: pipeline.enginesClosed shouldBe true
    // Assert: batch queue is NOT evicted (internalClearQueue NOT called).
    // (The regressive cancelling-observer alternative would fail this.)
}

// P3-9
@Test
fun `consecutiveOomCount is annotated Volatile`() {
    // Structural reflection: assert
    // TranslationPipeline::class.getDeclaredField("consecutiveOomCount")
    //   .isAnnotationPresent(Volatile::class.java)
}
@Test
fun `currentChapterTranslation is annotated Volatile`() {
    // Same for currentChapterTranslation.
}
```

**Done when:** all three tests pass; manual logcat confirms engine rebuild on config change without batch eviction.

---

# Wave 3 — Pipeline Safe Wins

## W3 Subagent G: P1a (RenderColorEstimator dedup)

**File:** `RenderColorEstimator.kt:127-132, 152, 170`

**Regression baseline:**
- `RenderColorEstimatorTest` — green (colorPolicy tests).
- `RenderColorEstimatorSamplingTest` — green (decideTextFill + sampleBackgroundLuma with synthetic IntArrays).

**New test (RED first):** the dedup is a pure refactor, so the test is a **bit-identical snapshot**:
```kotlin
// app/src/test/java/eu/kanade/translation/rendering/RenderColorEstimatorDedupTest.kt
class RenderColorEstimatorDedupTest {
    // Matrix of synthetic inputs (capture BEFORE the refactor):
    private val inputs = listOf(
        syntheticPixels(denseInk = true),
        syntheticPixels(denseInk = false),
        syntheticPixels(uniform = true),
        syntheticPixels(gradient = true),
        // ... cover the same matrix as RenderColorEstimatorSamplingTest
    )

    @Test
    fun `decideTextFill output unchanged after dedup`() {
        // Capture outputs before refactor (golden values).
        // After refactor: assert each input produces the SAME Long.
        inputs.forEach { pixels ->
            val before = GOLDEN_DECIDE_TEXT_FILL[inputs.indexOf(pixels)]
            val after = RenderColorEstimator.decideTextFill(pixels, 60, 60, intArrayOf(0,0,60,60))
            after shouldBe before
        }
    }

    @Test
    fun `sampleBackgroundLuma output unchanged after dedup`() {
        // Same pattern with GOLDEN_SAMPLE_LUMA.
    }
}
```

**Golden-value capture:** BEFORE the refactor, run the existing tests with logging, record the `Long`/`Float` outputs for each synthetic input. Hard-code as `GOLDEN_*` constants. The refactor must reproduce them exactly.

**Done when:** both tests pass (bit-identical); existing estimator tests green.

---

## W3 Subagent H: P1b (JPEG off permit)

**File:** `TranslationPipeline.kt:2463, 2597`

**Regression baseline:** `TranslationPipelineConcurrencyTest` — green.

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/InpaintEncodeOrderingTest.kt
class InpaintEncodeOrderingTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `cleaned bitmap encode happens after send and before tryRender`() = runTest {
        // Mock the encode step (Bitmap.compress is Android — wrap it).
        // Record call order: send(), encode(), tryRender().
        // Assert: order is [send, encode, tryRender], NOT [encode, send, tryRender].
    }

    @Test
    fun `cleanedImageName is non-null when tryRender reads it`() = runTest {
        // Assert: at the moment tryRender is invoked, pageTranslation.cleanedImageName != null.
    }

    @Test
    fun `encode runs exactly once per page`() = runTest {
        // Count encode calls across a single-page translation. Assert == 1.
    }
}
```

**Testability workaround:** `Bitmap.compress` is Android. Wrap the encode call in an `internal fun encodeCleanedBitmap(bitmap, os)` helper, mock it in tests to record invocation order. The ordering logic is what matters; the actual JPEG bytes don't.

**Manual verification:** logcat `[inpaint_encode]` timing — confirm the timestamp moves outside the permit window.

**Done when:** all three tests pass; manual logcat confirms encode moved.

---

## W3 Subagent I: P2a (MangaOcr FloatArray pool)

**File:** `MangaOcrEngine.kt:329-387, 364-379`

**Regression baseline:** `OcrTextFilterTest`, `PaddleCtcDecoderTest`, `DbPostProcessTest` — green (OCR-adjacent). No existing MangaOcrEngine test (it's private + Bitmap-coupled).

**Testability workaround (REQUIRED FIRST):** extract the pixel-write loop from `preprocess`:
```kotlin
// Before (private, Bitmap-coupled):
private fun preprocess(cropBitmap: Bitmap): FloatArray {
    val result = FloatArray(1*3*224*224)  // ← the churn
    // ... Bitmap ops, write to result
    return result
}

// After (pure helper + thin Bitmap adapter):
internal fun preprocessPixelsToBuffer(
    pixels: IntArray,  // from Bitmap.getPixels in the adapter
    width: Int,
    height: Int,
    target: FloatBuffer  // pooled, single-pass write
) {
    // Pure pixel math. No Bitmap. CHW layout preserved.
}

private fun preprocess(cropBitmap: Bitmap): FloatBuffer {
    val pixels = IntArray(cropBitmap.width * cropBitmap.height)
    cropBitmap.getPixels(pixels, 0, ...)
    val target = inputPixelPool.acquire()  // pooled
    preprocessPixelsToBuffer(pixels, cropBitmap.width, cropBitmap.height, target)
    return target
}
```

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/ocr/MangaOcrPreprocessPureTest.kt
class MangaOcrPreprocessPureTest {
    @Test
    fun `preprocessPixelsToBuffer produces identical output to old 2-pass`() {
        // Golden values: capture old FloatArray(1*3*224*224) output for a
        // synthetic IntArray input. After refactor: assert bit-identical.
        val pixels = syntheticMangaPixels()  // IntArray
        val oldOutput = GOLDEN_PREPROCESS_OUTPUT
        val newOutput = FloatArray(1*3*224*224)
        val buffer = FloatBuffer.wrap(newOutput)
        MangaOcrEngine.preprocessPixelsToBuffer(pixels, 224, 224, buffer)
        newOutput.toList() shouldBe oldOutput.toList()
    }

    @Test
    fun `CHW layout preserved`() {
        // Assert channel-first ordering: index 0 is R of pixel (0,0),
        // index (224*224) is G of pixel (0,0), etc.
    }

    @Test
    fun `no intermediate FloatArray allocated`() {
        // Structural: assert preprocessPixelsToBuffer writes directly to target,
        // no `FloatArray(...)` allocation in its body.
        // (Lightweight source assertion.)
    }
}
```

**Done when:** all three tests pass; output bit-identical; CHW preserved.

---

## W3 Subagent J: P2b (Google batch) — OPTIONAL/DEFER

**File:** `GoogleTranslator.kt:14, 20, 26-30, 33, 68`

**Blocker:** needs MockWebServer (new dep) + URL injection refactor.

**Regression baseline:** no existing GoogleTranslator test (it's hardcoded-URL + private).

**If pursued, test contract:**
```kotlin
// app/src/test/java/eu/kanade/translation/translator/GoogleTranslatorBatchTest.kt
class GoogleTranslatorBatchTest {
    @Test
    fun `multi-block page makes single request`() {
        // MockWebServer returns canned multi-line response.
        // translator.translatePage(blocks = listOf("a","b","c"))
        // Assert: mockWebServer.requestCount shouldBe 1
    }

    @Test
    fun `response split correctly by delimiter`() {
        // Canned response "a||b||c". Assert blocks == listOf("a","b","c").
    }

    @Test
    fun `text-heavy page chunks when URL length exceeds cap`() {
        // Blocks summing >16KB. Assert: requestCount > 1 (chunked).
    }
}
```

**Recommendation: DEFER** unless Google-translator users are a meaningful segment (Google isn't default — MLKit is). The refactor + new dep overhead may exceed benefit.

---

# Wave 4 — copyIfNeeded Deployment Fix

## W4 Subagent: copyIfNeeded version-stamp

**File:** `OnnxModelStore.kt:187-227, 253-270`

**Regression baseline:** all 57 translation tests green. No existing OnnxModelStore test (takes Android Context).

**Testability workaround (REQUIRED FIRST):** extract `copyIfNeeded` logic into a pure helper (ShortHash pattern):
```kotlin
// New: OnnxModelDeployer (pure, no Context)
internal class OnnxModelDeployer(
    private val baseDir: File,
    private val assetReader: (String) -> InputStream,  // inject assets.open
) {
    internal fun copyIfNeeded(name: String, assetPath: String, versionStamp: String): File
}

// OnnxModelStore becomes a thin adapter:
class OnnxModelStore(context: Context) {
    private val deployer = OnnxModelDeployer(
        baseDir = context.noBackupFilesDir,
        assetReader = { context.assets.open(it) }
    )
}
```

**New test (RED first):**
```kotlin
// app/src/test/java/eu/kanade/translation/runtime/onnx/OnnxModelDeployerTest.kt
class OnnxModelDeployerTest {
    private lateinit var tempDir: File

    @BeforeEach fun setup() { tempDir = createTempDir() }
    @AfterEach fun teardown() { tempDir.deleteRecursively() }

    @Test
    fun `re-copies when version stamp mismatches`() {
        // Pre-populate dest with old model + old .version stamp.
        // Call copyIfNeeded with new stamp. Assert: dest bytes == new asset bytes.
    }

    @Test
    fun `keeps cached model when stamp matches`() {
        // Pre-populate dest + matching .version stamp.
        // Call copyIfNeeded. Assert: dest UNCHANGED (asset NOT read).
    }

    @Test
    fun `missing stamp (legacy cache) triggers re-copy`() {
        // Pre-populate dest with NO .version file. Call copyIfNeeded.
        // Assert: re-copied (backward-compat: old users get the new model once).
    }

    @Test
    fun `valid onnx header still required after stamp check`() {
        // Looks-like-valid-onnx check preserved as defense-in-depth.
    }

    @Test
    fun `stamp contains version code and content hash`() {
        // Read the .version file. Assert format: "<versionCode>:<hash>".
    }
}
```

**Version stamp format:** `<BuildConfig.VERSION_CODE>:<ShortHash.hash(first 64KB of asset)>`. **Reuse existing `ShortHash`** (already extracted, tested) — don't reinvent.

**Manual verification:** install old build, change a model asset, install new build over it. Logcat shows "Copying model from assets" on UPDATE. Install again without asset change — no re-copy.

**Done when:** all five tests pass; manual update test confirms propagation.

---

# Wave 5 — AOT NPU

## W5.1 Subagent K: Model conversion (Python offline)

**Gate (not a JVM test):** Tier 2 numerics test in the conversion script:
```python
# verify_conversion.py
for sample in corpus:
    dyn_out = run_dynamic_model(sample)
    static_out = run_static_512_model(sample)
    diff = max_abs_diff(crop_back(dyn_out), crop_back(static_out))
    assert diff < 1e-3, f"Divergence on {sample}: {diff}"
# Also assert: onnx.checker.check_model(static_512_model) passes
# Also assert: input shape is exactly [1,3,512,512] and mask [1,1,512,512]
```

**Regression:** the converted model must load in the existing `AOTInpainting` test path (once W5.1M lands).

**Done when:** script exits 0; model committed to `assets/models/inpainting/aot-512.onnx`.

## W5.1 Subagent L: Corpus + harness

**Deliverable:** 20 JPEGs + `manifest.json` + `baseline_report.json` + `AotCorpusHarnessTest.kt`.

**New test:**
```kotlin
// app/src/test/java/eu/kanade/translation/inpainting/AotCorpusHarnessTest.kt
class AotCorpusHarnessTest {
    @Test
    fun `corpus manifest lists 20 pages with categories`() {
        val manifest = loadManifest()
        manifest.pages.size shouldBe 20
        // Assert each category represented per design §4 composition.
    }

    @Test
    fun `baseline report exists with guard verdicts per page`() {
        val baseline = loadBaselineReport()
        baseline.size shouldBe 20
        baseline.forEach { (_, verdict) ->
            verdict in setOf("ACCEPT", "REJECT")
        }
    }

    @Test
    fun `guard is decoupled - inspects synthetic IntArray without ONNX`() {
        // Confirm AotOutputGuard.inspect works standalone (already true —
        // this test documents the harness's testability foundation).
    }
}
```

**Baseline capture:** the harness runs the CURRENT dynamic model on the corpus, records `AotOutputGuard.inspect` stats + verdicts. This is the **golden baseline** W5.2 compares against.

**Done when:** corpus committed; baseline_report.json committed; harness tests pass.

## W5.1 Subagent M: Geometry fix + pad path

**Files:** `AotBoxGeometry.kt:26-38`, `AOTInpainting.kt:417-656`

**Regression baseline:**
- `AotBoxGeometryTest` — green (3 existing cases: empty boxes, centered-in-bounds, edge clamp).
- `AotOutputGuardTest`, `AotPixelOpsTest` — green.

**New tests (RED first):**

Extend `AotBoxGeometryTest`:
```kotlin
@Test
fun `centeredReportCrop on sub-512 page returns valid in-bounds crop`() {
    // 400x600 page, contextSize=512. side = min(512, 400) = 400.
    val crop = centeredReportCrop(boxes = listOf(intArrayOf(180,280,220,320)),
                                  width = 400, height = 600, contextSize = 512)
    // crop should be [x1, y1, x1+side, y1+side] in-bounds, side <= 400.
    // NO IllegalArgumentException (the bug).
    crop shouldNotBe null
    val (x1, y1, x2, y2) = listOf(crop!![0], crop!![1], crop!![2], crop!![3])
    (x2 - x1) shouldBe (y2 - y1)  // square
    x1 shouldBe >= 0
    y1 shouldBe >= 0
    x2 shouldBe <= 400
    y2 shouldBe <= 600
}
```

New `AotPadPathTest`:
```kotlin
// app/src/test/java/eu/kanade/translation/inpainting/AotPadPathTest.kt
class AotPadPathTest {
    @Test
    fun `pad to 512 fills border with dominant background color`() {
        // Source 400x400 with dominant bg = white (0xFFFFFFFF).
        // padTo512(source, bgColor). Assert: output is 512x512.
        // Border pixels == white. Interior (0,0)-(400,400) == source.
    }

    @Test
    fun `crop back from 512 recovers original 400x400 exactly`() {
        // pad to 512, then cropBack(output, 400, 400).
        // Assert: result == source (identity round-trip for the geometry).
    }

    @Test
    fun `pad handles asymmetric dimensions (400 wide, 600 tall scaled to 512)` {
        // Crop >512 on one axis: downscale path. Assert output is 512x512.
    }

    @Test
    fun `pad uses background color not zero`() {
        // Source with bg = mid-gray. Assert border == mid-gray, NOT 0x00000000.
        // (Validates the bg-color decision over zero-padding.)
    }
}
```

**Testability workaround:** the pad/crop logic uses Android `Bitmap`/`Canvas`. Extract pure helpers:
```kotlin
internal fun computePadGeom(srcW: Int, srcH: Int, target: Int = 512): PadGeom
internal fun dominantBackgroundColor(pixels: IntArray): Int  // pure
```
Test the geometry math + color math purely; the Bitmap draw stays in `inpaint()`.

**Done when:** all new tests pass; `AotBoxGeometryTest` extended case passes (no throw on sub-512); existing geometry/guard/pixel tests green.

## W5.2 Integration + Tier 3 gate

**New test (the gate):**
```kotlin
// app/src/test/java/eu/kanade/translation/inpainting/AotStaticVsDynamicCorpusTest.kt
class AotStaticVsDynamicCorpusTest {
    @Test
    fun `static-512 model guard verdicts match dynamic baseline on corpus`() {
        val baseline = loadBaselineReport()  // from W5.1L
        val staticResults = runStaticModelOnCorpus()  // needs W5.1K model + W5.1M path
        // Assert: zero NEW rejections.
        // ACCEPTED in baseline → ACCEPTED in static.
        // REJECTED in baseline → REJECTED in static (or improved to ACCEPT — OK).
        val newRejections = staticResults.filter { (page, v) ->
            baseline[page] == "ACCEPT" && v == "REJECT"
        }
        newRejections shouldBe emptyMap()
    }
}
```

**🔴 HARD GATE.** If `newRejections` is non-empty, W5.2 FAILS. Do not proceed to W5.3. Loop to K (model) or M (pad path).

## W5.3 Subagent: NNAPI EP + fallback

**New tests:**

`NnapiCapabilityGateTest`:
```kotlin
@Test
fun `gate returns NNAPI when coverage high and partitions low`() {
    NnapiCapabilityGate.decide(coverage = 0.95, partitions = 2) shouldBe EpChoice.NNAPI
}
@Test
fun `gate returns XNNPACK when coverage low`() {
    NnapiCapabilityGate.decide(coverage = 0.50, partitions = 2) shouldBe EpChoice.XNNPACK
}
@Test
fun `gate returns XNNPACK when partitions high`() {
    // The 23-partition problem.
    NnapiCapabilityGate.decide(coverage = 0.94, partitions = 23) shouldBe EpChoice.XNNPACK
}
@Test
fun `gate thresholds documented in constants`() {
    // Structural: thresholds are named consts, not magic numbers.
}
```

`DualSessionFallbackTest`:
```kotlin
@Test
fun `NNAPI rejection triggers XNNPACK re-run`() {
    // Mock NNAPI session to produce guard-rejected output.
    // Assert: XNNPACK session.run was called.
}
@Test
fun `both sessions rejected falls back to push-pull`() {
    // Both produce rejected output. Assert: FAST fallback path invoked.
}
@Test
fun `aggregate health monitor disables NNAPI after threshold`() {
    // Feed 20% rejections. Assert: NNAPI auto-disabled for session.
}
```

**Done when:** all tests pass; manual on-device validation (Wave 6).

---

# Summary: Tests Added Per Wave

| Wave | New test files | New test cases | Regression tests watched |
|---|---|---|---|
| 0 | 0 | 0 | n/a |
| 1 | 6 (MainDispatcherRule, ForceReleaseNativeBuffersGuard, CloseEnginesClearsKeys, PermitWatchdogCallbackGuard, InFlightPageKeysProbe, CancelPageTranslationSyncFlip) | ~15 | All 57 + new |
| 2A | 3 (AotSessionSpinningConfig, AotFeedingBufferPooling, none for ORT) | ~8 | All 57 + new |
| 2B | 0 (extend existing) + 1 new (P2-7 observer) | ~7 | Snapshot/Tracker/SplitTall/Retry + new |
| 3 | 4 (RenderColorEstimatorDedup, InpaintEncodeOrdering, MangaOcrPreprocessPure, optional GoogleBatch) | ~12 | Estimator/Sampling/Concurrency + new |
| 4 | 1 (OnnxModelDeployer) | 5 | All 57 + new |
| 5 | 5 (Corpus harness, AotPadPath, StaticVsDynamic gate, NnapiCapabilityGate, DualSessionFallback) | ~20 | Geometry/Guard/Pixel + new |

**Total new: ~19 test files, ~67 test cases**, each tied to a specific subagent deliverable.

---

# Anti-Breakage Protocol (applies to every subagent)

1. **Before writing any production code:** run `./gradlew :app:testStandardDebugUnitTest`. Capture the green baseline. If it's already red, STOP and report — the subagent's branch has a pre-existing failure.
2. **Write the new test first.** Run it. Confirm it FAILS for the right reason (RED). If it passes immediately, the test is wrong or the bug is already fixed.
3. **Implement the fix.** Run the new test. Confirm GREEN.
4. **Run the full suite.** All previously-green tests must stay green. Any regression = the subagent broke something.
5. **Commit only when:** new test green + full suite green + CI green.
6. **If a regression is unavoidable** (e.g., a test encoded the buggy behavior): flag it explicitly in the PR, explain why the test must change, and update it in the same commit.

---

# What's NOT Automated (honest limits)

| Fix | Why not automated | What covers it instead |
|---|---|---|
| P0-1 actual SIGSEGV | Native timing window, uncatchable from Kotlin | Structural test (guard present) + manual emulator repro |
| P1 (spinning-off) actual speed gain | Within measurement noise per-run | Structural test + manual ≥20-inference average |
| P1b actual timing | Timing is runtime/hardware | Ordering test + manual logcat |
| P3 on-device model load | Needs a device | Manual smoke on each model |
| W4 manual update propagation | Needs install-over-install | Manual two-install test |
| W5.3 NNAPI driver garbage | Needs real NPU + bad driver | Layer 4 monitor + Wave 6 on-device |
| Wave 6 all | Needs physical devices | Manual per-device checklist |

These are flagged honestly. No test is pretended to cover what it can't.

---

**No code changes made. Test contracts only. Awaiting approval.**
