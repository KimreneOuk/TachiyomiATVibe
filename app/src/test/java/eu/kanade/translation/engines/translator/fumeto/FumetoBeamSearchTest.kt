package eu.kanade.translation.engines.translator.fumeto

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Pins the beam kernels against reference-computed vectors: log-softmax in
 * float64, the top-2k frontier visit order that breaks once `width` alive
 * candidates are collected (EOS candidates visited before the break still
 * finish, length-normalised by the tokens BEFORE eos), and the stopping rule.
 */
class FumetoBeamSearchTest {

    @Test
    fun `logSoftmax matches float64 reference values`() {
        val logits = floatArrayOf(1.5f, -0.5f, 2.0f, 0.25f, -1.0f, 3.0f, 0.75f, -2.0f)
        val expected = doubleArrayOf(
            -2.096409192942,
            -4.096409192942,
            -1.596409192942,
            -3.346409192942,
            -4.596409192942,
            -0.596409192942,
            -2.846409192942,
            -5.596409192942,
        )

        val actual = FumetoBeamSearch.logSoftmax(logits)

        actual.size shouldBe expected.size
        expected.indices.forEach { i ->
            check(abs(actual[i] - expected[i]) < 1e-9) { "row $i: ${actual[i]} vs ${expected[i]}" }
        }
    }

    @Test
    fun `single beam frontier finishes eos and fills alive in score order`() {
        val lp = doubleArrayOf(
            -2.096409192942,
            -4.096409192942,
            -1.596409192942,
            -3.346409192942,
            -4.596409192942,
            -0.596409192942,
            -2.846409192942,
            -5.596409192942,
        )

        val selection = FumetoBeamSearch.selectLine(
            beamLogProbs = listOf(lp),
            beamScores = doubleArrayOf(0.0),
            beamLengths = intArrayOf(0),
            width = 4,
            eosId = 5,
            alpha = 1.0,
        )

        // eos id 5 has the highest log-prob: it finishes (length 0 → /1).
        selection.finished shouldBe listOf(FumetoBeamSearch.FinishedCandidate(-0.596409192942, 0, 0))
        selection.alive.map { it.tokenId } shouldBe listOf(2, 0, 6, 3)
        selection.alive.map { it.score } shouldBe listOf(
            -1.596409192942,
            -2.096409192942,
            -2.846409192942,
            -3.346409192942,
        )
    }

    @Test
    fun `two-beam frontier normalises finished scores by pre-eos length`() {
        // Case generated against the reference loop: two beams with different
        // lengths; eos candidates of BOTH beams finish before alive fills up.
        val lp0 = doubleArrayOf(-2.9, -1.1, -1.7, -2.4, -3.5, -0.3, -3.9, -4.4)
        val lp1 = doubleArrayOf(-4.1, -3.0, -1.2, -3.3, -1.8, -0.9, -2.2, -4.6)

        val selection = FumetoBeamSearch.selectLine(
            beamLogProbs = listOf(lp0, lp1),
            beamScores = doubleArrayOf(-4.2, -2.0),
            beamLengths = intArrayOf(3, 1),
            width = 4,
            eosId = 5,
            alpha = 1.0,
        )

        selection.finished shouldBe listOf(
            // beam 1 eos: (-2.0 + -0.9) / max(1, 1+1) = -1.45
            FumetoBeamSearch.FinishedCandidate(-1.45, 1, 1),
            // beam 0 eos: (-4.2 + -0.3) / max(1, 3+1) = -1.125
            FumetoBeamSearch.FinishedCandidate(-1.125, 0, 3),
        )
        selection.alive.map { (it.score to it.beamIndex) to it.tokenId } shouldBe listOf(
            (-3.2 to 1) to 2,
            (-3.8 to 1) to 4,
            (-4.2 to 1) to 6,
            (-5.0 to 1) to 1,
        )
    }

    @Test
    fun `stopping rule requires a full finished frontier`() {
        val bestFinished = -1.0
        val bestAlive = -12.0

        // Fewer finished sequences than the width never completes the line.
        FumetoBeamSearch.isLineDone(width = 4, finishedCount = 3, bestFinished = 0.0, bestAliveScore = -100.0, step = 5, alpha = 1.0) shouldBe false
        // 4 finished at step 5: threshold is -12.0/6 = -2.0; -1.0 beats it → done.
        FumetoBeamSearch.isLineDone(width = 4, finishedCount = 4, bestFinished = bestFinished, bestAliveScore = bestAlive, step = 5, alpha = 1.0) shouldBe true
        // At step 0 the alive score is undiscounted (t+1 == 1).
        FumetoBeamSearch.isLineDone(width = 4, finishedCount = 4, bestFinished = -2.0, bestAliveScore = -1.0, step = 0, alpha = 1.0) shouldBe false
    }
}
