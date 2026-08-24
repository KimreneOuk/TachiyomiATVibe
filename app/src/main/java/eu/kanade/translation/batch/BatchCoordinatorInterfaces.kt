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
    /**
     * Ephemeral native-lane value owned by [NativeLaneWorker]. It is only valid
     * until that worker receives [NativeLaneWorker.releaseNativeHandoff].
     */
    val nativeHandoff: Any? = null,
)

interface NativeLaneWorker {
    /**
     * Decode + OCR + persist OCR results. Returns the reference for translation,
     * or null if the page should be skipped. A returned reference may carry one
     * page-local native handoff for the immediately following inpaint stage.
     */
    suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef?

    /**
     * Run inpaint on the page. The default bridge preserves workers that do not
     * have a native handoff.
     */
    suspend fun runInpaintStage(pageKey: String)

    suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
        runInpaintStage(pageKey)
    }

    /** Releases a native handoff after inpaint or cancellation. */
    fun releaseNativeHandoff(ref: OcrReadyPageRef) {}
}

/** Result of admitting an OCR-complete page to the current provider-sized chunk. */
enum class ChunkAdmission {
    /** The page belongs to the current image chunk. */
    ACCEPT,

    /** The page is an OCR-only probe for the next image chunk. */
    PROBE,
}

interface TranslatorLaneWorker {
    /** True when [admit] buffers page input for a later chunk completion. */
    val usesChunkAdmission: Boolean get() = false

    /**
     * Admit one OCR-complete page without releasing its chunk ownership.
     *
     * Standard translators use the default per-page path. Contextual AI workers
     * override this to feed their token planner without making a provider call;
     * returning [ChunkAdmission.PROBE] retains the page as the single allowed
     * OCR-only lookahead page.
     */
    suspend fun admit(ref: OcrReadyPageRef): ChunkAdmission {
        translate(ref)
        return ChunkAdmission.ACCEPT
    }

    /** Translate one page's Pass-1 work item. */
    suspend fun translate(ref: OcrReadyPageRef)

    /**
     * Complete the current image chunk. For a buffered AI lane this is the
     * first point at which queued planner emissions may make provider calls.
     */
    suspend fun completeChunk(finalChunk: Boolean) {}
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
