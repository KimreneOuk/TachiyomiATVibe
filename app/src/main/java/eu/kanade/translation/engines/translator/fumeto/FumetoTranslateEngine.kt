package eu.kanade.translation.engines.translator.fumeto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.app.Application
import eu.kanade.translation.engines.runtime.onnx.OnnxModelStore
import eu.kanade.translation.engines.runtime.onnx.OnnxRuntimeProvider
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device Japanese→English translator running the fumetotranslate
 * encoder/decoder ONNX graphs (int8) on the CPU execution provider, in the
 * model's PAGE MODE: a page's text blocks are encoded together in reading
 * order and decoded with context via beam search, ported from the reference
 * `run.py`. Engine selection wiring lives in [eu.kanade.translation.engines.translator.StandardTranslatorKind].
 */
class FumetoTranslateEngine : TextTranslator {

    override val fromLang = TextRecognizerLanguage.JAPANESE
    override val toLang = TextTranslatorLanguage.ENGLISH

    // Resolved on first use, not at construction: EngineLane builds translators
    // eagerly at pipeline startup and the app context is only needed once the
    // model assets are actually deployed.
    private val modelStore: OnnxModelStore by lazy { OnnxModelStore(Injekt.get<Application>()) }

    @Volatile
    private var closed = false

    private val initMutex = Mutex()
    private val decodeMutex = Mutex()

    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: UnigramTokenizer? = null
    private var spmDecoder: SentencePieceDecoder? = null

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // Strict no-fallback like the other standard engines: a closed engine is
        // a real failure that must surface as a FAILED page, not silent blanks.
        if (closed) throw IllegalStateException("FumetoTranslate translator was closed/unavailable")
        ensureInitialized()
        decodeMutex.withLock {
            for ((_, page) in pages) {
                currentCoroutineContext().ensureActive()
                translatePageBlocks(page)
            }
        }
    }

    /** Sessions load lazily so pipeline startup stays free of the ~450 MB model. */
    private suspend fun ensureInitialized() {
        if (encoderSession != null) return
        initMutex.withLock {
            if (encoderSession != null) return
            val startedAt = System.nanoTime()
            val paths = modelStore.ensureFumetoTranslate()
            val loadedTokenizer = UnigramTokenizer.fromTsv(paths.encoderVocab.readText())
            val loadedSpm = SentencePieceDecoder.fromTsv(paths.spmPieces.readText())
            val encoderOpts = OnnxRuntimeProvider.createSessionOptions(useAccelerator = false) {
                it.setIntraOpNumThreads(THREAD_COUNT)
            }
            val decoderOpts = OnnxRuntimeProvider.createSessionOptions(useAccelerator = false) {
                it.setIntraOpNumThreads(THREAD_COUNT)
            }
            try {
                encoderSession = OnnxRuntimeProvider.environment.createSession(
                    paths.encoderModel.absolutePath,
                    encoderOpts,
                )
                decoderSession = OnnxRuntimeProvider.environment.createSession(
                    paths.decoderModel.absolutePath,
                    decoderOpts,
                )
            } catch (failure: Exception) {
                encoderSession?.close()
                decoderSession?.close()
                encoderSession = null
                decoderSession = null
                throw failure
            } finally {
                encoderOpts.close()
                decoderOpts.close()
            }
            tokenizer = loadedTokenizer
            spmDecoder = loadedSpm
            logcat(LogPriority.INFO) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                "FumetoTranslate ready in ${elapsedMs}ms (cpu, int8, threads=$THREAD_COUNT); " +
                    "encoder inputs=${encoderSession?.inputNames} outputs=${encoderSession?.outputNames}; " +
                    "decoder inputs=${decoderSession?.inputNames} outputs=${decoderSession?.outputNames}"
            }
        }
    }

    /**
     * Flattens the page's blocks into model lines in reading order, runs page
     * mode, and writes each line's translation back into its block.
     */
    private suspend fun translatePageBlocks(page: PageTranslation) {
        val loadedTokenizer = tokenizer ?: throw IllegalStateException("FumetoTranslate not initialized")
        val loadedSpm = spmDecoder ?: throw IllegalStateException("FumetoTranslate not initialized")

        val lines = ArrayList<String>()
        val linePlacements = ArrayList<LinePlacement>()
        page.blocks.forEachIndexed { blockIndex, block ->
            block.text.split("\n").forEachIndexed { lineSlot, line ->
                if (line.isNotBlank()) {
                    lines.add(line)
                    linePlacements.add(LinePlacement(blockIndex, lineSlot))
                }
            }
        }
        if (lines.isEmpty()) return

        val bodies = Array(lines.size) { lineIndex ->
            loadedTokenizer.encode(lines[lineIndex]).let { ids ->
                if (ids.size > MAX_LINE_TOKENS) ids.copyOf(MAX_LINE_TOKENS) else ids
            }
        }
        val translated = arrayOfNulls<String>(lines.size)
        val counts = IntArray(bodies.size) { bodies[it].size }
        for (window in FumetoPagePlanner.windows(counts, WINDOW_BUDGET)) {
            if (window.targetLo >= window.targetHi) continue
            currentCoroutineContext().ensureActive()
            decodeWindow(loadedSpm, bodies, window, translated)
        }

        val translationsByBlock = HashMap<Int, MutableMap<Int, String?>>()
        linePlacements.forEachIndexed { lineIndex, placement ->
            translationsByBlock.getOrPut(placement.blockIndex) { LinkedHashMap() }[placement.lineSlot] =
                translated[lineIndex]
        }
        page.blocks.forEachIndexed { blockIndex, block ->
            val slots = translationsByBlock[blockIndex] ?: return@forEachIndexed
            val splitLines = block.text.split("\n").toMutableList()
            for ((lineSlot, value) in slots) {
                if (lineSlot < splitLines.size && value != null) splitLines[lineSlot] = value
            }
            block.translation = splitLines.joinToString("\n")
        }
    }

    private suspend fun decodeWindow(
        loadedSpm: SentencePieceDecoder,
        bodies: Array<IntArray>,
        window: FumetoWindow,
        translated: Array<String?>,
    ) {
        val encoder = encoderSession ?: throw IllegalStateException("FumetoTranslate not initialized")
        val (lo, hi) = window.lo to window.hi
        var sourceLength = 1
        for (lineIndex in lo until hi) sourceLength += bodies[lineIndex].size + 1

        // <s> line1 <sep> line2 ... </s>; seg records the line each token sits on.
        val source = LongArray(sourceLength)
        val segment = IntArray(sourceLength)
        var cursor = 1
        source[0] = BOS_ID.toLong()
        segment[0] = 0
        for (lineIndex in lo until hi) {
            for (token in bodies[lineIndex]) {
                source[cursor] = token.toLong()
                segment[cursor] = lineIndex - lo
                cursor++
            }
            source[cursor] = (if (lineIndex < hi - 1) SEP_ID else EOS_ID).toLong()
            segment[cursor] = lineIndex - lo
            cursor++
        }
        // </s> belongs to the last line of the window.
        segment[cursor - 1] = hi - 1 - lo

        var inputIds: OnnxTensor? = null
        var attentionMask: OnnxTensor? = null
        var encoderResult: OrtSession.Result? = null
        try {
            inputIds = longTensor(source, longArrayOf(1L, sourceLength.toLong()))
            attentionMask = longTensor(
                LongArray(sourceLength) { 1L },
                longArrayOf(1L, sourceLength.toLong()),
            )
            encoderResult = encoder.run(
                mapOf(
                    "input_ids" to inputIds,
                    "attention_mask" to attentionMask,
                ),
            )
            val bias = encoderResult[0] as OnnxTensor
            val crossTensors = Array(DECODER_LAYERS * 2) { encoderResult[1 + it] as OnnxTensor }

            // rel[ti][k] clips (token line - decoded line) to ±REL_MAX, offset by REL_MAX.
            val relRows = Array(window.targetHi - window.targetLo) { targetIndex ->
                val decodedLine = window.targetLo + targetIndex - lo
                IntArray(sourceLength) { k ->
                    (segment[k] - decodedLine).coerceIn(-REL_MAX, REL_MAX) + REL_MAX
                }
            }

            val decoder = decoderSession ?: throw IllegalStateException("FumetoTranslate not initialized")

            // Targets decode in small chunks: beams of the whole page at once
            // would scale the self-attention KV caches with the line count.
            var chunkStart = window.targetLo
            while (chunkStart < window.targetHi) {
                val chunkEnd = minOf(chunkStart + DECODE_CHUNK_LINES, window.targetHi)
                val chunkRelRows = relRows.copyOfRange(
                    chunkStart - window.targetLo,
                    chunkEnd - window.targetLo,
                )
                decodeTargets(decoder, bias, crossTensors, sourceLength, chunkRelRows, loadedSpm, translated, chunkStart)
                chunkStart = chunkEnd
            }
        } finally {
            inputIds?.close()
            attentionMask?.close()
            encoderResult?.close()
        }
    }

    private suspend fun decodeTargets(
        decoder: OrtSession,
        bias: OnnxTensor,
        crossTensors: Array<OnnxTensor>,
        sourceLength: Int,
        relRows: Array<IntArray>,
        loadedSpm: SentencePieceDecoder,
        translated: Array<String?>,
        targetOffset: Int,
    ) {
        val targetCount = relRows.size
        // Per line: current beam frontier (1 beam at step 0, up to BEAM_WIDTH after).
        val lineBeams = Array(targetCount) { listOf(Beam(IntArray(0), 0.0)) }
        val finished = Array(targetCount) { ArrayList<Pair<Double, IntArray>>() }
        var alive = (0 until targetCount).toList()
        var pasts: Array<FloatArray>? = null

        for (step in 0 until MAX_DECODE_STEPS) {
            if (alive.isEmpty()) break
            currentCoroutineContext().ensureActive()

            val rowLines = ArrayList<Int>()
            for (lineIndex in alive) repeat(lineBeams[lineIndex].size) { rowLines.add(lineIndex) }
            val rows = rowLines.size
            val rowOffsetOfLine = HashMap<Int, Int>()
            var cursor = 0
            for (lineIndex in alive) {
                rowOffsetOfLine[lineIndex] = cursor
                cursor += lineBeams[lineIndex].size
            }

            val tokenBuffer = directLongBuffer(rows)
            for (row in 0 until rows) {
                val lineIndex = rowLines[row]
                val beam = lineBeams[lineIndex][row - rowOffsetOfLine.getValue(lineIndex)]
                tokenBuffer.put(row, beam.seq.lastOrNull()?.toLong() ?: BOS_ID.toLong())
            }
            val posBuffer = directLongBuffer(1)
            posBuffer.put(0, step.toLong())
            val relBuffer = directLongBuffer(rows * sourceLength)
            for (row in 0 until rows) {
                val relRow = relRows[rowLines[row]]
                for (k in 0 until sourceLength) relBuffer.put(row * sourceLength + k, relRow[k].toLong())
            }

            val tokenTensor = longTensor(tokenBuffer, longArrayOf(rows.toLong(), 1L))
            val posTensor = longTensor(posBuffer, longArrayOf(1L))
            val relTensor = longTensor(relBuffer, longArrayOf(rows.toLong(), sourceLength.toLong()))
            val selfPasts = arrayOfNulls<OnnxTensor>(DECODER_LAYERS * 2)
            val inputs = HashMap<String, OnnxTensor>()
            inputs["token"] = tokenTensor
            inputs["pos"] = posTensor
            inputs["mem_bias"] = bias
            inputs["rel"] = relTensor
            for (layer in 0 until DECODER_LAYERS) {
                inputs["cross_k$layer"] = crossTensors[layer * 2]
                inputs["cross_v$layer"] = crossTensors[layer * 2 + 1]
            }
            val pastLength = step // tokens decoded so far; empty at step 0
            try {
                for (layer in 0 until DECODER_LAYERS) {
                    val shape = longArrayOf(rows.toLong(), HEADS.toLong(), pastLength.toLong(), HEAD_DIM.toLong())
                    val kTensor = selfTensor(pasts, layer * 2, rows, pastLength)
                    val vTensor = selfTensor(pasts, layer * 2 + 1, rows, pastLength)
                    selfPasts[layer * 2] = kTensor
                    selfPasts[layer * 2 + 1] = vTensor
                    inputs["self_k$layer"] = kTensor
                    inputs["self_v$layer"] = vTensor
                }

                val stepResult = decoder.run(inputs)

                val logitsTensor = stepResult[0] as OnnxTensor
                val vocabSize = logitsTensor.info.shape[1].toInt()
                val logits = logitsTensor.floatBuffer
                val updatedPasts = Array(DECODER_LAYERS * 2) { layer ->
                    (stepResult[1 + layer] as OnnxTensor).floatBuffer
                }

                val nextBeams = HashMap<Int, List<Beam>>()
                val gatherRows = ArrayList<Int>()
                var offset = 0
                for (lineIndex in alive) {
                    val beamCount = lineBeams[lineIndex].size
                    val beamLogProbs = Array(beamCount) { beamIndex ->
                        val rowBase = (offset + beamIndex) * vocabSize
                        val rowLogits = FloatArray(vocabSize) { logits.get(rowBase + it) }
                        FumetoBeamSearch.logSoftmax(rowLogits)
                    }
                    val frontier = lineBeams[lineIndex]
                    val selection = FumetoBeamSearch.selectLine(
                        beamLogProbs.toList(),
                        DoubleArray(beamCount) { frontier[it].score },
                        IntArray(beamCount) { frontier[it].seq.size },
                        BEAM_WIDTH,
                        EOS_ID,
                        LENGTH_PENALTY,
                    )
                    for (candidate in selection.finished) {
                        finished[lineIndex].add(candidate.normalizedScore to frontier[candidate.beamIndex].seq)
                    }

                    val lineDone = if (selection.alive.isEmpty()) {
                        // Degenerate all-EOS frontier: finished holds the line's output.
                        finished[lineIndex].isNotEmpty()
                    } else {
                        val bestFinished = finished[lineIndex].maxOf { it.first }
                        val bestAlive = selection.alive.maxOf { it.score }
                        FumetoBeamSearch.isLineDone(
                            width = BEAM_WIDTH,
                            finishedCount = finished[lineIndex].size,
                            bestFinished = bestFinished,
                            bestAliveScore = bestAlive,
                            step = step,
                            alpha = LENGTH_PENALTY,
                        )
                    }

                    if (!lineDone) {
                        val expanded = selection.alive.map { candidate ->
                            Beam(
                                frontier[candidate.beamIndex].seq + candidate.tokenId,
                                candidate.score,
                            )
                        }
                        nextBeams[lineIndex] = expanded
                        for (candidate in selection.alive) gatherRows.add(offset + candidate.beamIndex)
                    }
                    offset += beamCount
                }

                if (nextBeams.isEmpty()) {
                    stepResult.close()
                    alive = emptyList()
                    break
                }

                // Beam rows follow the surviving beams of the still-alive lines.
                val pastFloats = HEADS * (step + 1) * HEAD_DIM
                val nextPasts = Array(DECODER_LAYERS * 2) { FloatArray(gatherRows.size * pastFloats) }
                for (layer in 0 until DECODER_LAYERS * 2) {
                    val source = updatedPasts[layer]
                    for ((destinationRow, sourceRow) in gatherRows.withIndex()) {
                        val sourceBase = sourceRow * pastFloats
                        for (i in 0 until pastFloats) {
                            nextPasts[layer][destinationRow * pastFloats + i] = source.get(sourceBase + i)
                        }
                    }
                }
                stepResult.close()

                pasts = nextPasts
                nextBeams.forEach { (lineIndex, beams) -> lineBeams[lineIndex] = beams }
                alive = alive.filter { it in nextBeams }
            } finally {
                tokenTensor.close()
                posTensor.close()
                relTensor.close()
                selfPasts.forEach { it?.close() }
            }
        }

        for (lineIndex in 0 until targetCount) {
            val bestSequence = if (finished[lineIndex].isNotEmpty()) {
                finished[lineIndex].maxByOrNull { it.first }!!.second
            } else {
                lineBeams[lineIndex].maxByOrNull { it.score }?.seq ?: IntArray(0)
            }
            translated[targetOffset + lineIndex] = loadedSpm.decode(bestSequence)
        }
    }

    /** One beam hypothesis: generated tokens plus its accumulated log score. */
    private data class Beam(val seq: IntArray, val score: Double)

    /** Maps a flattened model line back to (block index, split-line slot). */
    private data class LinePlacement(val blockIndex: Int, val lineSlot: Int)

    private fun selfTensor(
        pasts: Array<FloatArray>?,
        index: Int,
        rows: Int,
        pastLength: Int,
    ): OnnxTensor {
        val shape = longArrayOf(rows.toLong(), HEADS.toLong(), pastLength.toLong(), HEAD_DIM.toLong())
        val buffer = pasts?.get(index)?.let { FloatBuffer.wrap(it) } ?: emptyFloatBuffer()
        return OnnxTensor.createTensor(OnnxRuntimeProvider.environment, buffer, shape)
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(OnnxRuntimeProvider.environment, LongBuffer.wrap(data), shape)

    private fun longTensor(buffer: LongBuffer, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(OnnxRuntimeProvider.environment, buffer, shape)

    private fun directLongBuffer(size: Int): LongBuffer =
        ByteBuffer.allocateDirect(size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()

    private fun emptyFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun close() {
        if (closed) return
        closed = true
        encoderSession?.close()
        decoderSession?.close()
        encoderSession = null
        decoderSession = null
        tokenizer = null
        spmDecoder = null
    }

    private companion object {
        // Model constants from the release meta.json (int8 graphs).
        const val BOS_ID = 1
        const val EOS_ID = 2
        const val SEP_ID = 4
        const val REL_MAX = 5
        const val MAX_LINE_TOKENS = 126
        const val WINDOW_BUDGET = 256
        const val DECODER_LAYERS = 4
        const val HEADS = 12
        const val HEAD_DIM = 64
        const val BEAM_WIDTH = 4
        const val MAX_DECODE_STEPS = 128
        const val LENGTH_PENALTY = 1.0

        /**
         * Reference CPU configuration. Lines decode in chunks of four so the
         * beam KV caches stay near ~50 MB peak instead of growing with the
         * whole page's line count.
         */
        val THREAD_COUNT = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))
        const val DECODE_CHUNK_LINES = 4
    }
}
