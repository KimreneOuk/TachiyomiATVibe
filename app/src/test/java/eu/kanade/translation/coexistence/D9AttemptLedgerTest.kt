package eu.kanade.translation.coexistence

import eu.kanade.translation.artifact.loadArtifact

import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.ArtifactSeed
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * T917 Phase 3 — D9 durable attempt ledger + crash-loop cap (phase3-design §3, §5.3).
 *
 * Drives the REAL production graph over an ARTIFACT-authority store
 * (FakeChapterDocumentIo + the production fresh-chapter recipe, D5 harness
 * precedent) so the ledger sidecar is observable across a simulated process
 * death: a batch page parked at PROVIDER_START, then its scope killed WITHOUT
 * releasing the barrier — exactly the state a process death leaves behind (the
 * paid call never completed, so nothing may have resolved its entry).
 *
 * Ledger-side assertions read `attempts/ledger.json` directly from the fake
 * document IO through test-local kotlinx-serialization mirrors, pinning the
 * layout + record schema from the design note independently of the store
 * collaborator.
 *
 * RED (committed first, phase3-design §6 step 4): no attempt ledger exists, so
 * a death mid-call leaves no durable trace, the startup reconcile has nothing
 * to consume, and the crash-loop cap never binds. Where a test must call a
 * commit-2 production seam that cannot exist yet (the startup reconcile pass,
 * the store-level attempt recording/clearing), it goes through the reflection
 * bridge below, which raises an assertion naming the missing defect — never a
 * timeout and never a compile-time dependency on commit 2.
 */
class D9AttemptLedgerTest {

    companion object {
        /** The design-mandated ledger sidecar path for the fixture chapter. */
        const val LEDGER_FILE = "D9 Chapter_artifacts/attempts/ledger.json"
        const val CHAPTER_ID = TranslationCoexistenceHarness.CHAPTER_ID
        const val AWAIT_TIMEOUT_MS = TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS
    }

    // Shared document IO across simulated deaths: the "disk" survives the
    // scope kill, a NEW store instance reopens it.
    private val io = FakeChapterDocumentIo()

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Production fresh-chapter recipe over the shared IO (D5 harness precedent). */
    private fun freshStore(pageKeys: List<String>): ChapterTranslationStore {
        val artifactStore = ChapterArtifactEngine(
            AtomicChapterDocuments(io),
            ChapterArtifactLayout("D9 Chapter"),
        )
        val manifest = artifactStore
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        return ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = pageKeys.associateWith { key -> PageTranslation(sourceFileName = key) },
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    /** One batch run parked mid-paid-call on p0: its owning harness, run, store. */
    private class ParkedBatch(
        val harness: TranslationCoexistenceHarness,
        val batch: TranslationCoexistenceHarness.BatchRun,
        val store: ChapterTranslationStore,
    )

    private fun startParkedBatch(pageKeys: List<String> = listOf("p0")): ParkedBatch {
        val store = freshStore(pageKeys)
        val harness = TranslationCoexistenceHarness.create(pageKeys, storeOverride = store)
        harness.stubChapterPages(pageKeys)
        harness.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
        val batch = harness.launchBatch(pageKeys)
        runBlocking {
            harness.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.PROVIDER_START,
                "p0",
                AWAIT_TIMEOUT_MS,
            )
        }
        return ParkedBatch(harness, batch, store)
    }

