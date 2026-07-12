package eu.kanade.translation.model

import kotlinx.serialization.json.Json
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the durability of the persisted inpaint mask across the store
 * serialize/deserialize cycle, plus the [hasCurrentInpaintMask] predicate that
 * the stage-1 resume gate reads.
 *
 * The mask is what makes detector-only + watermark regions still get erased
 * after a batch resume / store reopen (when the transient `allTextDetections`
 * is gone). If the field ever stops round-tripping, the resume hole returns.
 */
class InpaintMaskSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `inpaintMaskBoxes survives a serialize - deserialize round trip`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "源", translation = "source",
                    width = 30f, height = 40f, x = 25f, y = 35f,
                    symHeight = 4f, symWidth = 3f, angle = 0f,
                    parentX = 10f, parentY = 20f, parentWidth = 80f, parentHeight = 90f,
                ),
            ),
            ocrStatus = StageStatus.READY,
            inpaintMaskBoxes = listOf(
                InpaintMaskBox(10, 20, 90, 110, 0), // bubble
                InpaintMaskBox(25, 35, 55, 75, 1), // text
                InpaintMaskBox(100, 100, 130, 130, 2), // detector-only
            ),
        )

        val encoded = json.encodeToString(PageTranslation.serializer(), page)
        val decoded = json.decodeFromString(PageTranslation.serializer(), encoded)

        decoded.inpaintMaskBoxes.shouldContainExactly(
            listOf(
                InpaintMaskBox(10, 20, 90, 110, 0),
                InpaintMaskBox(25, 35, 55, 75, 1),
                InpaintMaskBox(100, 100, 130, 130, 2),
            ),
        )
    }

    @Test
    fun `InpaintMaskBox toIntArray preserves box coordinates and label separately`() {
        val box = InpaintMaskBox(5, 10, 15, 20, 2)

        box.toIntArray().toList() shouldBe listOf(5, 10, 15, 20)
        box.label shouldBe 2
    }

    @Test
    fun `page OCR'd by current code has a current mask`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "源", translation = "",
                    width = 10f, height = 10f, x = 0f, y = 0f,
                    symHeight = 1f, symWidth = 1f, angle = 0f,
                ),
            ),
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
        )

        page.hasCurrentInpaintMask shouldBe true
    }

    @Test
    fun `pre-fix page with empty mask does NOT have a current mask`() {
        // A page deserialized from a pre-fix translation file: inpaintMaskBoxes
        // defaulted to empty. The stage-1 resume gate must re-OCR it so a fresh,
        // complete mask is captured.
        val page = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "源", translation = "",
                    width = 10f, height = 10f, x = 0f, y = 0f,
                    symHeight = 1f, symWidth = 1f, angle = 0f,
                ),
            ),
            inpaintMaskBoxes = emptyList(),
        )

        page.hasCurrentInpaintMask shouldBe false
    }

    @Test
    fun `textless page is treated as having a current mask`() {
        // Nothing to erase; the resume gate must skip re-OCR instead of looping.
        val page = PageTranslation(blocks = mutableListOf())

        page.hasCurrentInpaintMask shouldBe true
    }
}
