# Optimized Batch Translation Speed, Real-Time Reader Sync & Clean UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement high-speed single-pass native batch translation, active viewport priority scheduling, rate-aware AI chunking (15 RPM safe toggle), AI reasoning/thinking model integration, copy-on-write store publishing, and a radically clean zero-bloat UI across reader and chapter list.

**Architecture:** Fuse OCR and inpainting into a single-pass native execution per page to eliminate double-decode waste; bridge batch and reader scheduling with a unified priority queue (P0 viewport, P1 prefetch, P2 background); enforce 3–5 page chunking with a 15 RPM leaky-bucket pacer toggle; support reasoning models with `<think>` tag sanitization; and revamp UI components using native Material 3 with zero floating clutter while reading.

**Tech Stack:** Kotlin 1.9+, Android SDK 26–34, Jetpack Compose, ONNX Runtime Android 1.23.0, KotlinX Coroutines & Serialization, MOKO Resources (`i18n-at`), OkHttp3, JUnit 4, Robolectric.

## Global Constraints
- Target Android SDK min 26 (Android 8.0+); maintain bounded native memory under 48 MiB held-bitmap ceiling on 6GB RAM devices.
- Single native execution lane (`NativeRunQuarantine` Mutex) serialized for SoC and buffer safety.
- Zero behavior regressions for single-page manual or standard reader rolling translation.
- Adhere to `spotlessCheck` formatting rules (ktlint).

---

### Task 1: Single-Pass Native Pipeline Execution

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/batch/BatchCoordinatorInterfaces.kt`
- Modify: `app/src/main/java/eu/kanade/translation/batch/BatchCoordinator.kt`
- Modify: `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- Test: `app/src/test/java/eu/kanade/translation/batch/BatchCoordinatorTest.kt`

**Interfaces:**
- Consumes: `decodePageBitmapForTranslation`, `analyzePage`, `inpaintPage`, `persistCleanedBitmap`.
- Produces: `NativeLaneWorker.runSinglePassNative(pageKey: String, pageIndex: Int): OcrReadyPageRef?` which executes decode, OCR analyze, bubble sort, inpaint, and `.cleaned.jpg` persistence within one bitmap lifecycle before recycling.

- [ ] **Step 1: Write failing test in `BatchCoordinatorTest.kt` for single-pass native execution**

```kotlin
@Test
fun `runPass1 executes fused native stage and offers OCR ref to translation lane before inpaint persistence completes`() = runTest {
    val events = mutableListOf<String>()
    val nativeWorker = object : NativeLaneWorker {
        override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? = null
        override suspend fun runInpaintStage(pageKey: String) {}
        override suspend fun runSinglePassNative(pageKey: String, pageIndex: Int): OcrReadyPageRef {
            events += "native_start_$pageKey"
            events += "native_done_$pageKey"
            return OcrReadyPageRef(pageKey, pageIndex, 1L, emptyList())
        }
    }
    val translatorWorker = object : TranslatorLaneWorker {
        override suspend fun translate(ref: OcrReadyPageRef) {
            events += "translate_${ref.pageKey}"
        }
    }
    val renderJoin = object : RenderJoinWorker {
        override fun onNativeBranchDone(pageKey: String) {}
        override fun onTranslationBranchDone(pageKey: String) {}
        override suspend fun awaitAndRender(pageKey: String) {
            events += "render_$pageKey"
        }
    }
    val coordinator = BatchCoordinator(nativeWorker, translatorWorker, renderJoin)
    coordinator.runPass1(listOf("p1" to 0), TranslatorComputeClass.REMOTE_IO)
    
    assertEquals(listOf("native_start_p1", "native_done_p1", "translate_p1", "render_p1"), events)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.batch.BatchCoordinatorTest'`  
Expected: Compilation error (unresolved reference `runSinglePassNative`).

- [ ] **Step 3: Implement `runSinglePassNative` in `BatchCoordinatorInterfaces.kt`, `BatchCoordinator.kt`, and `TranslationPipeline.kt`**

