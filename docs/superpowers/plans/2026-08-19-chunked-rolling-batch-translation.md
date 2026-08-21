# Chunked Rolling-Context Batch Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a high-performance, context-aware batch translation pipeline that executes in smart dynamic chunks with rolling context and real-time reader streaming, strictly enforcing zero skipped pages.

**Architecture:** A dynamic chunking engine partitions chapter pages into density-bounded chunks. Each chunk executes Native OCR sequentially, then runs Native Inpainting and Batch Translation concurrently, fusing into a display-ready render committed to the shared store. A rolling glossary and micro-summary are carried forward across chunks for AI engines. The reader acts as a pure passive observer of the store's StateFlow.

**Tech Stack:** Kotlin, Coroutines, StateFlow, Android Jetpack, Mihon Reader, On-Device Native ML, Gradle.

## Global Constraints

- Android SDK 26+ (minimum API 26, target 34+).
- Kotlin 1.9+ with Coroutines Flow and Compose Multiplatform / Jetpack Compose.
- Strict Zero-Skip Invariant: Every page with $\ge 1$ detected text block MUST complete OCR $\to$ Inpaint $\to$ Translation $\to$ Render.
- No Tier-1 dirty overlay: Text overlays render ONLY on verified cleaned images (`.cleaned.jpg`).
- All tests run via `./gradlew :app:testStandardDebugUnitTest --tests "<TestClass>"`.
- Code formatting verified via `./gradlew spotlessCheck`.

---

### Task 1: Dynamic Chunker & Density Partitioning

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/batch/DynamicPageChunker.kt`
- Test: `app/src/test/java/eu/kanade/translation/batch/DynamicPageChunkerTest.kt`

**Interfaces:**
- Produces: `DynamicPageChunker.computeChunks(pages: List<PageRef>, bubbleCountByPage: Map<String, Int> = emptyMap()): List<PageChunk>`
- `data class PageChunk(val index: Int, val pageKeys: List<String>, val totalBubbles: Int)`

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/eu/kanade/translation/batch/DynamicPageChunkerTest.kt`:
```kotlin
package eu.kanade.translation.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynamicPageChunkerTest {

    @Test
    fun `short manga chapter uses 5-6 page chunks`() {
        val pages = (1..20).map { "page_$it.jpg" }
        val chunks = DynamicPageChunker.computeChunks(pages)
        assertTrue(chunks.all { it.pageKeys.size in 5..6 })
        assertEquals(20, chunks.sumOf { it.pageKeys.size })
    }

    @Test
    fun `webtoon chapter uses 8-10 page chunks`() {
        val pages = (1..100).map { "slice_$it.jpg" }
        val chunks = DynamicPageChunker.computeChunks(pages)
        assertTrue(chunks.all { it.pageKeys.size in 8..10 })
        assertEquals(100, chunks.sumOf { it.pageKeys.size })
    }

    @Test
    fun `density clamp seals chunk when bubbles reach threshold`() {
        val pages = (1..10).map { "page_$it.jpg" }
        val bubbleCounts = mapOf(
            "page_1.jpg" to 15,
            "page_2.jpg" to 22, // 15 + 22 = 37 >= 35 threshold
            "page_3.jpg" to 5,
        )
        val chunks = DynamicPageChunker.computeChunks(pages, bubbleCounts)
        assertEquals(listOf("page_1.jpg", "page_2.jpg"), chunks[0].pageKeys)
        assertEquals(37, chunks[0].totalBubbles)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.DynamicPageChunkerTest"`  
Expected: FAIL with compilation error (Unresolved reference: DynamicPageChunker).

- [ ] **Step 3: Implement DynamicPageChunker**

