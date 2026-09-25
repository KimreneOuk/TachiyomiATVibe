package eu.kanade.translation.coexistence

import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.model.Translation
import eu.kanade.translation.storage.ChapterTranslationStore
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 *  zero-legacy  — the DISPATCH-LEVEL resume wiring through the REAL
 * production shell (`ChapterTranslator.translateChapterInternal` →
 * `BatchChapterTranslator.runBatchPass1` → the coordinator's
 * `resumeFinalizeOrComplete`), rewritten for the post-flag world.
 *
 * The former flag-OFF wiring cases are gone with the  flag:
 *  - the shell-level `resumeCompletedOutcome`/`decideResume` consultation was
 *    DELETED — there is no flag-OFF state, so ALL resume wiring goes through
 *    the coordinator ( FINALIZE/COMPLETE idempotent resume +
 *    work-product evidence gate). The pure decision tests
 *    (`OcrPreflightFlagOffMidRunTest` DropToLegacy/TreatAsFinished) were
 *    deleted with the behavior.
 *  - "flag OFF mid-run drops to the legacy schedule" is meaningless: the
 *    legacy SequentialBatchCoordinator no longer exists. The surviving
 *    behavior of that case — a mid-run (non-COMPLETE) record re-dispatches
 *    into a fresh RUN_SNAPSHOT under the SAME run id, with sidecars
 *    respected — is pinned at the coordinator level in
 *    `StandardPipelineCoordinatorTest`
 *    (`re-dispatch over an interrupted standard run continues the same run id
 *    and completes exactly once`).
 *  - the COMPLETE-with-missing-evidence supersession is pinned at the
 *    coordinator level in `Stage7FinalizeResumeCoordinatorTest`
 *    (`a recorded COMPLETE lacking per-page display evidence is superseded by
 *    a fresh run `).
 *
 * What THIS file pins is the wiring itself — that a plain re-request of a
 * chapter the pipeline already finished (the Director's device A/B flow,
 * minus the flag) never re-runs ANY paid work and republishes nothing, and
 * that a demoted chapter re-dispatches real work in the SAME (new) lane:
 *
 *  1. run 1 completes through the real shell on a durable ARTIFACTS-authority
 *     store (single COMPLETE record, `standard:mlkit` provider identity);
 *  2. re-dispatch (run 2) over the identical store: zero transport calls, the
 *     chapter still finishes TRANSLATED, and the durable COMPLETE record is
 *     byte-untouched ( idempotent COMPLETE resume);
 *  3. after a user-style reset demotes one page's translated evidence, the
 *     re-dispatch starts REAL paid work for that page in the new lane (the
 *      evidence gate supersedes the recorded COMPLETE) while the healthy
 *     page is not re-paid.
 */
