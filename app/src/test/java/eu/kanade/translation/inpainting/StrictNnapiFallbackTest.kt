package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StrictNnapiFallbackTest {
    private val rejected = StrictNnapiFallback.Candidate.Rejected(
        AotOutputGuard.GuardStats(128.0, 0.0, 0.0, 16),
    )

    @Test fun `accepted NNAPI skips XNNPACK and is never blended`() {
        var baselineCalls = 0
        val candidate = intArrayOf(1, 2)
        val result = StrictNnapiFallback.run(
            useNnapi = true,
            nnapi = { StrictNnapiFallback.Candidate.Accepted(candidate) },
            xnnpack = {
                baselineCalls++
                StrictNnapiFallback.Candidate.Accepted(intArrayOf(9))
            },
        )
        result.route shouldBe StrictNnapiFallback.Route.NNAPI
        result.value shouldBe candidate
        baselineCalls shouldBe 0
    }

    @Test fun `NNAPI rejection reruns identical baseline attempt and returns baseline only`() {
        var nnapiCalls = 0
        var baselineCalls = 0
        val baseline = intArrayOf(3, 4)
        val result = StrictNnapiFallback.run(
            useNnapi = true,
            nnapi = { nnapiCalls++; rejected },
            xnnpack = { baselineCalls++; StrictNnapiFallback.Candidate.Accepted(baseline) },
        )
        result.route shouldBe StrictNnapiFallback.Route.XNNPACK
        result.value shouldBe baseline
        nnapiCalls shouldBe 1
        baselineCalls shouldBe 1
    }

    @Test fun `NNAPI exception disables health and reruns XNNPACK`() {
        val failure = IllegalStateException("native")
        var disabledWith: Throwable? = null
        val result = StrictNnapiFallback.run(
            useNnapi = true,
            nnapi = { throw failure },
            xnnpack = { StrictNnapiFallback.Candidate.Accepted(7) },
            onNnapiNativeFailure = { disabledWith = it },
        )
        result.route shouldBe StrictNnapiFallback.Route.XNNPACK
        result.value shouldBe 7
        disabledWith shouldBe failure
    }

    @Test fun `both reject selects push pull and shared rejection is not mismatch`() {
        var shared = 0
        var mismatch = 0
        val result = StrictNnapiFallback.run(
            useNnapi = true,
            nnapi = { rejected },
            xnnpack = { rejected },
            onSharedRejection = { shared++ },
            onNnapiOnlyMismatch = { mismatch++ },
        )
        result.route shouldBe StrictNnapiFallback.Route.PUSH_PULL
        result.value shouldBe null
        shared shouldBe 1
        mismatch shouldBe 0
    }

    @Test fun `NNAPI-only rejection is the sole mismatch decision`() {
        var mismatch = 0
        StrictNnapiFallback.run(
            useNnapi = true,
            nnapi = { rejected },
            xnnpack = { StrictNnapiFallback.Candidate.Accepted(1) },
            onNnapiOnlyMismatch = { mismatch++ },
        )
        mismatch shouldBe 1
    }

    @Test fun `disabled candidate goes directly to mandatory XNNPACK without recursion`() {
        var nnapiCalls = 0
        var baselineCalls = 0
        val result = StrictNnapiFallback.run(
            useNnapi = false,
            nnapi = { nnapiCalls++; StrictNnapiFallback.Candidate.Accepted(1) },
            xnnpack = { baselineCalls++; StrictNnapiFallback.Candidate.Failed(IllegalStateException()) },
        )
        result.route shouldBe StrictNnapiFallback.Route.PUSH_PULL
        nnapiCalls shouldBe 0
        baselineCalls shouldBe 1
    }
}
