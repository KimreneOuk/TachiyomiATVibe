package eu.kanade.translation.pipeline.batch

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LayoutFailureException
import eu.kanade.translation.RenderBlockPatch
import eu.kanade.translation.RenderStagePatch
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ColorStylePreparation
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.PageLayoutDrawPlan
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.StageArtifactRecord
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.rendering.DrawPlanFingerprint
import eu.kanade.translation.rendering.LayoutPlanPublication
import eu.kanade.translation.rendering.PersistedLayoutRuntime
import eu.kanade.translation.rendering.ProductionTextMeasurer
import eu.kanade.translation.rendering.RenderColorEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

/**
 * T909 Phase 20.4: the batch render join moved verbatim from
 * `TranslationPipeline.translateBatch` (T909 phase 20).
 *
 * Render join: per-page join of the translation result with its inpaint/render
 * prerequisites. tryRender is idempotent (READY short-circuit) so it is safe to
 * call both at chunk-completion (AI) and here; the per-page render mutex keeps
 * it serialized. This is the join the coordinator awaits for each page.
 * Bitmap recycle sites stay with tryRender.
 */
internal class BatchRenderJoin(
    private val store: ChapterTranslationStore,
    private val manga: Manga,
    private val chapter: Chapter,
    private val source: HttpSource,
    private val tracker: TranslationBatchProgressTracker?,
    private val translationRegistry: ConcurrentHashMap<String, PageTranslation>,
    private val heldBitmapRegistry: HeldBitmapRegistry,
    private val resumePlanner: BatchResumePlanner,
    private val writeGate: BatchWriteGate,
    private val expectedBatchFingerprints: BatchExpectedFingerprints,
    private val loadPersistedCleanedBitmapFn: suspend (Manga, Chapter, HttpSource, String) -> Bitmap?,
    private val deleteRetiredCleanedFileFn: suspend (Manga, Chapter, HttpSource, String, ChapterTranslationStore) -> Unit,
    private val abortBatchCandidateFn: suspend (String, String) -> Unit,
) : RenderJoinWorker {

    private val renderMutexes = ConcurrentHashMap<String, Mutex>()

    private val nativeRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val translationRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // Same-name wiring for the injected collaborators: the moved bodies call
    // these as plain named functions / property-style reads.
    private fun plannedRenderNeedsWork(pageKey: String): Boolean =
        resumePlanner.plannedRenderNeedsWork(pageKey)

    private fun translationFailureFence(pageKey: String): Boolean =
        resumePlanner.translationFailureFence(pageKey)

    private suspend fun releaseBatchLease(pageKey: String) =
        writeGate.releaseBatchLease(pageKey)

    private suspend fun guardedBatchUpdate(
        pageKey: String,
        description: String,
        stage: BatchStage?,
        update: (PageTranslation?) -> PageTranslation,
    ) = writeGate.guardedBatchUpdate(pageKey, description, stage, update)

    private suspend fun persistBatchPageWithOomRecovery(
        pageKey: String,
        pageTranslation: PageTranslation,
    ) = writeGate.persistBatchPageWithOomRecovery(pageKey, pageTranslation)

    private suspend fun loadPersistedCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        cleanedImageName: String,
    ): Bitmap? = loadPersistedCleanedBitmapFn(manga, chapter, source, cleanedImageName)

    private suspend fun deleteRetiredCleanedFile(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) = deleteRetiredCleanedFileFn(manga, chapter, source, pageKey, store)

    private suspend fun abortBatchCandidate(pageKey: String, reason: String) =
        abortBatchCandidateFn(pageKey, reason)

    suspend fun tryRender(pageKey: String) {
        val mutex = renderMutexes.computeIfAbsent(pageKey) { Mutex() }
        mutex.withLock {
            val page = translationRegistry[pageKey] ?: return@withLock
            if (page.ocrStatus == StageStatus.FAILED ||
                page.translationStatus == StageStatus.FAILED ||
                page.inpaintStatus == StageStatus.FAILED ||
                page.renderStatus == StageStatus.FAILED
            ) {
                abortBatchCandidate(pageKey, page.activeError ?: "terminal stage failure")
                return@withLock
            }
            if (page.isTextlessTerminal) {
                tracker?.markTranslateSkipped(pageKey)
                tracker?.markRenderSkipped(pageKey)
                heldBitmapRegistry.recycleHeld(pageKey)
                translationRegistry.remove(pageKey)
                releaseBatchLease(pageKey)
                return@withLock
            }
            if (page.renderStatus == StageStatus.READY && !plannedRenderNeedsWork(pageKey)) {
                heldBitmapRegistry.recycleHeld(pageKey)
                translationRegistry.remove(pageKey)
                releaseBatchLease(pageKey)
                tracker?.markRenderDone(pageKey)
                return@withLock
            }
            val status = page.translationStatus
            if (status != StageStatus.READY && status != StageStatus.PARTIAL) {
                if (status == StageStatus.FAILED) {
                    heldBitmapRegistry.recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    releaseBatchLease(pageKey)
                }
                return@withLock
            }
            val inpaintStatus = page.inpaintStatus
            if (inpaintStatus != StageStatus.READY && inpaintStatus != StageStatus.PARTIAL && inpaintStatus != StageStatus.TEXTLESS) {
                if (inpaintStatus == StageStatus.FAILED) {
                    heldBitmapRegistry.recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    releaseBatchLease(pageKey)
                }
                // Inpaint isn't ready yet, wait for inpaintWorker to call tryRender
                return@withLock
            }
            // READY/PARTIAL -> render. Consume the held bitmap on the happy path
            // (no disk reload); spill/SKIP_ALL pages reload .cleaned.jpg instead.
            val held = heldBitmapRegistry.bitmapRegistry.remove(pageKey)
            if (held != null) {
                heldBitmapRegistry.heldBitmapBytes.addAndGet(-held.byteCount.toLong())
                heldBitmapRegistry.countSlots.release()
            }
            val bitmap = held ?: page.cleanedImageName?.let { loadPersistedCleanedBitmap(manga, chapter, source, it) }
            if (bitmap == null && inpaintStatus != StageStatus.TEXTLESS) {
                page.inpaintStatus = StageStatus.FAILED
                page.renderStatus = StageStatus.FAILED
                page.recordAttemptFailure()
                page.errorMessage = "Cleaned image is missing or unreadable; retry inpainting"
                tracker?.markInpaintFailed(pageKey, page.errorMessage!!)
                tracker?.markRenderFailed(pageKey, page.errorMessage!!)
                val failurePersisted = persistBatchPageWithOomRecovery(pageKey, page)
                abortBatchCandidate(pageKey, page.errorMessage!!)
                if (failurePersisted is ChapterTranslationStore.PatchResult.Rejected) {
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.RENDER,
                    )
                }
                return@withLock
            }
            var renderPersisted = false
            try {
                tracker?.markRenderRunning(pageKey)
                val running = guardedBatchUpdate(pageKey, "batch render running", BatchStage.LAYOUT) {
                    (it ?: page).apply {
                        renderStatus = StageStatus.RUNNING
                        updatedAt = System.currentTimeMillis()
                    }
                }
                if (running is ChapterTranslationStore.PatchResult.Rejected) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch render running rejected (stale writer): " +
                            "pageKey=$pageKey reason=${running.reason}"
                    }
                    abortBatchCandidate(pageKey, "render admission rejected: ${running.reason}")
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.RENDER,
                    )
                }
                val renderInput = store.snapshot(pageKey)
                // T922 Phase 4: layout stage outcome for the page run. The
                // span settles even when the estimator throws (layout failure
                // path below keeps its existing handling).
                val layoutSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.LAYOUT,
                    lane = TranslationTraceLane.RENDER,
                )
                val patch: RenderStagePatch = try {
                    RenderColorEstimator.recomputeFor(bitmap, page.blocks)
                    layoutSpan.end(TranslationTraceOutcome.SUCCESS)
                    page.renderStatus = StageStatus.READY
                    page.updatedAt = System.currentTimeMillis()
                    val renderSpan = TranslationTrace.beginStage(
                        TranslationTraceStage.RENDER,
                        lane = TranslationTraceLane.RENDER,
                    )
                    try {
                        RenderStagePatch(
                    pageKey = pageKey,
                    generation = renderInput.generation,
                    expectedPageVersion = renderInput.pageVersion,
                    expectedLeaseToken = renderInput.leaseToken,
                    expectedCleanedImageName = renderInput.page?.cleanedImageName ?: "",
                    expectedInpaintRevision = renderInput.page?.inpaintRevision ?: 0,
                    expectedOcrBlockFingerprints = renderInput.page?.ocrBlockFingerprints().orEmpty(),
                    expectedCandidateGenerationId = renderInput.candidateGenerationId,
                    expectedDependencyFingerprint = renderInput.dependencyFingerprint,
                    expectedArtifactPageVersion = renderInput.artifactPageVersion,
                    layoutFingerprint = expectedBatchFingerprints.layout,
                    blocks = page.blocks.mapIndexed { index, block ->
                        RenderBlockPatch(
                            blockIndex = index,
                            expectedBlockFingerprint = renderInput.page?.blocks?.getOrNull(index)?.stableFingerprint() ?: "",
                            textColor = block.textColor,
                            strokeColor = block.strokeColor,
                            strokeWidth = block.strokeWidth,
                        )
                    },
                    renderStatus = StageStatus.READY,
                    )
                    } finally {
                        renderSpan.end()
                    }
                } catch (t: Throwable) {
                    layoutSpan.end(
                        if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                        error = t,
                    )
                    throw t
                }
                // T922 Phase 4: store commit outcome for the render stage patch.
                // Phase 4 review N3: the span settles in try/catch so a throw
                // from mergeRender (cancellation while suspended, store error)
                // cannot leave stage_start(store_commit) dangling.
                val commitSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.STORE_COMMIT,
                    lane = TranslationTraceLane.STORAGE,
                )
                val renderResult = try {
                    val result = store.mergeRender(patch)
                    commitSpan.end(
                        if (result is StagePatchResult.Accepted) {
                            TranslationTraceOutcome.SUCCESS
                        } else {
                            TranslationTraceOutcome.FAILURE
                        },
                    )
                    result
                } catch (t: Throwable) {
                    commitSpan.end(
                        if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                        error = t,
                    )
                    throw t
                }
                val acceptedRender = renderResult as? StagePatchResult.Accepted
                renderPersisted = acceptedRender != null
                if (renderResult is StagePatchResult.Rejected) {
                    abortBatchCandidate(pageKey, "render commit rejected: ${renderResult.reason}")
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.RENDER,
                    )
                }
                if (renderPersisted) {
                    // A newer committed display bundle may have just
                    // promoted; the file it superseded is now deletable.
                    deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
                    // T924 WP9 (T924-FF-02a(1)): with FF-02 ON the color-only
                    // render body becomes LAYOUT_PREPARE orchestration — the
                    // color preparation above is joined by the page geometry
                    // draw plan, both published as separately invalidatable
                    // sub-results (T924-TX-23 CAS set). Every failure here is
                    // non-fatal: the committed render stays authoritative and
                    // readers keep the async planner (T924-FF-02b/02c).
                    publishPersistedLayoutIfEnabled(pageKey, page, acceptedRender!!.snapshot)
                }
                tracker?.markRenderDone(pageKey)
            } catch (e: BatchPersistenceRejectedException) {
                throw e
            } catch (e: LayoutFailureException) {
                page.renderStatus = StageStatus.FAILED
                page.recordAttemptFailure()
                val msg = if (e.blockIds.isNotEmpty()) "${e.message} (blocks: ${e.blockIds.joinToString()})" else e.message
                page.errorMessage = msg
                tracker?.markRenderFailed(pageKey, msg ?: "Layout failed")
                logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render layout failed: $pageKey blocks=${e.blockIds}" }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                page.renderStatus = StageStatus.FAILED
                page.recordAttemptFailure()
                page.errorMessage = e.message
                tracker?.markRenderFailed(pageKey, e.message ?: e::class.java.simpleName)
                logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render failed: $pageKey" }
            } finally {
                try {
                    bitmap?.recycle()
                } catch (_: Exception) {}
                page.cleanedBitmap = null
                if (!renderPersisted) {
                    val failurePersisted = persistBatchPageWithOomRecovery(pageKey, page)
                    abortBatchCandidate(pageKey, page.activeError ?: "render did not commit")
                    if (failurePersisted is ChapterTranslationStore.PatchResult.Rejected) {
                        throw BatchPersistenceRejectedException(
                            pageKey = pageKey,
                            stage = BatchDiagnosticStage.RENDER,
                        )
                    }
                } else {
                    translationRegistry.remove(pageKey)
                    releaseBatchLease(pageKey)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // T924 WP9 (T924-FF-02a(1)): LAYOUT_PREPARE publication. FF-02 OFF keeps
    // the legacy color-only render body byte-for-byte; ON adds the per-page
    // persisted draw plan + color preparation publication after the render
    // commit, through ChapterArtifactStore.publishSidecarPointers with the
    // full T924-TX-23 CAS precondition set. Plans are late, per-page, and
    // additive: any precondition or publication failure keeps the committed
    // render authoritative and simply leaves "no plan" for the page
    // (T924-FF-02c — readers fall back to the async planner).
    // ------------------------------------------------------------------

    /** Lazily resolved Android context for the font asset read; null on JVM. */
    private val layoutPublicationContext: Context? by lazy {
        runCatching { Injekt.get<Application>() as Context }.getOrNull()
    }

    private fun publishPersistedLayoutIfEnabled(
        pageKey: String,
        page: PageTranslation,
        postSnapshot: ChapterTranslationStore.PageSnapshot,
    ) {
        if (!PersistedLayoutRuntime.flagEnabled()) return
        try {
            publishPersistedLayout(pageKey, page, postSnapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication failed: pageKey=$pageKey error=$t"
            }
        }
    }

    private fun publishPersistedLayout(
        pageKey: String,
        page: PageTranslation,
        postSnapshot: ChapterTranslationStore.PageSnapshot,
    ) {
        val artifact = store.artifactStore ?: return
        val manifest = store.artifactManifest ?: return
        if (manifest.authority != ManifestAuthority.ARTIFACTS) return
        val artifactPage = manifest.pages[pageKey] ?: return

        // T924-TX-23 precondition set, checked against the post-render-merge
        // snapshot; the whole-manifest CAS in publishSidecarPointers rejects
        // the commit if ANY durable state moved between this snapshot and the
        // manifest publication (fail-closed, prior manifest stays
        // authoritative).
        // (1) artifact pageVersion fencing.
        if (postSnapshot.artifactPageVersion != null &&
            artifactPage.pageVersion != postSnapshot.artifactPageVersion
        ) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=artifact pageVersion changed"
            }
            return
        }
        // (2) candidate generation + dependency fingerprint fencing.
        val candidate = artifactPage.candidate
        if (postSnapshot.candidateGenerationId != null &&
            candidate?.generationId != postSnapshot.candidateGenerationId
        ) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=candidate generation changed"
            }
            return
        }
        if (postSnapshot.dependencyFingerprint != null &&
            candidate != null &&
            candidate.dependencyFingerprint != postSnapshot.dependencyFingerprint
        ) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=dependency fingerprint changed"
            }
            return
        }
        // (3) OCR geometry/block identity: the plan carries the stable block
        // ids and mask content hashes; the page's OCR identity must still be
        // the merged snapshot's (pageVersion fencing above covers replacement,
        // this pins per-block drift).
        if (postSnapshot.page != null &&
            page.ocrBlockFingerprints() != postSnapshot.page.ocrBlockFingerprints()
        ) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=ocr block identity changed"
            }
            return
        }
        // (4)/(5) color + cleaned-image identity are embedded IN the published
        // ColorStylePreparation (cleanedImageRef fileName + inpaintRevision,
        // imageSource kind) and in the geometry plan's mask content hashes, so
        // a later reader-side fingerprint/identity comparison invalidates them.

        val pageWidth = page.imgWidth
        val pageHeight = page.imgHeight
        val context = layoutPublicationContext
        if (context == null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=no Android context for font identity"
            }
            return
        }
        installFontDigestLoader(context)
        val fontDigest = PersistedLayoutRuntime.productionFontSha256()
        if (fontDigest == null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=font digest unavailable"
            }
            return
        }
        val typeface = runCatching {
            ResourcesCompat.getFont(context, R.font.animeace)?.let { Typeface.create(it, Typeface.BOLD) }
        }.getOrNull()
        if (typeface == null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=font resource unavailable"
            }
            return
        }
        val compatInputs = LayoutPlanPublication.CompatInputs(
            translationArtifactId = page.ocrArtifactId.orEmpty(),
            cleanedImageArtifactIdOrOriginalSourceId =
                page.cleanedImageName ?: "original:${page.sourceFingerprint.orEmpty()}",
        )
        val prepared = LayoutPlanPublication.prepare(
            blocks = page.blocks,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            decodeSampleSize = page.decodeSampleSize,
            measurer = ProductionTextMeasurer.create(typeface),
            cleanedImageName = page.cleanedImageName,
            inpaintRevision = page.inpaintRevision,
            fontAssetSha256 = fontDigest,
            compatInputs = compatInputs,
        )
        if (prepared == null) {
            // Unpublishable (no drawable blocks, DTO validation failure, or
            // schema cap) — skip; never publish a plan that would fail
            // validation (provable-only rule).
            logcat(LogPriority.INFO) {
                "TachiyomiAT persisted-layout publication skipped: pageKey=$pageKey reason=plan not publishable"
            }
            return
        }

        val planFileName = artifact.layoutPlanSidecarName(pageKey, prepared.planContentFingerprint)
        val colorFileName = artifact.colorPreparationSidecarName(pageKey, prepared.colorContentFingerprint)
        val now = System.currentTimeMillis()
        val outcome = artifact.publishSidecarPointers(
            manifest = manifest,
            sidecars = listOf(
                artifact.jsonSidecarPublication(
                    fileName = planFileName,
                    contentFingerprint = prepared.planContentFingerprint,
                    document = prepared.plan,
                    serializer = PageLayoutDrawPlan.serializer(),
                ),
                artifact.jsonSidecarPublication(
                    fileName = colorFileName,
                    contentFingerprint = prepared.colorContentFingerprint,
                    document = prepared.colorPreparation,
                    serializer = ColorStylePreparation.serializer(),
                ),
            ),
            updatePointers = { current ->
                val updatedPage = current.pages.getValue(pageKey).copy(
                    layout = StageArtifactRecord(
                        status = ArtifactStageStatus.READY,
                        fingerprint = prepared.compatibilityFingerprint,
                        origin = ArtifactOrigin.BATCH,
                        artifactFileName = planFileName,
                        updatedAtEpochMs = now,
                    ),
                )
                current.copy(
                    pages = current.pages + (pageKey to updatedPage),
                    layoutPlans = current.layoutPlans + (
                        pageKey to SidecarPointer(
                            fileName = planFileName,
                            schemaVersion = PageLayoutDrawPlan.SCHEMA_VERSION,
                            contentFingerprint = prepared.planContentFingerprint,
                        )
                        ),
                    colorPreparations = current.colorPreparations + (
                        pageKey to SidecarPointer(
                            fileName = colorFileName,
                            schemaVersion = ColorStylePreparation.SCHEMA_VERSION,
                            contentFingerprint = prepared.colorContentFingerprint,
                        )
                        ),
                )
            },
        )
        when (outcome) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> {
                // Keep the store façade's manifest snapshot current (mirrors
                // the checkpointOcr façade), so later transactions CAS against
                // the fresh durable state instead of rejecting as stale.
                store.artifactManifest = outcome.manifest
                logcat(LogPriority.INFO) {
                    "TachiyomiAT persisted layout published: pageKey=$pageKey blocks=${prepared.plan.blocks.size}"
                }
            }
            is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT persisted-layout publication rejected (committed render kept): " +
                        "pageKey=$pageKey reason=${outcome.reason}"
                }
            }
        }
    }

    /**
     * One-time process install of the production font digest loader
     * (res/font/animeace.ttf read via the application context). The overlay
     * installs the same loader at its Android entry point; installation is
     * idempotent and the digest itself is computed once and cached
     * (wave-2 review gap 6).
     */
    private fun installFontDigestLoader(context: Context) {
        if (PersistedLayoutRuntime.fontSourceInstalled) return
        PersistedLayoutRuntime.fontSha256Loader = {
            val app = Injekt.get<Application>()
            DrawPlanFingerprint.fontAssetSha256(
                app.resources.openRawResource(R.font.animeace).use { it.readBytes() },
            )
        }
        PersistedLayoutRuntime.fontSourceInstalled = true
    }

    // ------------------------------------------------------------------
    // T924 Stage 7 (D2): per-page persisted-layout publication entry for the
    // flagged coordinator's NATIVE/RENDER path. Called after a page reached
    // committed-translation + committed-inpaint state (via the OverlapScheduler's
    // commit hook and the FINALIZE sweep). Reuses the EXISTING T924-TX-23
    // publication transaction above — same CAS fences, same sidecars, same
    // fail-safe: any precondition/failure keeps the committed display and the
    // async planner fallback authoritative (T924-FF-02b; reader display never
    // breaks, no rasterized output ever — R041, the published artifacts are
    // geometry/color DTOs only).
    // ------------------------------------------------------------------

    /**
     * Publishes the persisted layout for [pageKey] when the page carries the
     * full stage evidence (translation READY/PARTIAL, inpaint READY, drawable
     * blocks) and no plan is published yet (idempotent sweep). Returns true
     * when a plan is (already) published for the page.
     */
    internal suspend fun publishPersistedLayoutForCompletedPage(pageKey: String): Boolean {
        if (!PersistedLayoutRuntime.flagEnabled()) return false
        return try {
            val manifestNow = store.artifactManifest
            if (manifestNow?.layoutPlans?.containsKey(pageKey) == true) return true
            val snapshot = store.snapshot(pageKey)
            val page = snapshot.page ?: return false
            val translationReady = page.translationStatus == StageStatus.READY ||
                page.translationStatus == StageStatus.PARTIAL
            if (!translationReady || page.inpaintStatus != StageStatus.READY) return false
            if (page.blocks.isEmpty()) return false
            if (manifestNow == null || manifestNow.authority != ManifestAuthority.ARTIFACTS) return false
            publishPersistedLayout(pageKey, page, snapshot)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT persisted-layout stage-7 publication failed (planner fallback kept): " +
                    "pageKey=$pageKey error=$t"
            }
            false
        }
    }

    fun signalFor(
        signals: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
        pageKey: String,
    ): CompletableDeferred<Unit> = signals.getOrPut(pageKey) { CompletableDeferred() }

    override fun onNativeBranchDone(pageKey: String) {
        signalFor(nativeRenderSignals, pageKey).complete(Unit)
    }

    override fun onTranslationBranchDone(pageKey: String) {
        signalFor(translationRenderSignals, pageKey).complete(Unit)
    }

    override suspend fun awaitAndRender(pageKey: String) {
        signalFor(nativeRenderSignals, pageKey).await()
        signalFor(translationRenderSignals, pageKey).await()
        // A resumed retryable/terminal translation candidate owns no
        // display publication. Its committed bundle remains the reader
        // authority until an eligible retry succeeds, so never route a
        // failed candidate through the normal render path.
        if (!translationFailureFence(pageKey)) {
            tryRender(pageKey)
        }
        nativeRenderSignals.remove(pageKey)
        translationRenderSignals.remove(pageKey)
    }

    override suspend fun awaitAndSettle(pageKey: String) {
        signalFor(nativeRenderSignals, pageKey).await()
        signalFor(translationRenderSignals, pageKey).await()
        // A paused/terminal anchor retains the prior committed
        // display. Do not invoke tryRender on its provisional
        // candidate or overwrite that display pointer.
        nativeRenderSignals.remove(pageKey)
        translationRenderSignals.remove(pageKey)
    }
}