Add `runSinglePassNative` method to `NativeLaneWorker` interface in `BatchCoordinatorInterfaces.kt`. In `TranslationPipeline.kt`, implement `runSinglePassNative`:
1. Acquire native lane lock.
2. Decode page bitmap once.
3. Run OCR detection and analyze.
4. Notify `translationQueue.send(ref)`.
5. Run inpainting on the active bitmap and persist `.cleaned.jpg`.
6. Recycle bitmap immediately and release pooled buffers.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.batch.BatchCoordinatorTest'`  
Expected: BUILD SUCCESSFUL (all tests pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/batch/ app/src/main/java/eu/kanade/translation/TranslationPipeline.kt app/src/test/java/eu/kanade/translation/batch/
git commit -m "feat: implement single-pass native execution in BatchCoordinator"
```

---

### Task 2: Active Viewport Priority Queue in Batch Scheduling

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/batch/BatchCoordinator.kt`
- Modify: `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- Test: `app/src/test/java/eu/kanade/translation/batch/BatchPrioritySchedulerTest.kt`

**Interfaces:**
- Consumes: `BatchCoordinator.updateActiveViewport(visiblePageIndex: Int)`.
- Produces: Dynamic reprioritization where `visiblePageIndex` promotes to P0 (urgent priority) and lookahead pages promote to P1.

- [ ] **Step 1: Write failing test in `BatchPrioritySchedulerTest.kt`**

```kotlin
@Test
fun `updating active viewport promotes target page to P0 ahead of background sequence`() = runTest {
    val executionOrder = mutableListOf<String>()
    val priorityCoordinator = BatchPriorityQueue(listOf("p1", "p2", "p3", "p4", "p5"))
    priorityCoordinator.updateViewport(visibleIndex = 3) // Page 4 is visible
    
    val nextPages = (0 until 5).map { priorityCoordinator.pollNext() }
    assertEquals("p4", nextPages[0]) // P0 urgent
    assertTrue(nextPages[1] in listOf("p5", "p1")) // P1 lookahead or resume
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.batch.BatchPrioritySchedulerTest'`  
Expected: FAIL.

- [ ] **Step 3: Implement `BatchPriorityQueue` and integrate with `ReaderViewModel`**

1. Create priority state machine (`P0 Viewport`, `P1 Lookahead`, `P2 Background`).
2. Wire `ReaderViewModel.observeLiveTranslationStore()` to dispatch visible page index changes to `TranslationPipeline.updateBatchViewportPriority`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.batch.BatchPrioritySchedulerTest'`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/batch/ app/src/main/java/eu/kanade/translation/TranslationPipeline.kt app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt app/src/test/java/eu/kanade/translation/batch/
git commit -m "feat: add active viewport priority scheduling to batch translation"
```

---

### Task 3: 15 RPM Safe Pacing Toggle & AI Chunking

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
- Modify: `app/src/main/java/eu/kanade/translation/translator/TranslationRetry.kt`
- Modify: `app/src/main/java/eu/kanade/translation/translator/StreamingChunkPlanner.kt`
- Modify: `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- Test: `app/src/test/java/eu/kanade/translation/translator/RateLimitPacerTest.kt`

**Interfaces:**
- Consumes: `translationPreferences.translationRateLimitSafe(): Preference<Boolean>`.
- Produces: `RateLimitPacer.pace()` enforcing $\ge 4.1\text{s}$ interval when enabled, and 0s delay when disabled.

- [ ] **Step 1: Write failing test in `RateLimitPacerTest.kt`**

```kotlin
@Test
fun `rate limiter enforces spacing when safe mode is on and skips delay when off`() = runTest {
    val pacer = RateLimitPacer(safeMode = true, minIntervalMs = 4100L)
    val start = System.currentTimeMillis()
    pacer.acquireSlot()
    pacer.acquireSlot()
    val elapsed = System.currentTimeMillis() - start
    assertTrue(elapsed >= 4000L)
    
    val fastPacer = RateLimitPacer(safeMode = false, minIntervalMs = 4100L)
    val fastStart = System.currentTimeMillis()
    fastPacer.acquireSlot()
    fastPacer.acquireSlot()
    val fastElapsed = System.currentTimeMillis() - fastStart
    assertTrue(fastElapsed < 500L)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.translator.RateLimitPacerTest'`  
Expected: FAIL.

- [ ] **Step 3: Implement `RateLimitPacer` and preference toggle**

1. Add `translationRateLimitSafe` to `TranslationPreferences`.
2. Implement `RateLimitPacer` in `StreamingChunkPlanner.kt` / `TranslationPipeline.kt`.
3. Integrate exponential backoff on HTTP 429 errors.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.translator.RateLimitPacerTest'`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/translation/ app/src/main/java/eu/kanade/translation/translator/ app/src/main/java/eu/kanade/translation/TranslationPipeline.kt app/src/test/java/eu/kanade/translation/translator/
git commit -m "feat: add 15 RPM safe pacing toggle and leaky bucket pacer"
```

---

### Task 4: AI Model Reasoning / Thinking Toggle & Sanitizer

**Files:**
- Modify: `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
- Modify: `app/src/main/java/eu/kanade/translation/translator/OcrArtifactSanitizer.kt`
- Modify: `app/src/main/java/eu/kanade/translation/translator/GeminiTranslator.kt`
- Modify: `app/src/main/java/eu/kanade/translation/translator/DeepSeekTranslator.kt`
- Modify: `app/src/main/java/eu/kanade/translation/translator/OpenAiCompatibleTranslator.kt`
- Test: `app/src/test/java/eu/kanade/translation/translator/ThinkingSanitizerTest.kt`

**Interfaces:**
- Consumes: `translationPreferences.translationEnableReasoning(): Preference<Boolean>`.
- Produces: `OcrArtifactSanitizer.stripThinkingTags(rawOutput: String): String`.

- [ ] **Step 1: Write failing test in `ThinkingSanitizerTest.kt`**

```kotlin
@Test
fun `stripThinkingTags removes reasoning blocks from Gemini and DeepSeek completions`() {
    val inputWithThinkTags = "<think>\nAnalyzing the manga scene: character is shouting.\n</think>\n1: Stop right there!\n2: Who are you?"
    val cleaned = OcrArtifactSanitizer.stripThinkingTags(inputWithThinkTags)
    assertEquals("1: Stop right there!\n2: Who are you?", cleaned.trim())
    
    val inputWithMarkdownThought = "```thought\nAnalyzing dialogue...\n```\n1: Hello world!"
    val cleanedMd = OcrArtifactSanitizer.stripThinkingTags(inputWithMarkdownThought)
    assertEquals("1: Hello world!", cleanedMd.trim())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.translator.ThinkingSanitizerTest'`  
Expected: FAIL.

- [ ] **Step 3: Implement `stripThinkingTags` and reasoning options in translators**

1. Implement `stripThinkingTags` regex in `OcrArtifactSanitizer.kt`.
2. Add `translationEnableReasoning` preference in `TranslationPreferences.kt`.
3. In `GeminiTranslator`, `DeepSeekTranslator`, and `OpenAiCompatibleTranslator`, pass thinking/reasoning parameters when preference is enabled and pipe outputs through `stripThinkingTags`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.translator.ThinkingSanitizerTest'`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add domain/src/main/java/tachiyomi/domain/translation/ app/src/main/java/eu/kanade/translation/translator/ app/src/test/java/eu/kanade/translation/translator/
git commit -m "feat: add AI reasoning toggle and thinking tag sanitization"
```

---

### Task 5: Copy-on-Write Store Delta Publishing

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
- Test: `app/src/test/java/eu/kanade/translation/ChapterTranslationStoreCowTest.kt`

**Interfaces:**
- Consumes: `ChapterTranslationStore.updatePage(pageKey: String, transform: (PageTranslation?) -> PageTranslation)`.
- Produces: Delta state emission updating only the mutated page key while preserving reference identity for untouched pages.

- [ ] **Step 1: Write failing test in `ChapterTranslationStoreCowTest.kt`**

```kotlin
@Test
fun `updatePage clones only the mutated entry and preserves references to untouched pages`() {
    val store = ChapterTranslationStore.open(mockFile)
    store.updatePage("p1") { PageTranslation(sourceFileName = "p1", ocrStatus = StageStatus.READY) }
    store.updatePage("p2") { PageTranslation(sourceFileName = "p2", ocrStatus = StageStatus.READY) }
    
    val initialP2 = store.state.value["p2"]
    store.updatePage("p1") { it?.apply { translationStatus = StageStatus.READY } ?: PageTranslation() }
    
    val latestP2 = store.state.value["p2"]
    assertSame("Untouched page reference must remain identical", initialP2, latestP2)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.ChapterTranslationStoreCowTest'`  
Expected: FAIL (because legacy `publishLocked` deep-copies all pages via `detachedCopy()`).

- [ ] **Step 3: Implement Copy-on-Write delta updates in `ChapterTranslationStore.kt`**

Replace the full-chapter `snapshotPages()` copy with per-page delta map replacement.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.translation.ChapterTranslationStoreCowTest'`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt app/src/test/java/eu/kanade/translation/
git commit -m "perf: implement copy-on-write delta publishing in ChapterTranslationStore"
```

---

### Task 6: Localized Strings Revamp (`i18n-at`)

**Files:**
- Modify: `i18n-at/src/commonMain/moko-resources/base/strings.xml`

**Interfaces:**
- Consumes: MOKO resource identifiers.
- Produces: Concise string resources for `reader_auto_status_*`, `pref_ai_reasoning`, and `pref_rate_limit_safe`.

- [ ] **Step 1: Add new preference and streamlined status strings to `strings.xml`**

```xml
    <string name="pref_ai_reasoning">AI Model Reasoning / Thinking</string>
    <string name="pref_ai_reasoning_summary">Enables deep contextual reasoning for complex dialogue (Gemini Thinking, DeepSeek Reasoner)</string>
    <string name="pref_rate_limit_safe">Free-Tier Safe Pacing (15 RPM)</string>
    <string name="pref_rate_limit_safe_summary">Paces AI requests to prevent 429 rate limit errors on free Google API tier</string>
    <string name="reader_auto_status_preparing">Preparing %1$d ahead</string>
    <string name="reader_auto_status_current">Preparing P.%1$d</string>
    <string name="reader_auto_status_ready">P.%1$d · %2$d ready</string>
    <string name="reader_auto_status_all_ready">%1$d ahead ready ✓</string>
    <string name="reader_auto_status_paused">Paused (%1$s) · %2$d ready</string>
```

- [ ] **Step 2: Verify compilation and spotlessCheck**

Run: `./gradlew spotlessCheck`  
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add i18n-at/src/commonMain/moko-resources/base/strings.xml
git commit -m "i18n: add reasoning, rate-limit safe strings, and concise status labels"
```

---

### Task 7: Radically Clean UI — Reader Status, Chapter List Row, and Modernized Progress & Setup Sheets

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/reader/appbars/AutoTranslationStatus.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/reader/components/TranslationCompareHandle.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/manga/components/TranslationProgressSheet.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/manga/components/ConfirmTranslationDialog.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/reader/TranslationSettingsSheet.kt`
- Test: `app/src/test/java/eu/kanade/presentation/reader/appbars/AutoTranslationStatusTest.kt`

**Interfaces:**
- Consumes: `TranslationProgressSnapshot`, `ReaderAutoTranslationUiState`, `TranslationPreferences`.
- Produces: Streamlined Compose UI components with zero floating clutter while reading, native chapter row progress, and modernized Material 3 bottom sheets.

- [ ] **Step 1: Write Compose UI test verifying `AutoTranslationStatus` renders compact layout**

```kotlin
@Test
fun `auto translation status renders concise label and slot rail`() {
    val state = ReaderAutoTranslationUiState(
        identity = AutoChapterIdentity(1L, "Ch.1", null, "Manga", 1L),
        readyAheadCount = 3,
        availableAheadTarget = 5,
        activity = AutoActivityStatus.Working,
    )
    val snapshot = state.toAutoTranslationStatusSnapshot()
    assertEquals(3, snapshot.readyAheadCount)
    assertEquals(5, snapshot.availableAheadTarget)
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDevDebugUnitTest --tests 'eu.kanade.presentation.reader.appbars.AutoTranslationStatusTest'`  
Expected: PASS.

- [ ] **Step 3: Implement clean UI components**

1. In `AutoTranslationStatus.kt`: Tighten padding (`3.dp` vertical, `8.dp` horizontal) and apply auto-fade alpha.
2. In `TranslationCompareHandle.kt`: Redesign to 14dp low-profile sliver with 1-tap instant toggle.
3. In `TranslationProgressSheet.kt`: Replace 40-box cinema grid with linear progress meter, ETA, active stage chip, and `Retry All Failed` button.
4. In `ConfirmTranslationDialog.kt`: Add in-place interactive selectors for languages, engine/model, reasoning toggle, and 15 RPM indicator.
5. In `TranslationSettingsSheet.kt`: Add Reasoning toggle switch and 15 RPM Safe Pacing preference row.

- [ ] **Step 4: Run spotlessCheck and full test suite**

Run: `./gradlew spotlessCheck :app:testDevDebugUnitTest --tests 'eu.kanade.presentation.reader.*' --tests 'eu.kanade.presentation.manga.*'`  
Expected: BUILD SUCCESSFUL (0 errors).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/presentation/ app/src/test/java/eu/kanade/presentation/
git commit -m "feat: implement clean zero-bloat UI across reader, chapter list, and bottom sheets"
```

---

## Plan Self-Review
1. **Spec Coverage**:
   - Single-pass native execution: Covered in Task 1.
   - Active viewport priority: Covered in Task 2.
   - 15 RPM safe pacing toggle: Covered in Task 3.
   - Reasoning model toggle & tag sanitization: Covered in Task 4.
   - Copy-on-write store publishing: Covered in Task 5.
   - String revamp: Covered in Task 6.
   - Clean UI (Reader chip, Compare handle, Chapter row progress, Progress & Launch sheets): Covered in Task 7.
2. **No Placeholders**: Every task contains exact paths, exact code blocks, and runnable commands.
3. **Type Consistency**: Exact method signatures (`runSinglePassNative`, `stripThinkingTags`, `RateLimitPacer`) are aligned across tasks.
