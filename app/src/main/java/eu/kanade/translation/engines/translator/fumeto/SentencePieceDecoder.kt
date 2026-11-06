package eu.kanade.translation.engines.translator.fumeto

import java.io.ByteArrayOutputStream

/**
 * SentencePiece decoder for the fumetotranslate English output vocab (16k).
 * Mirrors SentencePieceProcessor.decode: NORMAL/USER_DEFINED surfaces
 * concatenate with "▁"→" " applied per piece, consecutive BYTE pieces
 * accumulate raw UTF-8 bytes, CONTROL pieces emit nothing, and the leading
 * space of a metaspace-prefixed FIRST piece (the dummy prefix) is stripped.
 * Unknown surfaces (" ⁇ ") and raw byte runs keep their leading spaces.
 */
class SentencePieceDecoder private constructor(
    private val pieces: List<String>,
    private val types: IntArray,
) {
    fun decode(ids: IntArray): String {
        val text = StringBuilder()
        val byteRun = ByteArrayOutputStream()
        var stripLeadingSpace = false
        var firstPieceSeen = false

        for (id in ids) {
            if (id < 0 || id >= types.size) continue
            val type = types[id]
            if (type == TYPE_CONTROL || type == TYPE_UNUSED) continue
            if (!firstPieceSeen) {
                firstPieceSeen = true
                stripLeadingSpace = (type == TYPE_NORMAL || type == TYPE_USER_DEFINED) &&
                    pieces[id].startsWith(METASPACE_STRING)
            }
            when (type) {
                TYPE_BYTE -> byteRun.write(pieceByte(id))
                TYPE_NORMAL, TYPE_USER_DEFINED -> {
                    flushByteRun(byteRun, text)
                    text.append(pieces[id].replace(METASPACE_STRING, " "))
                }
                TYPE_UNKNOWN -> {
                    flushByteRun(byteRun, text)
                    text.append(UNKNOWN_SURFACE)
                }
            }
        }
        flushByteRun(byteRun, text)

        var result = text.toString()
        if (stripLeadingSpace && result.startsWith(" ")) result = result.substring(1)
        return result
    }

    private fun flushByteRun(byteRun: ByteArrayOutputStream, text: StringBuilder) {
        if (byteRun.size() == 0) return
        text.append(byteRun.toString("UTF-8"))
        byteRun.reset()
    }

    private fun pieceByte(id: Int): Int {
        val piece = pieces[id]
        require(piece.length == 6 && piece.startsWith("<0x") && piece.endsWith(">")) {
            "BYTE piece id $id has unexpected surface '$piece'"
        }
        return piece.substring(3, 5).toInt(16)
    }

    companion object {
        private const val TYPE_NORMAL = 1
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_CONTROL = 3
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_UNUSED = 5
        private const val TYPE_BYTE = 6

        private const val METASPACE_STRING = "▁"
        private const val UNKNOWN_SURFACE = " ⁇ "

        /**
         * Loads the converted pieces TSV (one `piece<TAB>type` line per id, as
         * produced by scripts/converters/extract_spm_pieces.py).
         */
        fun fromTsv(text: String): SentencePieceDecoder {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            require(lines.isNotEmpty()) { "SPM pieces TSV is empty" }
            val pieces = ArrayList<String>(lines.size)
            val types = IntArray(lines.size)
            lines.forEachIndexed { index, line ->
                val separator = line.lastIndexOf('\t')
                require(separator > 0) { "SPM pieces line $index is not piece<TAB>type" }
                pieces.add(line.substring(0, separator))
                val type = line.substring(separator + 1).toInt()
                require(type in TYPE_NORMAL..TYPE_BYTE) { "SPM pieces line $index has invalid type $type" }
                types[index] = type
            }
            return SentencePieceDecoder(pieces, types)
        }
    }
}
