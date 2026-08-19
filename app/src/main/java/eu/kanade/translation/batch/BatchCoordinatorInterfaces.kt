package eu.kanade.translation.batch

/**
 * TachiyomiAT: testable batch coordinator interfaces.
 */

data class OcrReadyPageRef(
    val pageKey: String,
    val pageIndex: Int,
    val generation: Long,
    val blockFingerprints: List<String>,
)

interface NativeLaneWorker {
    /**
     * Decode + OCR + persist OCR results. Returns the reference for translation,
     * or null if the page should be skipped. Does NOT hold native resources.
     */
    suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef?

    /**
     * Run inpaint on the page. Decodes the image itself.
     */
    suspend fun runInpaintStage(pageKey: String)
}

interface TranslatorLaneWorker {
    /** Translate one page's Pass-1 work item. */
    suspend fun translate(ref: OcrReadyPageRef)
}

interface ChunkTranslatorLaneWorker : TranslatorLaneWorker {
    /** Translate a whole chunk's Pass-1 work items with rolling context. */
    suspend fun translateChunk(
        chunk: PageChunk,
        refs: List<OcrReadyPageRef>,
        rollingContext: RollingContextPacket,
    ): RollingContextPacket? = null

    override suspend fun translate(ref: OcrReadyPageRef) {}
}

interface RenderJoinWorker {
    fun onNativeBranchDone(pageKey: String)
    fun onTranslationBranchDone(pageKey: String)
    suspend fun awaitAndRender(pageKey: String)
}

open class BatchScheduleListener {
    open fun ocrStarted(pageKey: String) {}
    open fun ocrFinished(pageKey: String) {}
    open fun ocrPublished(pageKey: String) {}
    open fun inpaintStarted(pageKey: String) {}
    open fun inpaintFinished(pageKey: String) {}
    open fun translationRequested(pageKey: String) {}
    open fun translationFinished(pageKey: String) {}
    open fun renderStarted(pageKey: String) {}
    open fun renderFinished(pageKey: String) {}
    open fun allOcrBarrierReleased() {}
    open fun pass1BarrierReleased() {}
    open fun pass2Started() {}

    companion object {
        val NOOP: BatchScheduleListener = BatchScheduleListener()
    }
}
