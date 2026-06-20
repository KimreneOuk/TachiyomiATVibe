package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.RenderQuality
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the persist-decision used by [ChapterTranslationStore.updatePage].
 *
 * The stranded-page sweep writes a CANCELLED+errorMessage placeholder (no
 * blocks, no image) via updatePage on every chapter open. Persisting those
 * placeholders created on-disk entries that — under the old
 * `pages.isNotEmpty()` translated check — made chapters falsely read as fully
 * TRANSLATED forever after. shouldPersistUpdate now keeps such transient
 * placeholders in memory only, while still persisting durable progress
 * (rendered output, recognized blocks, cleaned image, failures, and the
 * rendered->cleared transition).
 */
class ChapterTranslationStorePersistTest {

    // Constructed with no file and no creator: shouldPersistUpdate is a pure
    // decision function and never touches the disk, so this is safe as long as
    // the test only calls shouldPersistUpdate (no updatePage/replaceAll, which
    // would try to persist and hit the null file via persistLocked).
    private val store = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = emptyMap(),
    )

    @Test
    fun `cancelled placeholder with error message but no content is not persisted`() {
        val placeholder = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            translationStatus = StageStatus.CANCELLED,
            inpaintStatus = StageStatus.CANCELLED,
            renderStatus = StageStatus.CANCELLED,
            errorMessage = "Page was stranded mid-translation; reset as cancelled on chapter reopen",
        )

        store.shouldPersistUpdate(previous = null, updated = placeholder) shouldBe false
    }

    @Test
    fun `cancelled placeholder replacing a cancelled placeholder is still not persisted`() {
        val placeholder = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            errorMessage = "stranded",
        )

        store.shouldPersistUpdate(previous = placeholder.copy(), updated = placeholder) shouldBe false
    }

    @Test
    fun `fresh pending page is not persisted`() {
        store.shouldPersistUpdate(previous = null, updated = PageTranslation()) shouldBe false
    }

    @Test
    fun `running page is not persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(ocrStatus = StageStatus.RUNNING),
        ) shouldBe false
    }

    @Test
    fun `failed page IS persisted`() {
        // Retry-exhaustion bookkeeping (hasExhaustedRetries) must survive a
        // reopen so the scheduler can skip permanently-failing pages.
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(
                ocrStatus = StageStatus.FAILED,
                errorMessage = "ONNX recognition failed",
            ),
        ) shouldBe true
    }

    @Test
    fun `page with recognized text blocks IS persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(
                blocks = mutableListOf(block()),
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
            ),
        ) shouldBe true
    }

    @Test
    fun `page with cleaned image IS persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(cleanedImageName = "001.cleaned.png"),
        ) shouldBe true
    }

    @Test
    fun `page with rendered image IS persisted`() {
        store.shouldPersistUpdate(
            previous = null,
            updated = PageTranslation(
                renderedImageName = "001.rendered.png",
                renderQuality = RenderQuality.FULL,
            ),
        ) shouldBe true
    }

    @Test
    fun `transition from rendered result to cleared IS persisted`() {
        // A forced retry clears the rendered image; the reader must stop showing
        // the stale one, so this transition must hit disk.
        val previousRendered = PageTranslation(
            renderedImageName = "001.rendered.png",
            renderQuality = RenderQuality.FULL,
        )
        val cleared = PageTranslation(
            ocrStatus = StageStatus.RUNNING,
            renderedImageName = null,
        )

        store.shouldPersistUpdate(previous = previousRendered, updated = cleared) shouldBe true
    }

    @Test
    fun `failed placeholder carries its error message onto disk`() {
        // Confirms error messages attached to real failures still persist now
        // that the standalone errorMessage rule was removed.
        val failed = PageTranslation(
            renderStatus = StageStatus.FAILED,
            errorMessage = "Could not save rendered image",
        )
        store.shouldPersistUpdate(previous = null, updated = failed) shouldBe true
    }

    /** Minimal block satisfying the non-default TranslationBlock geometry args. */
    private fun block() = TranslationBlock(
        text = "源",
        translation = "source",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )
}
