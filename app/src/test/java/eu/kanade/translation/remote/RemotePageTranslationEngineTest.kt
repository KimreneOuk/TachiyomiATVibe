package eu.kanade.translation.remote

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class RemotePageTranslationEngineTest {
    @Test
    fun `parses remote translation response as ready page`() {
        val page = RemotePageTranslationEngine.parseTranslateResponse(
            """
            {
              "protocol_version": ${PageTranslation.CURRENT_INPAINT_REVISION},
              "img_width": 1200,
              "img_height": 1800,
              "blocks": [
                {
                  "text": "src",
                  "translation": "translated",
                  "x": 100.0,
                  "y": 200.0,
                  "width": 80.0,
                  "height": 30.0,
                  "sym_height": 28.0,
                  "sym_width": 26.0,
                  "angle": 0.0,
                  "label": 1,
                  "direction": "LTR"
                }
              ],
              "inpaint_mask_boxes": [
                { "x1": 90, "y1": 195, "x2": 190, "y2": 235, "label": 1 }
              ]
            }
            """.trimIndent(),
        )

        page.translationStatus shouldBe StageStatus.READY
        page.ocrStatus shouldBe StageStatus.READY
        page.inpaintStatus shouldBe StageStatus.READY
        page.blocks.size shouldBe 1
        page.blocks.single().translation shouldBe "translated"
        page.inpaintMaskBoxes.size shouldBe 1
    }

    @Test
    fun `protocol mismatch requests companion update`() {
        val error = shouldThrow<RemotePageTranslationException> {
            RemotePageTranslationEngine.parseTranslateResponse(
                """
                {
                  "protocol_version": -1,
                  "img_width": 1,
                  "img_height": 1,
                  "blocks": [],
                  "inpaint_mask_boxes": []
                }
                """.trimIndent(),
            )
        }

        error.message shouldBe "Update the companion server"
    }

    @Test
    fun `backend page keys partition desktop and on device cache entries`() {
        val pageKey = "page-001.png"

        BackendPageKey.partition(pageKey, InferenceBackend.ON_DEVICE) shouldNotBe
            BackendPageKey.partition(pageKey, InferenceBackend.DESKTOP)
    }

    @Test
    fun `desktop unreachable constant matches ui fallback string`() {
        RemotePageTranslationException.DESKTOP_UNREACHABLE shouldBe "Desktop Server Unreachable"
    }
}