class BatchDispatchResumeWiringTest {

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * Runs the REAL shell until it publishes COMPLETE or returns a typed
     * retryable stop. Such a result is an explicit retry boundary: a later user
     * request re-arms the chapter. Model that request with a fresh harness over
     * the same store.
     */
    private fun firstRun(pageKeys: List<String>): TranslationCoexistenceHarness {
        var durableStore: ChapterTranslationStore? = null
        val retryDiagnostics = mutableListOf<String>()
        val maxAttempts = 4
        for (attempt in 1..maxAttempts) {
            val harness = TranslationCoexistenceHarness.createStandard(pageKeys, durableStore)
            durableStore = harness.store
            var keepHarness = false
            try {
                harness.stubChapterPages(pageKeys)
                val batch = harness.launchBatch(pageKeys)
                val reconciliation = runBlocking {
                    withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                        batch.reconciliation.await().shouldNotBeNull()
                    }
                }
                if (reconciliation.nonDurableFailure || reconciliation.retryableCount > 0 || reconciliation.paused) {
                    retryDiagnostics +=
                        "attempt=$attempt, reconciliation=$reconciliation: " +
                        harness.failureDiagnostics(pageKeys)
                    check(attempt < maxAttempts) {
                        "run did not publish durably after $maxAttempts user-style attempts: " +
                            retryDiagnostics.joinToString(" | ")
                    }
                    continue
                }

                // A non-null reconciliation is NOT a completion oracle: a
                // typed pause can also reconcile non-null. Poll the durable
                // record until COMPLETE and include the exact failure context
                // if it stays in an earlier phase.
                val artifact = harness.store.artifactEngine.shouldNotBeNull()
                try {
                    runBlocking {
                        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                            while (durableRecord(artifact).second != ChapterRunState.COMPLETE) {
                                delay(50)
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    val manifest = artifact.readManifest().shouldNotBeNull()
                    val record = (
                        artifact.readRunRecord(manifest.activeRun.shouldNotBeNull())
                            as ChapterArtifactEngine.RunRecordRead.Usable
                        ).record
                    error(
                        "run $attempt never reached ChapterRunState.COMPLETE within " +
                            "${TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS}ms — state=${record.state}, " +
                            "runId=${record.runId}, phaseCounters=${record.phaseCounters}, " +
                            "reconciliation=$reconciliation, diagnostics=${harness.failureDiagnostics(pageKeys)}",
                    )
                }
                keepHarness = true
                return harness
            } finally {
                if (!keepHarness) harness.close()
            }
        }
        error(
            "run did not reach ChapterRunState.COMPLETE after $maxAttempts attempts: " +
                retryDiagnostics.joinToString(" | "),
        )
    }

    private fun durableRecord(
        artifact: ChapterArtifactEngine,
    ): Pair<String, ChapterRunState> {
        val manifest = artifact.readManifest().shouldNotBeNull()
        val record = (
            artifact.readRunRecord(manifest.activeRun.shouldNotBeNull())
                as ChapterArtifactEngine.RunRecordRead.Usable
            ).record
        return record.runId to record.state
    }

    @Test
    fun `re-dispatch over a finished chapter costs zero work and republishes nothing`() {
        val pageKeys = listOf("p0", "p1")
        val first = firstRun(pageKeys)
        try {
            // Run 1 closed as a real COMPLETE publication.
            val artifact = first.store.artifactEngine.shouldNotBeNull()
            val manifestBefore = artifact.readManifest().shouldNotBeNull()
            val pointerBefore = manifestBefore.activeRun.shouldNotBeNull()
            val recordBefore = (
                artifact.readRunRecord(pointerBefore)
                    as ChapterArtifactEngine.RunRecordRead.Usable
                ).record
            recordBefore.state shouldBe ChapterRunState.COMPLETE
            recordBefore.frozenConfig.providerKey shouldBe "standard:mlkit"

            // Run 2: the same chapter re-requested through the real shell over
            // the identical store — no flag, no special casing.
            val second = TranslationCoexistenceHarness.createStandard(
                pageKeys,
                storeOverride = first.store,
            )
            try {
                second.stubChapterPages(pageKeys)
                val batch = second.launchBatch(pageKeys)
                val reconciliation = runBlocking {
                    withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                        batch.reconciliation.await().shouldNotBeNull()
                    }
                }

                // ZERO paid work: with the flag gone there is no shell-level
                // short-circuit left — the re-dispatch re-enters the
                // pipeline and the resume obligation is settled by the
                // coordinator's  COMPLETE fast path ( evidence gate
                // satisfied by the open candidate work products; pinned at
                // coordinator level in StandardPipelineCoordinatorTest T3).
                second.transportCallsFor("p0") shouldBe 0
                second.transportCallsFor("p1") shouldBe 0

                // The chapter still finishes end-to-end (no stranded pages).
                if (reconciliation.chapterStatus != Translation.State.TRANSLATED) {
                    throw AssertionError(
                        "idempotent standard resume ended as ${reconciliation.chapterStatus}: " +
                            second.failureDiagnostics(pageKeys),
                    )
                }
                reconciliation.chapterStatus shouldBe Translation.State.TRANSLATED
                reconciliation.doneCount shouldBe pageKeys.size
                reconciliation.strandedPages shouldBe emptyMap()

                // The durable COMPLETE record still owns the chapter,
                // untouched (idempotent resume republishes nothing).
                artifact.readManifest().shouldNotBeNull().activeRun shouldBe pointerBefore
                (
                    artifact.readRunRecord(pointerBefore)
                        as ChapterArtifactEngine.RunRecordRead.Usable
                    ).record shouldBe recordBefore
            } finally {
                second.close()
            }
        } finally {
            first.close()
        }
    }

    @Test
    fun `re-dispatch after a reset demotes real work in the same lane while healthy pages stay retired`() {
        val pageKeys = listOf("p0", "p1")
        val first = firstRun(pageKeys)
        try {
            val artifact = first.store.artifactEngine.shouldNotBeNull()
            val manifestBefore = artifact.readManifest().shouldNotBeNull()
            val recordBefore = (
                artifact.readRunRecord(manifestBefore.activeRun.shouldNotBeNull())
                    as ChapterArtifactEngine.RunRecordRead.Usable
                ).record
            recordBefore.state shouldBe ChapterRunState.COMPLETE

            // User-style reset of p1 only: clear its live translated state and
            // demote its durable display/work-product evidence (the real
            // reset primitives, mirroring the coordinator-level  test).
            runBlocking {
                first.store.updatePageFromCurrentSnapshot("p1", "t924 dispatch reset") { page ->
                    page?.copy(
                        blocks = page.blocks.map { it.copy(translation = "") }.toMutableList(),
                        translationStatus = eu.kanade.translation.model.StageStatus.PENDING,
                        renderStatus = eu.kanade.translation.model.StageStatus.PENDING,
                    ) ?: eu.kanade.translation.model.PageTranslation(sourceFileName = "p1")
                }
                first.store.demoteCommittedDisplay("p1", "t924 dispatch reset")
                first.store.flush()
            }

            // Re-dispatch through the real shell.
            val second = TranslationCoexistenceHarness.createStandard(
                pageKeys,
                storeOverride = first.store,
            )
            try {
                second.stubChapterPages(pageKeys)
                val batch = second.launchBatch(pageKeys)

                // The  gate supersedes the recorded COMPLETE: p1 gets REAL
                // paid work in the SAME (standard) lane — a legacy coordinator
                // no longer exists to be involved.
                runBlocking {
                    withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                        second.transportStarted["p1"].shouldNotBeNull().await()
                    }
                }

                batch.job.cancel()
                runBlocking {
                    withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.job.join() }
                }

                // The healthy page was never re-paid; only p1 re-ran.
                second.transportCallsFor("p0") shouldBe 0
                (second.transportCallsFor("p1") >= 1) shouldBe true

                // The run record keeps owning the chapter under the SAME run
                // id (the frozen fingerprint still matches): the fresh
                // RUN_SNAPSHOT the re-dispatch published carries run 1's id.
                // (The job was cancelled mid-run, so the record sits at its
                // last in-run phase, not COMPLETE.)
                val (runIdAfter, _) = durableRecord(artifact)
                runIdAfter shouldBe recordBefore.runId
            } finally {
                second.close()
            }
        } finally {
            first.close()
        }
    }
}
