package eu.kanade.translation.engines.translator.fumeto

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Pure beam-search kernels ported from the fumetotranslate reference `run.py:_beam`. */
internal object FumetoBeamSearch {

    /** One surviving (non-EOS) expansion candidate of a line's beam frontier. */
    internal data class AliveCandidate(
        val score: Double,
        val beamIndex: Int,
        val tokenId: Int,
    )

    /** One EOS expansion: [seqLength] counts the tokens BEFORE the EOS token. */
    internal data class FinishedCandidate(
        val normalizedScore: Double,
        val beamIndex: Int,
        val seqLength: Int,
    )

    internal data class LineSelection(
        val alive: List<AliveCandidate>,
        val finished: List<FinishedCandidate>,
    )

    /**
     * Numerically stable log-softmax in double precision (the reference casts
     * logits to float64 before normalising).
     */
    fun logSoftmax(logits: FloatArray): DoubleArray {
        var maxLogit = Double.NEGATIVE_INFINITY
        for (logit in logits) if (logit.toDouble() > maxLogit) maxLogit = logit.toDouble()
        var sumExp = 0.0
        val shifted = DoubleArray(logits.size)
        for (i in logits.indices) {
            shifted[i] = logits[i] - maxLogit
            sumExp += exp(shifted[i])
        }
        val logSum = ln(sumExp)
        for (i in shifted.indices) shifted[i] -= logSum
        return shifted
    }

    /**
     * Reference per-line frontier expansion: candidates are every current
     * beam score plus its row's log-probs; the top `2 * width` are visited in
     * descending order — EOS candidates become [FinishedCandidate] (length
     * normalised by the tokens BEFORE eos), non-EOS fill `alive` up to [width].
     * Visiting stops once [width] alive candidates are collected, exactly like
     * the reference loop that breaks at `len(ns) == k`.
     *
     * @param beamLengths current token count per beam row of this line.
     */
    fun selectLine(
        beamLogProbs: List<DoubleArray>,
        beamScores: DoubleArray,
        beamLengths: IntArray,
        width: Int,
        eosId: Int,
        alpha: Double,
    ): LineSelection {
        val vocab = beamLogProbs.firstOrNull()?.size ?: 0

        // Global top 2*width == merge of per-row top 2*width: each row can
        // contribute at most 2*width entries to any global top 2*width, so a
        // per-row partial selection before merging is lossless.
        data class Candidate(val score: Double, val beamIndex: Int, val tokenId: Int)

        val merged = ArrayList<Candidate>(beamLogProbs.size * 2 * width)
        for ((beamIndex, lp) in beamLogProbs.withIndex()) {
            val base = beamScores[beamIndex]
            // partial top 2*width of this row by single pass bound
            val rowTop = ArrayList<Candidate>(2 * width)
            var rowWorst = Double.NEGATIVE_INFINITY
            for (tokenId in 0 until vocab) {
                val score = base + lp[tokenId]
                if (score > rowWorst || rowTop.size < 2 * width) {
                    rowTop.add(Candidate(score, beamIndex, tokenId))
                    rowTop.sortByDescending { it.score }
                    if (rowTop.size > 2 * width) rowTop.removeAt(rowTop.size - 1)
                    rowWorst = rowTop.last().score
                }
            }
            merged.addAll(rowTop)
        }
        merged.sortByDescending { it.score }

        val alive = ArrayList<AliveCandidate>(width)
        val finished = ArrayList<FinishedCandidate>(width)
        for (candidate in merged) {
            if (alive.size >= width) break
            if (candidate.tokenId == eosId) {
                val lengthBeforeEos = beamLengths[candidate.beamIndex]
                val normalized = candidate.score / max(1.0, (lengthBeforeEos + 1).toDouble()).pow(alpha)
                finished.add(FinishedCandidate(normalized, candidate.beamIndex, lengthBeforeEos))
            } else {
                alive.add(AliveCandidate(candidate.score, candidate.beamIndex, candidate.tokenId))
            }
        }
        return LineSelection(alive, finished)
    }

    /**
     * Reference stopping rule: the line is done when it already holds [width]
     * finished sequences and the best of them beats the best alive raw score
     * discounted by its future length potential.
     */
    fun isLineDone(
        width: Int,
        finishedCount: Int,
        bestFinished: Double,
        bestAliveScore: Double,
        step: Int,
        alpha: Double,
    ): Boolean {
        if (finishedCount < width) return false
        return bestFinished >= bestAliveScore / max(1.0, (step + 1).toDouble()).pow(alpha)
    }
}