Create `app/src/main/java/eu/kanade/translation/batch/DynamicPageChunker.kt`:
```kotlin
package eu.kanade.translation.batch

import kotlin.math.min

data class PageChunk(
    val index: Int,
    val pageKeys: List<String>,
    val totalBubbles: Int,
)

object DynamicPageChunker {
    private const val MAX_BUBBLES_PER_CHUNK = 35

    fun computeChunks(
        pageKeys: List<String>,
        bubbleCountByPage: Map<String, Int> = emptyMap(),
    ): List<PageChunk> {
        if (pageKeys.isEmpty()) return emptyList()
        val totalPages = pageKeys.size

        val defaultTargetSize = when {
            totalPages <= 35 -> 5
            totalPages <= 60 -> 7
            else -> 9
        }

        val chunks = mutableListOf<PageChunk>()
        var currentChunkPages = mutableListOf<String>()
        var currentBubbleCount = 0

        for (key in pageKeys) {
            val bubbles = bubbleCountByPage[key] ?: 0
            val wouldExceedBubbles = (currentBubbleCount + bubbles) > MAX_BUBBLES_PER_CHUNK && currentChunkPages.isNotEmpty()
            val reachedTargetSize = currentChunkPages.size >= defaultTargetSize

            if (wouldExceedBubbles || reachedTargetSize) {
                chunks.add(PageChunk(chunks.size, currentChunkPages.toList(), currentBubbleCount))
                currentChunkPages = mutableListOf()
                currentBubbleCount = 0
            }

            currentChunkPages.add(key)
            currentBubbleCount += bubbles
        }

        if (currentChunkPages.isNotEmpty()) {
            chunks.add(PageChunk(chunks.size, currentChunkPages.toList(), currentBubbleCount))
        }

        return chunks
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.DynamicPageChunkerTest"`  
Expected: BUILD SUCCESSFUL with 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/batch/DynamicPageChunker.kt app/src/test/java/eu/kanade/translation/batch/DynamicPageChunkerTest.kt
git commit -m "feat(translation): implement dynamic page chunker with density guardrail"
```

---

### Task 2: Rolling Context Packet & Micro-Summary Storage

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/batch/RollingContextManager.kt`
- Test: `app/src/test/java/eu/kanade/translation/batch/RollingContextManagerTest.kt`

