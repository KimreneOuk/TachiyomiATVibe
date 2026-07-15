package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.translator.TranslatorComputeClass

/**
 * TachiyomiAT: testable batch coordinator interfaces. The real
 * [eu.kanade.translation.TranslationPipeline] supplies heavy implementations
 * (ONNX OCR/inpaint, HTTP translate); tests supply fakes that record timing so
 * the schedule (lane serialization, overlap rules, channel backpressure,
 * Pass-1 barrier, final join) can be asserted deterministically without Android
 * or network.
 */

/**
 * Immutable work item produced by the native lane after OCR persistence. Offered
 * to the translation lane. Carries everything the translator + render join need
 * so no bitmap or native permit is held across the channel send.
 */
data class TranslationWorkItem(
    val pageKey: String,
    val pageIndex: Int,
    /** OCR result + inpaint status as of the offer time. */
    val translation: PageTranslation,
    /** True when the page was fully durable (skip-all) and needs no inpaint. */
    val skipInpaint: Boolean,
)

/**
 * Opaque handle returned by [NativeLaneWorker.runOcr] carrying the live native
 * resources (decoded bitmap, OCR state) needed by [NativeLaneWorker.runInpaint].
 * Keeps the coordinator free of bitmap/ONNX types so it stays testable.
 */
interface OcrResultHandle {
    val item: TranslationWorkItem
}

/**
 * Native lane abstraction: decode -> OCR -> persist -> (split) -> inpaint ->
 * publish cleaned. The split between [runOcr] and [runInpaint] lets the
 * coordinator offer the translation work item AFTER OCR publication and BEFORE
 * inpaint completes for REMOTE_IO overlap. Serialized: at most one
 * runOcr+runInpaint pair executes at a time.
 */
interface NativeLaneWorker {
    /**
     * Decode + OCR + persist OCR results. Returns the work item to offer the
     * translation lane plus an opaque handle for the subsequent inpaint, or null
     * when the page should be skipped (textless, already durable, or failed).
     */
    suspend fun runOcr(pageKey: String, pageIndex: Int): OcrResultHandle?

    /** Run inpaint on the handle's bitmap + persist the cleaned image. */
    suspend fun runInpaint(handle: OcrResultHandle)

    /**
     * Recycle the bitmap + release the native permit / leave the native lane.
     * Called by the coordinator AFTER inpaint and BEFORE any suspending channel
     * send so a full channel never suspends holding a bitmap + permit.
     */
    fun releaseNativeResources(pageKey: String)
}

/**
 * Translator lane abstraction: a single serialized provider lane. Receives the
 * work item after OCR and performs the Pass-1 translation. For REMOTE_IO it may
 * overlap same-page inpaint; for LOCAL_COMPUTE the native lane keeps it inline.
 */
interface TranslatorLaneWorker {
    /** Translate one page's Pass-1 work item. Updates translation in place. */
    suspend fun translate(item: TranslationWorkItem)
}

/**
 * Render join: per-page join of the translation result with its inpaint/render
 * prerequisites. Render starts only after BOTH the translation and the
 * inpaint/render branch are done for that page.
 */
interface RenderJoinWorker {
    /** Record that the native (inpaint/render-prereq) branch finished for [pageKey]. */
    fun onNativeBranchDone(pageKey: String)

    /** Record that the translation branch finished for [pageKey]. */
    fun onTranslationBranchDone(pageKey: String)

    /**
     * Block until both branches for [pageKey] are done, then perform the render.
     */
    suspend fun awaitAndRender(pageKey: String)
}

/**
 * Listener recording scheduling events for deterministic assertions. Tests pass
 * a recording listener; production passes a no-op. Default no-op methods so a
 * test subclass overrides only what it asserts.
 */
open class BatchScheduleListener {
    open fun nativeLaneEntered(pageKey: String) {}
    open fun nativeLaneLeft(pageKey: String) {}
    open fun ocrPublished(pageKey: String) {}
    open fun inpaintStarted(pageKey: String) {}
    open fun inpaintFinished(pageKey: String) {}
    open fun translationRequested(pageKey: String) {}
    open fun translationFinished(pageKey: String) {}
    open fun renderStarted(pageKey: String) {}
    open fun renderFinished(pageKey: String) {}
    open fun pass1BarrierReleased() {}
    open fun pass2Started() {}
    open fun channelSendSuspendedBeforeBitmapRelease(pageKey: String) {}

    companion object {
        /** Default no-op listener for production. */
        val NOOP: BatchScheduleListener = BatchScheduleListener()
    }
}
