package eu.kanade.translation.coexistence

import android.graphics.Bitmap
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.translator.TextTranslatorLanguage
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.engines.vision.ocr.TextRecognizerLanguage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fakes installed only at the sanctioned externals of the
 * ONNX recognition/decode, provider HTTP transport, disk
 * render IO). Everything between the barriers is the real production graph.
 * The barriers suspend inside these fakes, so the harness never sleeps and
 * never polls.
 */
internal object FakeCoexistence {

    /**
     * Byte size reported by the stub bitmap. Larger than HeldBitmapRegistry's
     * 48 MB ceiling so the batch render path always spills the held bitmap and
     * reloads it through the real `loadPersistedCleanedBitmap` seam — which is
     * where the RENDER barrier lives.
     */
    const val STUB_BITMAP_BYTES: Int = 48 * 1024 * 1024 + 1

    /**
     * JVM-safe bitmap stand-in. An
     * Unsafe-allocated Bitmap, but the batch's HeldBitmapRegistry.holdCleaned
     * reads `byteCount` outside any try/catch, which throws on the unit-test
     * android.jar. A relaxed mockk Bitmap with a stubbed `byteCount` keeps the
     * real registry arithmetic working; every other Bitmap touchpoint in the
     * graph is recycle-guarded or behind the RenderColorEstimator shim.
     */
    fun stubBitmap(): Bitmap = mockk(relaxed = true) {
        every { byteCount } returns STUB_BITMAP_BYTES
    }

    /** One text block with OCR text, so the provider lane has real work. */
    fun textBlock(text: String): TranslationBlock = TranslationBlock(
        text = text,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    /** Fresh analyzed page as the fake batch analyze stage's product. */
    fun analyzedPage(pageKey: String): PageTranslation = PageTranslation(sourceFileName = pageKey).apply {
        ocrStatus = eu.kanade.translation.model.StageStatus.READY
        originalImgWidth = 100f
        originalImgHeight = 100f
        decodeSampleSize = 1
        blocks += textBlock("hello-$pageKey")
    }

    /** Decoded page as the fake decode stage's product (bitmap never drawn). */
    fun decodedPage(pageKey: String): DecodedPage = DecodedPage(
        bitmap = stubBitmap(),
        sampleSize = 1,
        originalWidth = 100,
        originalHeight = 100,
        decodeDecision = DecodeDecision(
            kind = DecodeDecisionKind.FULL,
            sampleSize = 1,
            rawBitmapBytes = 0L,
            sampledBitmapBytes = 0L,
            sourcePixels = 0L,
            sampledPixels = 0L,
            snapshot = TranslationMemoryBudget.snapshot(),
        ),
        sourceBytesSize = 4L,
        sourceFingerprint = "fp-$pageKey",
    )
}

/**
 * Fake [PageRecognitionEngine] under the real EngineLane — used by the
 * single-page (reader) path. Hosts the NATIVE_RELEASE barrier at the return
 * gate of the native recognition call.
 */
internal class FakeRecognitionEngine(
    private val barrier: CoexistenceBarrier,
) : PageRecognitionEngine {

    val analyzeCalls = AtomicInteger(0)
    val inpaintCalls = AtomicInteger(0)
    val closeCalls = AtomicInteger(0)

    private val cleaned = FakeCoexistence.stubBitmap()

    override suspend fun analyze(bitmap: Bitmap): PageTranslation {
        analyzeCalls.incrementAndGet()
        barrier.arrive(CoexistenceBarrier.BarrierPoint.NATIVE_RELEASE, CoexistenceBarrier.WILDCARD_PAGE)
        return PageTranslation().apply { blocks += FakeCoexistence.textBlock("hello-single") }
    }

    override suspend fun inpaint(bitmap: Bitmap, pageTranslation: PageTranslation): Bitmap? {
        inpaintCalls.incrementAndGet()
        return cleaned
    }

    override fun reclaimPooledMemory() {}

    override fun close() {
        closeCalls.incrementAndGet()
    }
}

/**
 * Cross-instance observation state shared by the primary
 * fake transport and every translator the epoch retry's REBUILD produces.
 * The §1.4.1a oracle "any second call landed on the REBUILT translator
 * instance" is only observable if call/serving evidence survives across
 * instances, and the §1.4.1b close-order oracle needs one merged event log.
 */
internal class SharedTransportState {
    val instanceCounter = AtomicInteger(0)