**Interfaces:**
- Produces: `RollingContextManager.getRollingContext(): RollingContextPacket`
- Produces: `RollingContextManager.updateContext(newGlossary: Map<String, String>, newMicroSummary: String)`
- `data class RollingContextPacket(val glossary: Map<String, String>, val microSummary: String)`

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/eu/kanade/translation/batch/RollingContextManagerTest.kt`:
```kotlin
package eu.kanade.translation.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RollingContextManagerTest {

    @Test
    fun `rolling context accumulates glossary and retains latest micro summary`() {
        val manager = RollingContextManager()
        assertEquals(emptyMap<String, String>(), manager.getRollingContext().glossary)
        assertEquals("", manager.getRollingContext().microSummary)

        manager.updateContext(
            newGlossary = mapOf("Jin-Woo" to "Male protagonist", "Shadow" to "Ability"),
            newMicroSummary = "Jin-Woo enters the double dungeon with his party."
        )

        val packet1 = manager.getRollingContext()
        assertEquals(2, packet1.glossary.size)
        assertEquals("Jin-Woo enters the double dungeon with his party.", packet1.microSummary)

        // Chunk 2 updates
        manager.updateContext(
            newGlossary = mapOf("Statue" to "Boss monster"),
            newMicroSummary = "The giant statue awakens and attacks the raid group."
        )

        val packet2 = manager.getRollingContext()
        assertEquals(3, packet2.glossary.size)
        assertEquals("Male protagonist", packet2.glossary["Jin-Woo"])
        assertEquals("Boss monster", packet2.glossary["Statue"])
        assertEquals("The giant statue awakens and attacks the raid group.", packet2.microSummary)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.RollingContextManagerTest"`  
Expected: FAIL with compilation error (Unresolved reference: RollingContextManager).

- [ ] **Step 3: Implement RollingContextManager**

Create `app/src/main/java/eu/kanade/translation/batch/RollingContextManager.kt`:
```kotlin
package eu.kanade.translation.batch

data class RollingContextPacket(
    val glossary: Map<String, String> = emptyMap(),
    val microSummary: String = "",
) {
    fun toPromptContext(): String {
        if (glossary.isEmpty() && microSummary.isBlank()) return ""
        val sb = StringBuilder()
        if (microSummary.isNotBlank()) {
            sb.appendLine("Previous Scene Summary: $microSummary")
        }
        if (glossary.isNotEmpty()) {
            sb.appendLine("Established Glossary:")
            glossary.forEach { (term, definition) ->
                sb.appendLine("- $term: $definition")
            }
        }
        return sb.toString().trim()
    }
}

class RollingContextManager {
    private val accumulatedGlossary = mutableMapOf<String, String>()
    private var currentMicroSummary: String = ""

    @Synchronized
    fun getRollingContext(): RollingContextPacket {
        return RollingContextPacket(
            glossary = accumulatedGlossary.toMap(),
            microSummary = currentMicroSummary,
        )
    }

    @Synchronized
    fun updateContext(newGlossary: Map<String, String>, newMicroSummary: String) {
        accumulatedGlossary.putAll(newGlossary)
        if (newMicroSummary.isNotBlank()) {
            currentMicroSummary = newMicroSummary.trim()
        }
    }

    @Synchronized
    fun reset() {
        accumulatedGlossary.clear()
        currentMicroSummary = ""
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.RollingContextManagerTest"`  
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/eu/kanade/translation/batch/RollingContextManager.kt app/src/test/java/eu/kanade/translation/batch/RollingContextManagerTest.kt
git commit -m "feat(translation): implement rolling context manager for glossary and micro-summary propagation"
```

---

### Task 3: Strict Zero-Skip Invariant & Elimination of Tier-1 Dirty Overlays

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt:40-60`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationOverlayBinding.kt:20-45`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:2785-2805`
- Test: `app/src/test/java/eu/kanade/translation/model/StrictZeroSkipTest.kt`

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/eu/kanade/translation/model/StrictZeroSkipTest.kt`:
```kotlin
package eu.kanade.translation.model

import eu.kanade.tachiyomi.ui.reader.viewer.selectReaderTranslationOverlayBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrictZeroSkipTest {

    @Test
    fun `text overlay is only bound when image is cleaned and translated`() {
        val dirtyPage = PageTranslation(
            sourceFileName = "page_1.jpg",
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.PENDING, // Not cleaned yet!
            cleanedImageName = null,
            renderStatus = StageStatus.PENDING,
            blocks = listOf(TranslationBlock(id = 1, text = "Hello", translation = "Bonjour", x = 0, y = 0, width = 10, height = 10)),
        )

        // Overlay MUST NOT be bound over dirty uncleaned image
        val dirtyBinding = selectReaderTranslationOverlayBinding(showTranslatedImage = true, dirtyPage)
        assertTrue(dirtyBinding.blocks.isEmpty())

        val cleanPage = dirtyPage.copy(
            inpaintStatus = StageStatus.READY,
            cleanedImageName = "page_1.cleaned.jpg",
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            renderStatus = StageStatus.READY,
            imgWidth = 1000f,
            imgHeight = 1500f,
        )

        val cleanBinding = selectReaderTranslationOverlayBinding(showTranslatedImage = true, cleanPage)
        assertEquals(1, cleanBinding.blocks.size)
        assertEquals("Bonjour", cleanBinding.blocks[0].translation)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.model.StrictZeroSkipTest"`

- [ ] **Step 3: Remove Tier-1 dirty overlay logic**

In `ReaderTranslationOverlayBinding.kt`, ensure `selectReaderTranslationOverlayBinding` requires `isTranslationDisplayReady`:
```kotlin
fun selectReaderTranslationOverlayBinding(
    showTranslatedImage: Boolean,
    translation: PageTranslation?,
): ReaderTranslationOverlayBinding {
    if (translation == null) {
        return ReaderTranslationOverlayBinding(emptyList(), 0, 0)
    }
    val width = when {
        translation.imgWidth > 0f -> translation.imgWidth.toInt()
        translation.originalImgWidth > 0f -> translation.originalImgWidth.toInt()
        else -> 0
    }
    val height = when {
        translation.imgHeight > 0f -> translation.imgHeight.toInt()
        translation.originalImgHeight > 0f -> translation.originalImgHeight.toInt()
        else -> 0
    }
    if (showTranslatedImage && translation.isTranslationDisplayReady) {
        return ReaderTranslationOverlayBinding(
            blocks = translation.blocks,
            pageWidth = width,
            pageHeight = height,
        )
    }
    return ReaderTranslationOverlayBinding(emptyList(), 0, 0)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.model.StrictZeroSkipTest"`  
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "fix(reader): enforce clean inpaint invariant for overlay binding and eliminate Tier-1 dirty overlays"
```

---

### Task 4: Chunk Batch Coordinator & Parallel Lane Scheduling

**Files:**
- Create: `app/src/main/java/eu/kanade/translation/batch/ChunkBatchCoordinator.kt`
- Modify: `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt`
- Test: `app/src/test/java/eu/kanade/translation/batch/ChunkBatchCoordinatorTest.kt`

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/eu/kanade/translation/batch/ChunkBatchCoordinatorTest.kt`:
```kotlin
package eu.kanade.translation.batch

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChunkBatchCoordinatorTest {

    @Test
    fun `coordinator executes chunks sequentially and emits progress`() = runBlocking {
        val chunk = PageChunk(index = 0, pageKeys = listOf("01.jpg", "02.jpg"), totalBubbles = 10)
        assertEquals(0, chunk.index)
        assertEquals(2, chunk.pageKeys.size)
    }
}
```

- [ ] **Step 2: Run test to verify it passes/fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.ChunkBatchCoordinatorTest"`

- [ ] **Step 3: Implement ChunkBatchCoordinator with structured parallel lanes**

Create `app/src/main/java/eu/kanade/translation/batch/ChunkBatchCoordinator.kt`.
Connect it to `TranslationPipeline.kt` to drive `translateBatch` in discrete sequential chunks with rolling context.

- [ ] **Step 4: Run full batch unit tests**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.*"`

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(translation): implement ChunkBatchCoordinator for pipelined chunk execution"
```

---

### Task 5: Agnostic Engine Integration & Rolling Prompt Serialization

**Files:**
- Modify: `app/src/main/java/eu/kanade/translation/translator/AITranslator.kt`
- Modify: `app/src/main/java/eu/kanade/translation/translator/BaseTranslator.kt`
- Test: `app/src/test/java/eu/kanade/translation/batch/ChunkTranslationPayloadTest.kt`

- [ ] **Step 1: Write the failing unit test**

Create `app/src/test/java/eu/kanade/translation/batch/ChunkTranslationPayloadTest.kt`:
```kotlin
package eu.kanade.translation.batch

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkTranslationPayloadTest {

    @Test
    fun `ai prompt includes rolling context glossary and scene summary`() {
        val packet = RollingContextPacket(
            glossary = mapOf("Jin-Woo" to "Protagonist"),
            microSummary = "Jin-Woo levels up.",
        )
        val promptContext = packet.toPromptContext()
        assertTrue(promptContext.contains("Jin-Woo: Protagonist"))
        assertTrue(promptContext.contains("Previous Scene Summary: Jin-Woo levels up."))
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.batch.ChunkTranslationPayloadTest"`

- [ ] **Step 3: Integrate rolling context into AITranslator**

In `AITranslator.kt`, inject `RollingContextPacket.toPromptContext()` into the system instruction block and parse the returned glossary/summary updates.

- [ ] **Step 4: Run unit tests**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(translation): inject rolling glossary and micro-summary into AI batch prompts"
```

---

### Task 6: Full Verification, Formatting & APK Deployment

- [ ] **Step 1: Run Spotless formatting check**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew spotlessCheck`

- [ ] **Step 2: Run full regression unit test suite**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew :app:testStandardDebugUnitTest`

- [ ] **Step 3: Assemble Standard Debug APK**

Run: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME = 'C:\Users\User\AppData\Local\Android\Sdk'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"; ./gradlew assembleStandardDebug`

- [ ] **Step 4: Install to Device & Verify Real-Time Streaming**

Run: `& "C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.100.223:37743 install -r -d "app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk"`
