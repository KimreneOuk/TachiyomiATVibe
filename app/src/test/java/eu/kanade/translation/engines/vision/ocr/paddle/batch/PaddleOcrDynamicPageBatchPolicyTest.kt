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