    /** Merged, real-time-ordered lifecycle events: call-start/call-end/close/abort per instance. */
    val eventLog = CopyOnWriteArrayList<String>()

    /** pageKey → instance ids, one entry per paid call in issue order. */
    val servingOrder = ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()

    /** Paid calls that ended by cancellation (never a close's business — drain-not-cancel). */
    val cancelledCalls = AtomicInteger(0)

    fun recordServing(pageKey: String, instanceId: Int) {
        servingOrder.computeIfAbsent(pageKey) { CopyOnWriteArrayList() }.add(instanceId)
    }
}

/**
 * Fake provider HTTP transport ([TextTranslator]) shared by the single-page
 * HTTP phase and the batch standard translation lane. Hosts the PROVIDER_START
 * / PROVIDER_END barriers and records the exactly-once paid-call oracle.
 * Sets `block.translation = "tr-" + text` on every block so
 * TranslationBlockValidation.applyTo lands READY.
 *
 * Per-page lane serialization (design-note determinism, no sleeps/polling):
 * production's remote lane overlaps the page's inpaint/publish with its
 * provider call, and both writers touch the same store page — with real
 * second-scale latencies the read-write windows never collide, but instant
 * fakes would make the collision a coin flip. The harness therefore enforces
 * the production-typical order per page:
 *   identity check → [signalTransportStarted] → native inpaint → cleaned
 *   publication ([waitForNativeStage] returns) → paid call → commit → render.
 *
 *  Engine-epoch support for the test fakes:
 *  - [instanceId] — every instance exposes an id so the epoch retry can prove
 *    the second paid call landed on the REBUILT translator;
 *  - [closedFlag]/[closedSignal] — models providers closing their executors/pools in
 *    `close()`): a call that resumes from its PROVIDER_START park after the
 *    fake was closed FAILS instead of silently completing, which is exactly
 *    the mid-call failure closeEngines inflicts on a racing page today;
 *  - [shared] — cross-instance evidence ([SharedTransportState]); null keeps
 *    existing per-instance behavior byte-identical for existing suites.
 */
internal class FakeTransportTranslator(
    private val barrier: CoexistenceBarrier,
    private val waitForNativeStage: suspend (String) -> Unit = {},
    private val signalTransportStarted: (String) -> Unit = {},
    internal val shared: SharedTransportState? = null,
) : TextTranslator {

    val instanceId: Int = shared?.instanceCounter?.incrementAndGet() ?: 0

    override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
    override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

    val callsByPage = ConcurrentHashMap<String, AtomicInteger>()
    val closeCalls = AtomicInteger(0)

    val closedFlag = AtomicBoolean(false)
    val closedSignal = CompletableDeferred<Unit>()

    fun callsFor(pageKey: String): Int = callsByPage[pageKey]?.get() ?: 0

    fun totalCalls(): Int = callsByPage.values.sumOf { it.get() }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            pages.forEach { (pageKey, page) ->
                println("DBG transport call pageKey=$pageKey inst=$instanceId")
                // The batch identity check in BatchLaneWorkers.translate has passed
                // by this point; only now may the native lane's cleaned publication
                // for this page land.
                signalTransportStarted(pageKey)
                waitForNativeStage(pageKey)
                // The paid call begins before the start gate parks the lane.
                callsByPage.computeIfAbsent(pageKey) { AtomicInteger(0) }.incrementAndGet()
                shared?.recordServing(pageKey, instanceId)
                shared?.eventLog?.add("call-start:$pageKey:inst$instanceId")
                barrier.arrive(CoexistenceBarrier.BarrierPoint.PROVIDER_START, pageKey)
                if (closedFlag.get()) {
                    shared?.eventLog?.add("call-aborted-closed:$pageKey:inst$instanceId")
                    throw IllegalStateException(
                        "fake transport closed mid-call (inst$instanceId pageKey=$pageKey)",
                    )
                }
                page.blocks.forEach { block -> block.translation = "tr-" + block.text }
                barrier.arrive(CoexistenceBarrier.BarrierPoint.PROVIDER_END, pageKey)
                shared?.eventLog?.add("call-end:$pageKey:inst$instanceId")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            shared?.cancelledCalls?.incrementAndGet()
            throw e
        }
    }

    override fun close() {
        closeCalls.incrementAndGet()
        closedFlag.set(true)
        shared?.eventLog?.add("close:inst$instanceId")
        closedSignal.complete(Unit)
    }
}
