package eu.kanade.translation.inpainting.aot

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class AotFallbackCoordinatorTest {

    @Test
    fun `accepted primary skips fallback`() {
        val primaryPixels = intArrayOf(1, 2, 3)
        var fallbackCalls = 0

        val result = AotFallbackCoordinator.run(
            primary = { AotFallbackCoordinator.CandidateResult.Accepted(primaryPixels) },
            fallback = {
                fallbackCalls++
                AotFallbackCoordinator.CandidateResult.Failed(IllegalStateException("must not run"))
            },
        )

        result shouldBe AotFallbackCoordinator.Result.Accepted(
            primaryPixels,
            AotFallbackCoordinator.Attempt.PRIMARY,
        )
        fallbackCalls shouldBe 0
    }

    @Test
    fun `rejected primary accepts fallback`() {
        val fallbackPixels = intArrayOf(4, 5, 6)
        val rejectedStats = AotOutputGuard.GuardStats(128.0, 0.0, 0.0, 16)

        val result = AotFallbackCoordinator.run(
            primary = { AotFallbackCoordinator.CandidateResult.Rejected(rejectedStats) },
            fallback = { AotFallbackCoordinator.CandidateResult.Accepted(fallbackPixels) },
        )

        result shouldBe AotFallbackCoordinator.Result.Accepted(
            fallbackPixels,
            AotFallbackCoordinator.Attempt.FALLBACK,
        )
    }

    @Test
    fun `failed primary accepts fallback`() {
        val fallbackPixels = intArrayOf(7, 8, 9)

        val result = AotFallbackCoordinator.run(
            primary = { AotFallbackCoordinator.CandidateResult.Failed(IllegalStateException("primary")) },
            fallback = { AotFallbackCoordinator.CandidateResult.Accepted(fallbackPixels) },
        )

        result shouldBe AotFallbackCoordinator.Result.Accepted(
            fallbackPixels,
            AotFallbackCoordinator.Attempt.FALLBACK,
        )
    }

    @Test
    fun `primary OOM skips fallback allocation`() {
        var fallbackCalls = 0

        val result = AotFallbackCoordinator.run(
            primary = { AotFallbackCoordinator.CandidateResult.Failed(OutOfMemoryError("fixed")) },
            fallback = {
                fallbackCalls++
                AotFallbackCoordinator.CandidateResult.Accepted(intArrayOf(1))
            },
        )

        fallbackCalls shouldBe 0
        val exhausted = result.shouldBeInstanceOf<AotFallbackCoordinator.Result.Exhausted>()
        exhausted.fallback shouldBe AotFallbackCoordinator.CandidateResult.Skipped("primary_oom")
    }

    @Test
    fun `two unsuccessful attempts preserve both results`() {
        val primary = AotFallbackCoordinator.CandidateResult.Failed(IllegalStateException("primary"))
        val fallback = AotFallbackCoordinator.CandidateResult.Rejected(
            AotOutputGuard.GuardStats(255.0, 0.0, 0.0, 16),
        )

        AotFallbackCoordinator.run(
            primary = { primary },
            fallback = { fallback },
        ) shouldBe AotFallbackCoordinator.Result.Exhausted(primary, fallback)
    }

    @Test
    fun `guard inspects crop back rather than padded border`() {
        val sourceSize = 1
        val paddedCandidate = IntArray(PADDED_PIXELS) { TEXTURED_BORDER }
        val paddedMask = IntArray(PADDED_PIXELS) { 0 }
        val offset = AotPadPath.centeredOffset(sourceSize)
        val centerIndex = offset * AotPadPath.SIZE + offset
        paddedCandidate[centerIndex] = UNIFORM_GRAY
        paddedMask[centerIndex] = 0xFFFFFFFF.toInt()
        val candidateDestination = IntArray(2) { GUARD_PIXEL }
        val maskDestination = IntArray(2) { GUARD_PIXEL }

        val result = AotFallbackCoordinator.inspectCroppedCandidate(
            paddedCandidate,
            paddedMask,
            sourceSize,
            candidateDestination,
            maskDestination,
        )

        result.shouldBeInstanceOf<AotFallbackCoordinator.CandidateResult.Accepted>()
        candidateDestination[0] shouldBe UNIFORM_GRAY
        maskDestination[0] shouldBe 0xFFFFFFFF.toInt()
        candidateDestination[1] shouldBe GUARD_PIXEL
        maskDestination[1] shouldBe GUARD_PIXEL
    }

    @Test
    fun `guard rejects suspicious pixels after crop back`() {
        val sourceSize = 4
        val sourcePixels = sourceSize * sourceSize
        val paddedCandidate = IntArray(PADDED_PIXELS) { TEXTURED_BORDER }
        val paddedMask = IntArray(PADDED_PIXELS)
        val uniformCandidate = IntArray(sourcePixels) { UNIFORM_GRAY }
        val mask = IntArray(sourcePixels) { 0xFFFFFFFF.toInt() }
        AotPadPath.padSquareInto(uniformCandidate, sourceSize, TEXTURED_BORDER, paddedCandidate)
        AotPadPath.padSquareInto(mask, sourceSize, 0, paddedMask)

        val result = AotFallbackCoordinator.inspectCroppedCandidate(
            paddedCandidate,
            paddedMask,
            sourceSize,
            IntArray(sourcePixels),
            IntArray(sourcePixels),
        )

        val rejected = result.shouldBeInstanceOf<AotFallbackCoordinator.CandidateResult.Rejected>()
        rejected.stats.maskedCount shouldBe sourcePixels
    }

    private companion object {
        const val PADDED_PIXELS = AotPadPath.SIZE * AotPadPath.SIZE
        val TEXTURED_BORDER = 0xFF123456.toInt()
        val UNIFORM_GRAY = 0xFF808080.toInt()
        const val GUARD_PIXEL = 0x7F55AA33
    }
}
