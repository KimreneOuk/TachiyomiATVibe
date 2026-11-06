package eu.kanade.translation.engines.translator.fumeto

/**
 * Unigram-LM tokenizer matching HuggingFace `Tokenizer.from_file` for the
 * fumetotranslate encoder vocab (sbintuitions/modernbert-ja-310m derived).
 *
 * Faithful to the reference pipeline: Metaspace normalisation replaces " " with
 * "▁" (prepend never), the Viterbi lattice maximises summed piece scores, and
 * characters covered by no piece emit one byte-fallback token per UTF-8 byte.
 * Added-token pre-extraction is not replicated: every special string is also a
 * score-0.0 vocab piece, so the lattice picks it whenever the exact string
 * appears — the only inputs where the two can differ are texts containing a
 * literal special string, which manga dialogue does not produce.
 */
class UnigramTokenizer private constructor(
    private val pieces: List<String>,
    private val scores: DoubleArray,
    private val unkId: Int,
) {
    private val pieceToId = HashMap<String, Int>(pieces.size * 2)
    private val maxPieceChars = pieces.maxOf { it.length }
    private val minScore = scores.min()
    private val unkScore = minScore - UNK_PENALTY

    init {
        pieces.forEachIndexed { id, piece -> pieceToId[piece] = id }
    }

    /** Encodes [text] without special tokens; the engine applies truncation itself. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val metaspace = text.replace(' ', METASPACE_REPLACEMENT)
        val length = metaspace.length

        // Forward Viterbi: bestScore[end] = max summed score of any segmentation
        // of metaspace[0, end). bestId/bestBegin record the winning last node.
        val bestScore = DoubleArray(length + 1) { Double.NEGATIVE_INFINITY }
        val bestId = IntArray(length + 1)
        val bestBegin = IntArray(length + 1)
        bestScore[0] = 0.0

        var index = 0
        while (index < length) {
            val current = bestScore[index]
            var matched = false
            val maxEnd = minOf(index + maxPieceChars, length)
            for (end in index + 1..maxEnd) {
                val id = pieceToId[metaspace.substring(index, end)]
                if (id != null) {
                    val candidate = current + scores[id]
                    if (candidate > bestScore[end]) {
                        bestScore[end] = candidate
                        bestId[end] = id
                        bestBegin[end] = index
                    }
                    matched = true
                }
            }
            if (!matched) {
                // One unknown CHARACTER per node; expanded to byte tokens below.
                val isSurrogatePair = Character.isHighSurrogate(metaspace[index]) &&
                    index + 1 < length &&
                    Character.isLowSurrogate(metaspace[index + 1])
                val charEnd = index + if (isSurrogatePair) 2 else 1
                val candidate = current + unkScore
                if (candidate > bestScore[charEnd]) {
                    bestScore[charEnd] = candidate
                    bestId[charEnd] = unkId
                    bestBegin[charEnd] = index
                }
            }
            index++
        }

        // Backtrack the winning path, expanding unk nodes into byte tokens.
        // The list is built last-node-first, so a multi-byte unk group must be
        // appended reversed to survive the final list reversal.
        val reversed = ArrayList<Int>()
        var end = length
        while (end > 0) {
            val id = bestId[end]
            val begin = bestBegin[end]
            if (id == unkId) {
                val bytes = metaspace.substring(begin, end).toByteArray(Charsets.UTF_8)
                for (i in bytes.indices.reversed()) {
                    reversed.add(byteId(bytes[i].toInt() and 0xFF))
                }
            } else {
                reversed.add(id)
            }
            end = begin
        }
        return reversed.toIntArray().let { it.reversedArray() }
    }

    private fun byteId(byte: Int): Int = pieceToId[bytePiece(byte)] ?: unkId

    companion object {
        private const val UNK_PENALTY = 10.0
        private const val METASPACE_REPLACEMENT = '▁'

        private fun bytePiece(byte: Int) = "<0x" + byte.toString(16).uppercase().padStart(2, '0') + ">"

        /**
         * Loads the converted vocab TSV (one `piece<TAB>score` line per id, as
         * produced by scripts/converters/extract_unigram_vocab.py).
         */
        fun fromTsv(text: String, unkId: Int = 0): UnigramTokenizer {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            require(lines.isNotEmpty()) { "Unigram vocab TSV is empty" }
            val pieces = ArrayList<String>(lines.size)
            val scores = DoubleArray(lines.size)
            lines.forEachIndexed { index, line ->
                val separator = line.lastIndexOf('\t')
                require(separator > 0) { "Unigram vocab line $index is not piece<TAB>score" }
                pieces.add(line.substring(0, separator))
                scores[index] = line.substring(separator + 1).toDouble()
            }
            require(pieces[unkId].isNotEmpty()) { "Unigram vocab has no piece at unk id $unkId" }
            return UnigramTokenizer(pieces, scores, unkId)
        }
    }
}
