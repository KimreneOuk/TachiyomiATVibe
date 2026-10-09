# Dynamic Page Batching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Batch an entire page's same-width-bucket PaddleOCR leaves into one ORT call (memory-bounded dynamic ceiling) instead of fixed chunks of 4, cutting the ~2 s/page OCR stage.

**Architecture:** A new pure policy computes a per-width-bucket batch ceiling from dictionary size and byte budgets. The planner gains a per-bucket chunk function; the executor's hard cap rises to 32 with halving failure ladders for batches > 8; a new `DYNAMIC` preference tier routes through the existing activation/governor gates with "B8 tier = uncapped dynamic" semantics. Spec: `docs/superpowers/specs/2026-10-09-dynamic-page-batching-design.md`.

**Tech Stack:** Kotlin, JUnit 5 + kotest matchers, ONNX Runtime (existing seams unchanged).

**Gradle rule (every test/build command):**
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME='C:\Users\User\AppData\Local\Android\Sdk'
./gradlew --max-workers=2 "-Dkotlin.daemon.jvmargs=-Xmx3g" <tasks>
```
Never `./gradlew --stop`, never parallel builds.

**Commit rule:** the working tree carries unrelated uncommitted changes — always `git add` only the exact files a task touched.

---

### Task 1: `PaddleOcrDynamicPageBatchPolicy` (pure ceiling math)

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDynamicPageBatchPolicy.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDynamicPageBatchPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

The production dictionary is **18,708 lines → 18,710 classes** (`wc -l app/src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt`), so per-sample output is 80×18,710×4 = 5,987,200 B at 640 and 200×18,710×4 = 14,968,000 B at 1600. All expectations below are derived from those numbers.

```kotlin
package eu.kanade.translation.engines.vision.ocr.paddle.batch

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class PaddleOcrDynamicPageBatchPolicyTest {

    @Test
    fun `output budget dominates both bucket ceilings for the production dictionary`() {
        val policy = PaddleOcrDynamicPageBatchPolicy(
            dictionarySize = 18_708,
            outputBudgetBytes = 96L * 1024 * 1024,
        )
        // 100_663_296 / 5_987_200 = 16.8 -> 16; / 14_968_000 = 6.7 -> 6
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_640) shouldBe 16
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_1600) shouldBe 6
    }

    @Test
    fun `output budget is tiered by total device RAM`() {
        PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(totalRamBytes = 8L * 1024 * 1024 * 1024) shouldBe
            96L * 1024 * 1024
        PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(totalRamBytes = 6L * 1024 * 1024 * 1024) shouldBe
            96L * 1024 * 1024
        PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(totalRamBytes = 4L * 1024 * 1024 * 1024) shouldBe
            48L * 1024 * 1024
        PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(totalRamBytes = 3L * 1024 * 1024 * 1024) shouldBe
            32L * 1024 * 1024
    }

    @Test
    fun `mid RAM tier yields B8 at 640 and B3 at 1600 for the production dictionary`() {
        val policy = PaddleOcrDynamicPageBatchPolicy(
            dictionarySize = 18_708,
            outputBudgetBytes = PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(4L shl 30),
        )
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_640) shouldBe 8
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_1600) shouldBe 3
    }

    @Test
    fun `tiny dictionaries clamp to the hard max instead of unbounded batches`() {
        val policy = PaddleOcrDynamicPageBatchPolicy(
            dictionarySize = 3,
            outputBudgetBytes = 96L * 1024 * 1024,
        )
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_640) shouldBe PaddleOcrDynamicPageBatchPolicy.HARD_MAX_BATCH
    }

    @Test
    fun `a budget smaller than one sample floors the ceiling at B1`() {
        val policy = PaddleOcrDynamicPageBatchPolicy(
            dictionarySize = 3,
            outputBudgetBytes = 1_599L, // one 640 sample needs 80*5*4 = 1600B
        )
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_640) shouldBe 1
    }

    @Test
    fun `input budget can bind before the output budget on wide buckets`() {
        val policy = PaddleOcrDynamicPageBatchPolicy(
            dictionarySize = 3,
            inputBudgetBytes = 3L * 1024 * 1024, // 3 MiB; 1600 sample = 921_600B -> 3
            outputBudgetBytes = 96L * 1024 * 1024,
        )
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_1600) shouldBe 3
    }

    @Test
    fun `shipped dictionary asset keeps the whole-page ceiling at B16-640 and B6-1600`() {
        val dictionaryFile = File("src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(dictionaryFile.exists(), "asset not present in this checkout")
        val dictionarySize = dictionaryFile.useLines { it.count() }

        val policy = PaddleOcrDynamicPageBatchPolicy(
            dictionarySize = dictionarySize,
            outputBudgetBytes = PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(6L shl 30),
        )
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_640) shouldBe 16
        policy.ceilingFor(PaddleOcrWidthBucket.WIDTH_1600) shouldBe 6
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --max-workers=2 "-Dkotlin.daemon.jvmargs=-Xmx3g" :app:testDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrDynamicPageBatchPolicyTest"`
Expected: FAIL (unresolved reference `PaddleOcrDynamicPageBatchPolicy`)

- [ ] **Step 3: Write the implementation**

```kotlin
package eu.kanade.translation.engines.vision.ocr.paddle.batch

/**
 * Memory-derived per-bucket ceiling for page-dynamic recognition batching.
 *
 * One ORT call carries up to `ceilingFor(bucket)` same-page crops: the input
 * NCHW float32 lease plus ORT's natively-allocated `B x T x C` output must
 * both fit their byte budgets. Output bytes dominate for the PP-OCRv6
 * dictionary (18,710 classes): the ≥5 GiB RAM tier yields 16 at WIDTH_640 and
 * 6 at WIDTH_1600. The output tensor is transient (freed after CTC decode)
 * and the vision lane is permit-serialized, so the tier budget is a peak
 * transient, not resident. Runtime failure/latency ladders in the executor
 * still halve below this ceiling, and the rolling-P95 governor can cap it
 * further.
 */
class PaddleOcrDynamicPageBatchPolicy(
    private val dictionarySize: Int,
    private val inputBudgetBytes: Long = DEFAULT_INPUT_BUDGET_BYTES,
    private val outputBudgetBytes: Long = DEFAULT_OUTPUT_BUDGET_BYTES,
    private val hardMaxBatch: Int = HARD_MAX_BATCH,
) {
    init {
        require(dictionarySize > 0) { "dictionarySize must be positive" }
        require(inputBudgetBytes > 0) { "inputBudgetBytes must be positive" }
        require(outputBudgetBytes > 0) { "outputBudgetBytes must be positive" }
        require(hardMaxBatch > 0) { "hardMaxBatch must be positive" }
    }

    fun ceilingFor(bucket: PaddleOcrWidthBucket): Int {
        val perSampleInputBytes = CHANNELS * RECOGNITION_HEIGHT *
            bucket.paddedWidth * FLOAT_BYTES
        val perSampleOutputBytes = (bucket.paddedWidth / OUTPUT_TIME_STEP_STRIDE).toLong() *
            (dictionarySize + OUTPUT_EXTRA_CLASSES) * FLOAT_BYTES
        val inputFit = (inputBudgetBytes / perSampleInputBytes).coerceAtLeast(1L)
        val outputFit = (outputBudgetBytes / perSampleOutputBytes).coerceAtLeast(1L)
        return minOf(hardMaxBatch.toLong(), inputFit, outputFit).toInt().coerceAtLeast(1)
    }

    companion object {
        /** Absolute batch-dimension bound for any dynamic chunk. */
        const val HARD_MAX_BATCH = 32

        /** Checked-in PP-OCRv6 small emits `width / 8` CTC steps. */
        const val OUTPUT_TIME_STEP_STRIDE = 8

        /** CTC blank plus the dictionary's explicit space token. */
        const val OUTPUT_EXTRA_CLASSES = 2

        /**
         * Peak-transient output budget by total device RAM. The output tensor
         * is ORT-native and freed right after decode; tiers keep ~90 MiB peak
         * on ≥5 GiB flagships, ~45 MiB on mid devices, ~30 MiB below that.
         */
        fun defaultOutputBudgetBytesFor(totalRamBytes: Long): Long = when {
            totalRamBytes >= 5L shl 30 -> 96L * 1024 * 1024
            totalRamBytes >= 3_758_096_384L -> 48L * 1024 * 1024 // 3.5 GiB
            else -> 32L * 1024 * 1024
        }

        private const val DEFAULT_INPUT_BUDGET_BYTES = 16L * 1024 * 1024
        private const val DEFAULT_OUTPUT_BUDGET_BYTES = 32L * 1024 * 1024
        private const val CHANNELS = 3L
        private const val RECOGNITION_HEIGHT = 48L
        private const val FLOAT_BYTES = 4L
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: same command. Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDynamicPageBatchPolicy.kt app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDynamicPageBatchPolicyTest.kt
git commit -m "feat(ocr): add memory-derived dynamic page batch ceiling policy"
```

---

### Task 2: Executor hard cap 32 + halving ladder; pool shares contract constants

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6BatchExecutor.kt:312-337`
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6BatchBufferPool.kt:30-32,195-204`
- Test: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6BatchExecutorTest.kt`

- [ ] **Step 1: Write the failing tests** (append inside `PaddleOcrV6BatchExecutorTest`; also make `newPool` accept a `maxBatchSize` parameter defaulting to 8)

```kotlin
@Test
fun `dynamic batch above eight runs as one call when the pool allows it`() {
    val session = FakeSession()
    val execution = PaddleOcrV6BatchExecutor(
        session = session,
        bufferPool = newPool(maxBatchSize = 32),
        dictionary = DICTIONARY,
    ).execute(
        crops = (0 until 15).toList(),
        widthBucket = 640,
        maxBatch = 15,
        writeSample = ::writeMarker,
    )

    session.calls.map { it.shape[0].toInt() } shouldContainExactly listOf(15)
    execution.telemetry.actualBatchSizes shouldContainExactly listOf(15)
    execution.results.size shouldBe 15
}

@Test
fun `provider failure on a dynamic batch halves instead of using the fixed ladder`() {
    val session = FakeSession(failuresByBatch = mutableMapOf(15 to 1))
    val execution = PaddleOcrV6BatchExecutor(
        session = session,
        bufferPool = newPool(maxBatchSize = 32),
        dictionary = DICTIONARY,
    ).execute(
        crops = (0 until 15).toList(),
        widthBucket = 640,
        maxBatch = 15,
        writeSample = ::writeMarker,
    )

    session.calls.map { it.shape[0].toInt() } shouldContainExactly listOf(15, 7, 7, 1)
    execution.telemetry.downgradeReasons shouldContainExactly listOf("provider_failure")
    execution.results.size shouldBe 15
}

@Test
fun `latency overrun on a dynamic batch halves and retries the same rows`() {
    var now = 0L
    val session = FakeSession(onRun = { batch ->
        now += if (batch >= 15) 100_000_000L else 1_000_000L
    })
    val execution = PaddleOcrV6BatchExecutor(
        session = session,
        bufferPool = newPool(maxBatchSize = 32),
        dictionary = DICTIONARY,
        latencyBudgetMs = 50.0,
        clockNanos = { now },
    ).execute(
        crops = (0 until 15).toList(),
        widthBucket = 640,
        maxBatch = 15,
        writeSample = ::writeMarker,
    )

    session.calls.map { it.shape[0].toInt() } shouldContainExactly listOf(15, 7, 7, 1)
    execution.telemetry.downgradeReasons shouldContainExactly listOf("latency_failure")
    execution.results.size shouldBe 15
}

@Test
fun `normalizeBatch clamps oversized requests to the hard max`() {
    val session = FakeSession()
    PaddleOcrV6BatchExecutor(
        session = session,
        bufferPool = newPool(maxBatchSize = 32),
        dictionary = DICTIONARY,
    ).execute(
        crops = (0 until 40).toList(),
        widthBucket = 640,
        maxBatch = 40,
        writeSample = ::writeMarker,
    )

    session.calls.map { it.shape[0].toInt() } shouldContainExactly listOf(32, 8)
}
```

`newPool` helper becomes:

```kotlin
private fun newPool(
    maxBatchSize: Int = 8,
    allocator: PaddleOcrV6BatchBufferPool.FloatBufferAllocator =
        PaddleOcrV6BatchBufferPool.FloatBufferAllocator { capacity -> FloatBuffer.allocate(capacity) },
): PaddleOcrV6BatchBufferPool = PaddleOcrV6BatchBufferPool(
    maxBatchSize = maxBatchSize,
    maxWidth = 1600,
    dictionarySize = DICTIONARY.size,
    allocator = allocator,
)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew --max-workers=2 "-Dkotlin.daemon.jvmargs=-Xmx3g" :app:testDebugUnitTest --tests "eu.kanade.translation.engines.vision.ocr.PaddleOcrV6BatchExecutorTest"`
Expected: the four new tests FAIL (15 splits into 8+7 under the old cap; `normalizeBatch` clamps 40→8)

- [ ] **Step 3: Implement**

In `PaddleOcrV6BatchExecutor`:

```kotlin
private fun normalizeBatch(maxBatch: Int): Int = maxBatch.coerceIn(MIN_BATCH_SIZE, HARD_MAX_BATCH)

private fun lowerBatch(batchSize: Int): Int = when {
    // Dynamic page batches halve so the largest working size is found
    // quickly; at/below the reviewed fixed tiers the B8 -> B4 -> B1
    // production ladder is preserved.
    batchSize > MAX_BATCH_SIZE -> (batchSize / 2).coerceAtLeast(MIN_BATCH_SIZE)
    batchSize >= MAX_BATCH_SIZE -> MEDIUM_BATCH_SIZE
    batchSize == SMALL_BATCH_SIZE -> MIN_BATCH_SIZE
    else -> MIN_BATCH_SIZE
}
```

Companion gains (and imports `eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrDynamicPageBatchPolicy`):

```kotlin
const val HARD_MAX_BATCH = PaddleOcrDynamicPageBatchPolicy.HARD_MAX_BATCH
```

In `PaddleOcrV6BatchBufferPool`, replace the private `OUTPUT_TIME_STEP_STRIDE`/`OUTPUT_EXTRA_CLASSES` companion constants with references (delete the private copies, import the policy):

```kotlin
private val maxOutputElements: Long =
    maxBatchSize.toLong() * (maxWidth / PaddleOcrDynamicPageBatchPolicy.OUTPUT_TIME_STEP_STRIDE).toLong() *
        (dictionarySize + PaddleOcrDynamicPageBatchPolicy.OUTPUT_EXTRA_CLASSES).toLong()
```

- [ ] **Step 4: Run the full executor test class** — all PASS (old + new)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6BatchExecutor.kt app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6BatchBufferPool.kt app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6BatchExecutorTest.kt
git commit -m "feat(ocr): allow dynamic batches to 32 with halving failure ladder"
```

---

### Task 3: Planner per-bucket chunk function

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrBatchPlanner.kt:84-124,203-215`
- Test: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrBatchPlannerTest.kt`

- [ ] **Step 1: Grep for external uses of `maxInFlightLeavesPerBucket`** (it becomes per-bucket). Update any caller found alongside the planner change.

- [ ] **Step 2: Write the failing tests** (append to `PaddleOcrBatchPlannerTest`)

```kotlin
@Test
fun `dynamic chunk admits the whole page into one batch at finish`() {
    val page = PaddleOcrPageGeneration("chapter/page-dyn", generation = 1L)
    val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B8, chunkSizeFor = { 15 })
    val leaves = leavesIn(page, count = 12, bucket = PaddleOcrWidthBucket.WIDTH_640)

    leaves.forEach { planner.admit(it) shouldBe null }
    val batches = planner.finishPage()

    batches.map { it.size } shouldBe listOf(12)
    planner.complete(batches.single(), List(12) { "ok" }) { _, _ -> }
    planner.isDrained shouldBe true
}

@Test
fun `dynamic chunk denser than the ceiling still streams full chunks`() {
    val page = PaddleOcrPageGeneration("chapter/page-dyn2", generation = 1L)
    val planner = PaddleOcrBatchPlanner<String>(page, PaddleOcrBatchSize.B8, chunkSizeFor = { 15 })
    val leaves = leavesIn(page, count = 20, bucket = PaddleOcrWidthBucket.WIDTH_640)

    val streamed = leaves.mapNotNull { planner.admit(it) }
    val remainder = planner.finishPage()

    (streamed + remainder).map { it.size } shouldBe listOf(15, 5)
}

@Test
fun `dynamic chunks are independent per width bucket`() {
    val page = PaddleOcrPageGeneration("chapter/page-dyn3", generation = 1L)
    val planner = PaddleOcrBatchPlanner<String>(
        page,
        PaddleOcrBatchSize.B8,
        chunkSizeFor = { bucket ->
            if (bucket == PaddleOcrWidthBucket.WIDTH_640) 15 else 6
        },
    )
    val narrow = leavesIn(page, count = 3, bucket = PaddleOcrWidthBucket.WIDTH_640)
    val wide = leavesIn(page, count = 2, bucket = PaddleOcrWidthBucket.WIDTH_1600)

    (narrow + wide).forEach { planner.admit(it) }
    // finishPage() emits in enum declaration order: WIDTH_640, then WIDTH_1600.
    // (Do NOT sort by name — "WIDTH_1600" sorts before "WIDTH_640".)
    val batches = planner.finishPage()

    batches.map { it.size to it.widthBucket } shouldBe listOf(
        3 to PaddleOcrWidthBucket.WIDTH_640,
        2 to PaddleOcrWidthBucket.WIDTH_1600,
    )
}
```

Add/verify a `leavesIn(page, count, bucket)` helper in the test (mirror the existing leaf factory used by current tests, adding the `widthBucket` parameter; if the existing helper hard-codes a bucket, parameterize it — existing call sites keep the old value via a default argument).

- [ ] **Step 3: Run tests to verify they fail** (planner has no `chunkSizeFor` parameter)

- [ ] **Step 4: Implement**

```kotlin
class PaddleOcrBatchPlanner<CROP>(
    val pageGeneration: PaddleOcrPageGeneration,
    val batchSize: PaddleOcrBatchSize,
    private val chunkSizeFor: (PaddleOcrWidthBucket) -> Int = { batchSize.value },
) {
    fun chunkSize(bucket: PaddleOcrWidthBucket): Int = chunkSizeFor(bucket).coerceAtLeast(1)

    fun maxInFlightLeavesPerBucket(bucket: PaddleOcrWidthBucket): Int = chunkSize(bucket) * 2
    // ... pending/inFlight/admittedIdentities/nextSequence/pageFinished unchanged
```

`admit` window check and emit threshold:

```kotlin
val bucket = leaf.widthBucket
if (inFlightLeafCount(bucket) >= maxInFlightLeavesPerBucket(bucket)) { /* unchanged throw */ }
val queue = pending.getValue(bucket)
queue.addLast(leaf)
return if (queue.size >= chunkSize(bucket)) emit(bucket) else null
```

`emit` collection bound: `while (queue.isNotEmpty() && leaves.size < chunkSize(bucket))`.

- [ ] **Step 5: Run the full planner test class** — PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrBatchPlanner.kt app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrBatchPlannerTest.kt
git commit -m "feat(ocr): planner accepts per-bucket dynamic chunk sizes"
```

---

### Task 4: `DYNAMIC` preference tier + activation mapping

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt:49`
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDevicePolicy.kt:18-23` (`PaddleOcrBatchActivation` field)
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrBatchActivationPolicy.kt`
- Test: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDevicePolicyTest.kt`

- [ ] **Step 1: Write the failing tests** (append to `PaddleOcrDevicePolicyTest`)

```kotlin
@Test
fun `DYNAMIC preference maps to the B8 tier with the dynamic flag in debug`() {
    val decision = PaddleOcrBatchActivationPolicy.current(
        requestedBatchSize = PaddleOcrBatchSize.B8,
        requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
        dynamicPageBatch = true,
    )
    decision.activeBatchSize shouldBe PaddleOcrBatchSize.B8
    decision.dynamicPageBatch shouldBe true
    decision.forceCpuB1EmergencyFallback shouldBe false
}

@Test
fun `DYNAMIC skips the staged build-config cap that clamps fixed tiers`() {
    // Build config caps fixed tiers at 4; a dynamic request must not be
    // clamped because its ceiling is memory-derived at runtime.
    val decision = PaddleOcrBatchActivationPolicy.current(
        requestedBatchSize = PaddleOcrBatchSize.B8,
        requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
        dynamicPageBatch = true,
    )
    decision.activeBatchSize shouldBe PaddleOcrBatchSize.B8
}

@Test
fun `staged flag off forces emergency CPU B1 even for dynamic`() {
    val decision = PaddleOcrDevicePolicy.resolveActivation(
        stagedEnabled = false,
        requestedBatchSize = PaddleOcrBatchSize.B8,
        requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
        profile = PaddleOcrDeviceProfile.untested(),
    )
    decision.activeBatchSize shouldBe PaddleOcrBatchSize.B1
    decision.forceCpuB1EmergencyFallback shouldBe true
}
```

Extend the existing `recognition preference maps B1 B2 and B4 through debug activation` test's map with `PaddleOcrRecognitionBatch.DYNAMIC to PaddleOcrBatchSize.B8` (assert `dynamicPageBatch` true for that row).

- [ ] **Step 2: Run to verify failure** (unresolved `dynamicPageBatch` / `DYNAMIC`)

- [ ] **Step 3: Implement**

Domain enum:

```kotlin
/** User-requested PaddleOCR v6 recognizer microbatch size. */
enum class PaddleOcrRecognitionBatch { B1, B2, B4, DYNAMIC }
```

`PaddleOcrBatchActivation`:

```kotlin
data class PaddleOcrBatchActivation(
    val activeBatchSize: PaddleOcrBatchSize,
    val providerTarget: PaddleOcrProviderTarget?,
    val forceCpuB1EmergencyFallback: Boolean,
    val reason: String,
    /** Whole-page dynamic chunking armed; the tier acts as the governor cap. */
    val dynamicPageBatch: Boolean = false,
)
```

`PaddleOcrBatchActivationPolicy`:

```kotlin
fun currentFromPreferences(
    preferences: TranslationPreferences,
    requestedProvider: PaddleOcrExecutionProvider?,
): PaddleOcrBatchActivation {
    val preference = preferences.paddleOcrRecognitionBatch().get()
    return current(
        requestedBatchSize = when (preference) {
            PaddleOcrRecognitionBatch.B1 -> PaddleOcrBatchSize.B1
            PaddleOcrRecognitionBatch.B2 -> PaddleOcrBatchSize.B2
            PaddleOcrRecognitionBatch.B4 -> PaddleOcrBatchSize.B4
            PaddleOcrRecognitionBatch.DYNAMIC -> PaddleOcrBatchSize.B8
        },
        requestedProvider = requestedProvider?.toBatchProvider(),
        dynamicPageBatch = preference == PaddleOcrRecognitionBatch.DYNAMIC,
    )
}

fun current(
    requestedBatchSize: PaddleOcrBatchSize = PaddleOcrBatchSize.B1,
    profile: PaddleOcrDeviceProfile = PaddleOcrDeviceProfile.untested(),
    widthBucket: PaddleOcrWidthBucket = PaddleOcrWidthBucket.WIDTH_640,
    thermalSeverity: Int = 0,
    requestedProvider: PaddleOcrProviderTarget? = null,
    dynamicPageBatch: Boolean = false,
): PaddleOcrBatchActivation {
    val buildConfigBatch = when (BuildConfig.PADDLE_BATCHING_REQUESTED_BATCH) { /* unchanged */ }
    val requestedBatch = if (BuildConfig.PADDLE_BATCHING_STAGED && !dynamicPageBatch) {
        requestedBatchSize.capAt(buildConfigBatch)
    } else {
        requestedBatchSize
    }
    return PaddleOcrDevicePolicy.resolveActivation(
        stagedEnabled = BuildConfig.PADDLE_BATCHING_STAGED,
        requestedBatchSize = requestedBatch,
        requestedProvider = requestedProvider,
        profile = profile,
        widthBucket = widthBucket,
        thermalSeverity = thermalSeverity,
        debugProvisionalOptIn = BuildConfig.DEBUG && requestedBatchSize != PaddleOcrBatchSize.B1,
    ).copy(dynamicPageBatch = dynamicPageBatch)
}
```

- [ ] **Step 4: Run the policy test class** — PASS

- [ ] **Step 5: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDevicePolicy.kt app/src/main/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrBatchActivationPolicy.kt app/src/test/java/eu/kanade/translation/engines/vision/ocr/paddle/batch/PaddleOcrDevicePolicyTest.kt
git commit -m "feat(ocr): add DYNAMIC recognition batch tier routed through existing gates"
```

---

### Task 5: Coordinator/dispatcher plumbing + engine session support

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinator.kt:43-46,54-63,71-84,120-144,166-176,307-326`
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6SmallEngine.kt:29-43,136-140,346-360` (pool sizing at the `PaddleOcrV6BatchBufferPool` construction, dictionary size exposure, companion constants)
- Modify: `app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinatorTest.kt` (dispatcher lambda signature)
- Test: same coordinator test file (new dynamic test)

- [ ] **Step 1: Grep `PaddlePageOcrBatchDispatcher(` and `recognizeBatch = {` across `app/src`** — every construction site's lambda changes from `(crops, bucket, requestedBatchSize: PaddleOcrBatchSize)` to `(crops, bucket, maxBatch: Int)`; update all (main + tests, incl. `PaddleOcrB1TestFixtures.kt` users and lifecycle tests if present).

- [ ] **Step 2: Write the failing test** (append to `PaddlePageOcrCoordinatorTest`)

```kotlin
@Test
fun `dynamic ceiling batches the whole page per width bucket in one call`() = runBlocking<Unit> {
    val page = PaddleOcrPageGeneration("chapter/page-dynamic", generation = 21L)
    val calls = ArrayList<Int>()
    val dispatcher = PaddlePageOcrBatchDispatcher<String, String>(
        pageGeneration = page,
        policy = PaddlePageOcrPolicy(
            PaddlePageOcrMode.AUTO,
            PaddleOcrBatchSize.B8,
            dynamicCeiling = { 15 },
        ),
        batchCallMutex = Mutex(),
        recognizeBatch = { crops, _, maxBatch ->
            calls += maxBatch
            crops.map { "recognized-$it" }
        },
    )
    val leaves = leaves(page, count = 12)

    leaves.forEach { dispatcher.submit(it) }
    dispatcher.finish()

    calls shouldBe listOf(12) // one whole-page call
    assertEquals(12, dispatcher.resolvedLeafCount)
    assertEquals(listOf(12), dispatcher.batchTraces.map { it.requestedChunkSize })
}
```

(Adapt `leaves(...)`/assertion imports to the file's existing helpers; `shouldBe` needs `io.kotest.matchers.shouldBe` import or use JUnit `assertEquals`.)

- [ ] **Step 3: Run to verify failure** (`dynamicCeiling`/`requestedChunkSize` unresolved)

- [ ] **Step 4: Implement**

`PaddlePageOcrPolicy` and trace:

```kotlin
internal data class PaddlePageOcrPolicy(
    val mode: PaddlePageOcrMode,
    val validatedBatchSize: PaddleOcrBatchSize = PaddleOcrBatchSize.B1,
    /**
     * Whole-page dynamic chunking; null (function or return) keeps fixed tier
     * chunking. The return is nullable so a caller can arm the seam before the
     * dynamic policy exists (Task 6 passes a helper that returns null when
     * fixed-tier batching is active).
     */
    val dynamicCeiling: ((bucket: PaddleOcrWidthBucket) -> Int?)? = null,
)

internal data class PaddlePageBatchTrace(
    // ... existing fields ...
    val requestedBatchSize: PaddleOcrBatchSize,
    val requestedChunkSize: Int = requestedBatchSize.value,
    // ...
)
```

Dispatcher:

```kotlin
private val planner = PaddleOcrBatchPlanner<CROP>(
    pageGeneration,
    policy.validatedBatchSize,
    chunkSizeFor = { bucket ->
        policy.dynamicCeiling?.invoke(bucket) ?: policy.validatedBatchSize.value
    },
)
```

`recognizeBatch` signature becomes `suspend (crops: List<CROP>, widthBucket: PaddleOcrWidthBucket, maxBatch: Int) -> List<RESULT>`; `execute` calls:

```kotlin
val rows = batchCallMutex.withLock {
    recognizeBatch(batch.leaves.map { it.crop }, batch.widthBucket, batch.leaves.size)
}
```

and the trace gains `requestedChunkSize = batch.size`.

Coordinator constructor + `recognizePage` policy construction:

```kotlin
internal class PaddlePageOcrCoordinator(
    private val engine: PaddleOcrV6SmallEngine,
    internal val validatedBatchSize: PaddleOcrBatchSize = PaddleOcrBatchSize.B1,
    private val dynamicCeiling: ((bucket: PaddleOcrWidthBucket) -> Int?)? = null,
    private val nowNanos: () -> Long = System::nanoTime,
)
// in recognizePage:
val policy = PaddlePageOcrPolicy(mode, validatedBatchSize, dynamicCeiling)
```

Coordinator's `recognizeBatch` lambda:

```kotlin
recognizeBatch = { crops, widthBucket, maxBatch ->
    if (maxBatch <= 1) {
        crops.map { engine.recognizeWithConf(it) }
    } else {
        engine.recognizeBucketBatch(crops, widthBucket.paddedWidth, maxBatch)
    }
}
```

`logCropSummary` batchSize becomes `maxOf(validatedBatchSize.value, traces.maxOfOrNull { it.requestedChunkSize } ?: 0)`.

`PaddleOcrV6SmallEngine`: expose `val dictionarySize: Int get() = dictionary.size`; construct the pool with `maxBatchSize = PaddleOcrDynamicPageBatchPolicy.HARD_MAX_BATCH` (import the policy); drop the now-unused `MAX_BATCH_SIZE` const if nothing else references it (grep first).

- [ ] **Step 5: Run coordinator + lifecycle + B1 parity test classes** — PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinator.kt app/src/main/java/eu/kanade/translation/engines/vision/ocr/PaddleOcrV6SmallEngine.kt app/src/test/java/eu/kanade/translation/engines/vision/ocr/PaddlePageOcrCoordinatorTest.kt
git commit -m "feat(ocr): thread dynamic page ceilings through coordinator and dispatcher"
```

---

### Task 6: `RoiPageRecognitionEngine` wiring + governor interplay

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt:81,261-311,379-403,801-819`

- [ ] **Step 1: Implement** (no unit-testable seam here beyond existing classes; verified by compile + Task 7 suite)

New field + helper:

```kotlin
private var paddleDynamicBatchPolicy: PaddleOcrDynamicPageBatchPolicy? = null

/** Governor-aware dynamic chunk ceiling; null when fixed-tier batching is active. */
private fun paddleDynamicCeiling(bucket: PaddleOcrWidthBucket): Int? {
    val dynamicPolicy = paddleDynamicBatchPolicy ?: return null
    val ceiling = dynamicPolicy.ceilingFor(bucket)
    val governorCap = paddleBatchGovernor?.activeBatchSize
        ?.takeIf { it != PaddleOcrBatchSize.B8 }
        ?.value
    return if (governorCap != null) minOf(ceiling, governorCap) else ceiling
}
```

In `initialize()`'s PADDLEOCR_V6_SMALL branch, after `it.initialize(...)`:

```kotlin
paddleDynamicBatchPolicy = if (activation.dynamicPageBatch) {
    val totalRamBytes = try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        android.app.ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }.totalMem
    } catch (_: Throwable) {
        0L // unknown RAM -> most conservative tier
    }
    PaddleOcrDynamicPageBatchPolicy(
        dictionarySize = it.dictionarySize,
        outputBudgetBytes = PaddleOcrDynamicPageBatchPolicy.defaultOutputBudgetBytesFor(totalRamBytes),
    )
} else {
    null
}
paddlePageOcrCoordinator = PaddlePageOcrCoordinator(
    engine = it,
    validatedBatchSize = activation.activeBatchSize,
    dynamicCeiling = ::paddleDynamicCeiling,
)
```

In `recordPaddleBatchLatencies`'s rebuild, pass `dynamicCeiling = ::paddleDynamicCeiling` too (governor tier change re-caps the ceiling). In `freeNativeSessions`, null out `paddleDynamicBatchPolicy`. In the perf log: `val paddleDynamic = localPaddlePageCoordinator != null && paddleDynamicBatchPolicy != null`, log `dynamic=$paddleDynamic` and keep `batchSize` as the tier value.

- [ ] **Step 2: Compile + run the ocr/vision test packages** — PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/engines/vision/ocr/RoiPageRecognitionEngine.kt
git commit -m "feat(ocr): arm dynamic page batching with governor-capped ceilings"
```

---

### Task 7: Settings UI + strings

**Files:**
- Modify: `i18n-at/src/commonMain/moko-resources/base/strings.xml:30-34`
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt:231-240`

- [ ] **Step 1: Add strings**

```xml
<string name="pref_paddle_ocr_batch_summary">B1 is the production default. Dynamic batches the whole page per width bucket (recommended). B2/B4 are fixed experimental sizes; changes apply when a new reader/translation session creates OCR sessions.</string>
<string name="pref_paddle_ocr_batch_dynamic">Auto — whole page (dynamic)</string>
```

- [ ] **Step 2: Add the list entry**

```kotlin
entries = mapOf(
    PaddleOcrRecognitionBatch.DYNAMIC to stringResource(ATMR.strings.pref_paddle_ocr_batch_dynamic),
    PaddleOcrRecognitionBatch.B1 to stringResource(ATMR.strings.pref_paddle_ocr_batch_b1),
    PaddleOcrRecognitionBatch.B2 to stringResource(ATMR.strings.pref_paddle_ocr_batch_b2),
    PaddleOcrRecognitionBatch.B4 to stringResource(ATMR.strings.pref_paddle_ocr_batch_b4),
).toImmutableMap(),
```

- [ ] **Step 3: Compile** `:app:compileDebugKotlin` (and `:i18n-at` generates resources) — PASS

- [ ] **Step 4: Commit**

```bash
git add i18n-at/src/commonMain/moko-resources/base/strings.xml app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt
git commit -m "feat(ocr): expose the dynamic whole-page recognition batch preference"
```

---

### Task 8: Full verification

- [ ] **Step 1:** `./gradlew --max-workers=2 "-Dkotlin.daemon.jvmargs=-Xmx3g" :app:testDebugUnitTest` — all PASS
- [ ] **Step 2:** `./gradlew --max-workers=2 "-Dkotlin.daemon.jvmargs=-Xmx3g" :app:assembleDebug` — BUILD SUCCESSFUL
- [ ] **Step 3:** Install on device `192.168.100.223:46123`, select **PaddleOCR v6 recognition batch → Auto — whole page (dynamic)**, restart the reader session, and verify via `trace_stream.ps1` that `crop_summary` reports whole-page-sized batches (a page with ≤16 leaves in the 640 bucket should show a single batch; expect ceilings 16@640 / 6@1600 on this 6 GB device) and `ocrMs` drops versus the 2 s baseline. The governor log line `[paddle_batch_governor]` must stay at `from=8` unless sustained P95 > 1 s downgrades it.