    /**
     * Simulated process death: kill the scope WITHOUT releasing the barrier —
     * the paid call never completes, exactly as a dead process leaves it. The
     * join is quiescence only (no ledger-relevant write can run afterwards);
     * nothing downstream of the parked call may resolve anything.
     */
    private fun killScope(parked: ParkedBatch) {
        parked.batch.job.cancel()
        runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { parked.batch.job.join() } }
        parked.harness.close()
    }

    // ------------------------------------------------------------------
    // ledger-file observation (schema pinned from the design note)
    // ------------------------------------------------------------------

    @Serializable
    private data class EntryMirror(
        val pageKey: String = "",
        val providerKeyHash: String = "",
        val origin: String = "BATCH",
        val generation: Long = 0L,
        val startedAtEpochMs: Long = 0L,
    )

    @Serializable
    private data class LedgerMirror(
        val entries: List<EntryMirror> = emptyList(),
        val consecutiveUnresolved: Map<String, Int> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun readLedger(): LedgerMirror? =
        io.read(LEDGER_FILE)?.let { bytes -> json.decodeFromString<LedgerMirror>(bytes.decodeToString()) }

    // ------------------------------------------------------------------
    // commit-2 seam bridge — named failure, never a timeout.
    // An absent seam IS the defect under test, so the bridge raises an
    // assertion naming it instead of failing to compile against it.
    // ------------------------------------------------------------------

    private fun invokeSuspending(target: Any, name: String, vararg args: Any?): Any? {
        val candidates = generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .toList()
        // `internal` production members carry the JVM module mangling
        // (`<name>$app_standardDebug`); match the mangled form, never the
        // `$default` synthetic.
        val method = candidates.firstOrNull { it.name == name }
            ?: candidates.firstOrNull {
                it.name.startsWith("${name}${'$'}") && !it.name.endsWith("${'$'}default")
            }
        checkNotNull(method) {
            "T917 D9 RED defect: $name is not implemented — the durable attempt ledger seam is missing"
        }
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val mapped = method.parameterTypes.dropLast(1).mapIndexed { index, type ->
            val raw = args[index]
            if (type.isEnum) {
                java.lang.Enum.valueOf(type.asSubclass(Enum::class.java) as Class<out Enum<*>>, raw as String)
            } else {
                raw
            }
        }
        val done = CompletableDeferred<Any?>()
        val continuation = object : kotlin.coroutines.Continuation<Any?> {
            override val context get() = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                result.fold(done::complete, done::completeExceptionally)
            }
        }
        val token = method.invoke(target, *mapped.toTypedArray(), continuation)
        if (token !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) done.complete(token)
        return runBlocking { withTimeout(AWAIT_TIMEOUT_MS) { done.await() } }
    }

    /** Startup reconcile over exactly the bounded chapter set (never a library scan). */
    private fun reconcileStartup(manager: eu.kanade.translation.TranslationManager, store: ChapterTranslationStore) {
        invokeSuspending(
            manager,
            "reconcileAttemptLedgersForStartup",
            setOf(CHAPTER_ID),
            TranslationStoreResolver { _ -> store },
        )
    }

    // ------------------------------------------------------------------
    // 1. entry written BEFORE the paid call; startup reconcile consumes it
    // ------------------------------------------------------------------

    @Test
    fun `paid batch call writes a ledger entry before the provider call and startup reconcile consumes it`() =
        runBlocking<Unit> {
            val parked = startParkedBatch()
            try {
                // The entry is durable BEFORE the provider call was allowed to
                // run (the page is still parked at PROVIDER_START).
                val parkedLedger = withClue(
                    "D9 (design §3.2): the attempt entry must be durable BEFORE the paid provider " +
                        "call starts — the page is parked at PROVIDER_START and the ledger sidecar " +
                        "must already record the started attempt. RED defect: no attempt ledger " +
                        "exists, so a death mid-call leaves no durable trace",
                ) {
                    readLedger().shouldNotBeNull()
                }
                withClue("D9: exactly the parked page's attempt is recorded while the call is in flight") {
                    parkedLedger.entries.map { it.pageKey } shouldBe listOf("p0")
                    parkedLedger.entries.single().origin shouldBe "BATCH"
                }
                withClue("D9: the entry carries the page's current generation") {
                    parkedLedger.entries.single().generation shouldBe parked.store.snapshot("p0").generation
                }
                withClue("D9: no unresolved count exists before a death is reconciled") {
                    parkedLedger.consecutiveUnresolved shouldBe emptyMap()
                }
            } finally {
                killScope(parked)
            }

            // Reopen: a NEW store instance over the same durable documents.
            val reopened = freshStore(listOf("p0"))
            withClue("D9: the unresolved entry survives the simulated process death") {
                readLedger().shouldNotBeNull().entries.map { it.pageKey } shouldBe listOf("p0")
            }

            // Startup reconcile (bounded chapter set — this chapter only).
            val reconcileHarness =
                TranslationCoexistenceHarness.create(listOf("p0"), storeOverride = reopened)
            try {
                reconcileStartup(reconcileHarness.manager, reopened)

                val consumed = withClue(
                    "D9 (design §3.2): the startup reconcile must consume the unresolved entry as " +
                        "a consumed attempt — entry removed, the per-page consecutive counter " +
                        "incremented and persisted back into the same file. RED defect: no startup " +
                        "reconcile pass exists",
                ) {
                    readLedger().shouldNotBeNull()
                }
                consumed.entries shouldBe emptyList()
                consumed.consecutiveUnresolved shouldBe mapOf("p0" to 1)

                withClue("D9: one consumed attempt is below the cap — no durable failure yet") {
                    reopened.durableFailure("p0").shouldBeNull()
                }
                withClue("D9: the page below the cap keeps its pre-death state (translation untouched)") {
                    reopened.state.value.getValue("p0").translationStatus shouldBe StageStatus.PENDING
                }
            } finally {
                reconcileHarness.close()
            }
        }

    // ------------------------------------------------------------------
    // 2. three death cycles cap the chapter; auto is refused; force clears
    // ------------------------------------------------------------------

    @Test
    fun `three interrupted death cycles pause the chapter refuse auto entries and yield to explicit force`() =
        runBlocking<Unit> {
            // Cycles 1-2: each death is reconciled into exactly one consumed
            // attempt; the counter accumulates, nothing else changes.
            repeat(2) { cycle ->
                val parked = startParkedBatch()
                try {
                    withClue("D9 cycle ${cycle + 1}: the attempt entry is durable before the parked call") {
                        readLedger().shouldNotBeNull().entries.map { it.pageKey } shouldBe listOf("p0")
                    }
                } finally {
                    killScope(parked)
                }
                val reopened = freshStore(listOf("p0"))
                val reconcileHarness =
                    TranslationCoexistenceHarness.create(listOf("p0"), storeOverride = reopened)
                try {
                    reconcileStartup(reconcileHarness.manager, reopened)
                    withClue("D9 cycle ${cycle + 1}: the death was consumed as exactly one attempt") {
                        readLedger().shouldNotBeNull().consecutiveUnresolved shouldBe mapOf("p0" to cycle + 1)
                    }
                    withClue("D9 cycle ${cycle + 1}: below the cap there is no durable failure") {
                        reopened.durableFailure("p0").shouldBeNull()
                    }
                } finally {
                    reconcileHarness.close()
                }
            }

            // Third strike: reconcile runs against the harness whose chapter
            // queue entry is visible, so the cap's PAUSED flip is observable.
            val parked = startParkedBatch()
            try {
                withClue("D9 cycle 3: the attempt entry is durable before the parked call") {
                    readLedger().shouldNotBeNull().entries.map { it.pageKey } shouldBe listOf("p0")
                }
            } finally {
                killScope(parked)
            }
            val capped = freshStore(listOf("p0"))
            val cappedHarness = TranslationCoexistenceHarness.create(listOf("p0"), storeOverride = capped)
            try {
                val queueEntry =
                    Translation(cappedHarness.source, cappedHarness.manga, cappedHarness.chapterFor(CHAPTER_ID))
                queueEntry.status = Translation.State.QUEUE
                // Both fields must be wired: `_queueState` is the internal
                // delegate the translator mutates; `queueState` is the stored
                // exposed flow (`val queueState = _queueState.asStateFlow()`)
                // the manager's reconcile reads.
                val queueFlow = MutableStateFlow(listOf(queueEntry))
                TranslationCoexistenceHarness.setFields(
                    cappedHarness.translator,
                    listOf(
                        "_queueState" to queueFlow,
                        "queueState" to queueFlow,
                    ),
                )

                reconcileStartup(cappedHarness.manager, capped)

                withClue("D9: the third death left the counter at the cap") {
                    readLedger().shouldNotBeNull().consecutiveUnresolved shouldBe mapOf("p0" to 3)
                }
                val failure = withClue(
                    "D9 (design §3.2 cap): N=3 consecutive unresolved attempts must record " +
                        "DurableFailureMetadata on the page. RED defect: the cap never binds " +
                        "because no startup reconcile exists",
                ) {
                    capped.durableFailure("p0").shouldNotBeNull()
                }
                withClue("D9: the cap failure is INTERRUPTED-class (process death, not a provider fault)") {
                    failure.category.name shouldBe "INTERRUPTED"
                }
                withClue("D9: the cap is never auto-retryable") {
                    failure.nextEligibleRetryAtEpochMs.shouldBeNull()
                }
                withClue("D9: the capped page is PARTIAL") {
                    capped.state.value.getValue("p0").translationStatus shouldBe StageStatus.PARTIAL
                }
                withClue(
                    "D9: the capped chapter's queue state is the existing Translation.State.PAUSED " +
                        "(no new enum)",
                ) {
                    queueEntry.status shouldBe Translation.State.PAUSED
                }

                // Auto-retry loops are refused while capped; the user is not.
                val beforeRefusal = readLedger().shouldNotBeNull()
                withClue(
                    "D9 (design §3.2): an AUTO attempt on a capped page must be REFUSED — the cap " +
                        "binds auto-retry loops, never the user",
                ) {
                    invokeSuspending(
                        capped,
                        "recordAttemptStart",
                        "p0",
                        "d9-provider-hash",
                        "AUTO",
                        capped.currentGeneration,
                    ) shouldBe false
                }
                withClue("D9: the refused auto entry wrote nothing") {
                    readLedger().shouldNotBeNull().entries shouldBe beforeRefusal.entries
                }

                // Explicit user force clears the counter and the durable failure.
                capped.state.value.getValue("p0").prepareForcedRetry()
                withClue("D9: explicit user force clears the cap bookkeeping") {
                    invokeSuspending(capped, "clearAttemptCapForManualRetry", "p0") shouldBe true
                }
                withClue("D9: the durable failure is gone after explicit force") {
                    capped.durableFailure("p0").shouldBeNull()
                }
                withClue("D9: the counter is cleared after explicit force") {
                    readLedger().shouldNotBeNull().consecutiveUnresolved shouldBe emptyMap()
                }
                withClue("D9: auto entries are admitted again after the user's explicit force") {
                    invokeSuspending(
                        capped,
                        "recordAttemptStart",
                        "p0",
                        "d9-provider-hash",
                        "AUTO",
                        capped.currentGeneration,
                    ) shouldBe true
                }
            } finally {
                cappedHarness.close()
            }
        }

}
