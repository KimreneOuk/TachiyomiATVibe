package eu.kanade.translation.coexistence

import android.graphics.Bitmap
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.pipeline.DecodedPage
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T917 Phase 1 fakes — installed ONLY at the sanctioned externals of the
 * design note §1.2 (ONNX recognition/decode, provider HTTP transport, disk
 * render IO). Everything between the barriers is the real production graph.
 * The barriers suspend inside these fakes, so the harness never sleeps and
 * never polls.
 */
internal object FakeCoexistence {

    /**
     * Byte size reported by the stub bitmap. Larger than HeldBitmapRegistry's
     * 48 MB ceiling so the batch render path always spills the held bitmap and
     * reloads it through the real `loadPersistedCleanedBitmap` seam — which is
     * where the RENDER barrier lives (design note §2).
     */
    const val STUB_BITMAP_BYTES: Int = 48 * 1024 * 1024 + 1

    /**
     * JVM-safe bitmap stand-in. The design note §1.2 proposed an
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
 * gate of the native recognition call (design note §2).
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
 * Fake provider HTTP transport ([TextTranslator]) shared by the single-page
 * HTTP phase and the batch standard translation lane. Hosts the PROVIDER_START
 * / PROVIDER_END barriers and records the exactly-once paid-call oracle.
 * Sets `block.translation = "tr-" + text` on every block so
 * TranslationBlockValidation.applyTo lands READY (design note §1.2.2).
 *
 * Per-page lane serialization (design-note determinism, no sleeps/polling):
 * production's remote lane overlaps the page's inpaint/publish with its
 * provider call, and both writers touch the same store page — with real
 * second-scale latencies the read-write windows never collide, but instant
 * fakes would make the collision a coin flip. The harness therefore enforces
 * the production-typical order per page:
 *   identity check → [signalTransportStarted] → native inpaint → cleaned
 *   publication ([waitForNativeStage] returns) → paid call → commit → render.
 */
internal class FakeTransportTranslator(
    private val barrier: CoexistenceBarrier,
    private val waitForNativeStage: suspend (String) -> Unit = {},
    private val signalTransportStarted: (String) -> Unit = {},
) : TextTranslator {

    override val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.JAPANESE
    override val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH

    val callsByPage = ConcurrentHashMap<String, AtomicInteger>()
    val closeCalls = AtomicInteger(0)

    fun callsFor(pageKey: String): Int = callsByPage[pageKey]?.get() ?: 0

    fun totalCalls(): Int = callsByPage.values.sumOf { it.get() }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        pages.forEach { (pageKey, page) ->
            println("DBG transport call pageKey=$pageKey")
            // The batch identity check in BatchLaneWorkers.translate has passed
            // by this point; only now may the native lane's cleaned publication
            // for this page land.
            signalTransportStarted(pageKey)
            waitForNativeStage(pageKey)
            // The paid call begins before the start gate parks the lane.
            callsByPage.computeIfAbsent(pageKey) { AtomicInteger(0) }.incrementAndGet()
            barrier.arrive(CoexistenceBarrier.BarrierPoint.PROVIDER_START, pageKey)
            page.blocks.forEach { block -> block.translation = "tr-" + block.text }
            barrier.arrive(CoexistenceBarrier.BarrierPoint.PROVIDER_END, pageKey)
        }
    }

    override fun close() {
        closeCalls.incrementAndGet()
    }
}
