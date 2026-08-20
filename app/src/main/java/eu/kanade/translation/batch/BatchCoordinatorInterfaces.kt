package eu.kanade.translation.batch

/**
 * TachiyomiAT: testable batch coordinator interfaces.
 */

/** Natural-order page identity retained by the live sequential coordinator. */
typealias PageKey = Pair<String, Int>

data class OcrReadyPageRef(
    val pageKey: String,
    val pageIndex: Int,
    val generation: Long,
    val blockFingerprints: List<String>,
    /** Exact batch lease token held across the translation boundary. */
    val leaseToken: Long? = null,
    /** Artifact candidate generation observed at OCR publication. */
    val candidateGenerationId: String? = null,
    /** Artifact dependency fingerprint observed at OCR publication. */
    val dependencyFingerprint: String? = null,
    /** Artifact-manifest page version observed at OCR publication. */
    val artifactPageVersion: Long? = null,
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

interface RenderJoinWorker {
    fun onNativeBranchDone(pageKey: String)
    fun onTranslationBranchDone(pageKey: String)
    suspend fun awaitAndRender(pageKey: String)
}

/** Result of the only live batch coordinator's first pass. */
data class BatchPass1Outcome(val needsTranslation: List<String>)

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
