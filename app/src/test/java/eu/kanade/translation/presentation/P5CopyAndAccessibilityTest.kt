package eu.kanade.translation.presentation

import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests truthful notification copy and accessibility labels.
 */
class P5CopyAndAccessibilityTest {

    private fun callTruth(method: String, arity: Int, defect: String, vararg args: Any?): Any? {
        val instance = try {
            Class.forName("eu.kanade.translation.presentation.TranslationUiTruth")
                .getDeclaredField("INSTANCE").get(null)
        } catch (missing: ReflectiveOperationException) {
            throw AssertionError(
                "TranslationUiTruth is required for the presentation projection",
                missing,
            )
        }
        val found = instance::class.java.declaredMethods.firstOrNull {
            it.name == method && it.parameterTypes.size == arity
        }
        return found?.apply { isAccessible = true }?.invoke(instance, *args)
            ?: throw AssertionError(defect)
    }

    // ------------------------------------------------------------------
    // Notification copy as a pure projection
    // ------------------------------------------------------------------

    private fun notificationCopyOf(
        chapterName: String,
        snapshot: TranslationProgressSnapshot?,
    ): Any = try {
        val clazz = Class.forName("eu.kanade.translation.presentation.TranslationNotificationCopy")
        val instance = clazz.getDeclaredField("INSTANCE").get(null)
        instance::class.java.declaredMethods
            .first { it.name == "of" && it.parameterTypes.size == 2 }
            .apply { isAccessible = true }(instance, chapterName, snapshot)
    } catch (missing: ReflectiveOperationException) {
        throw AssertionError(
            "T917 P5 RED defect (notification copy): TranslationNotificationCopy does not " +
                "exist — the foreground service builds notification text inline, so truthful " +
                "terminal/unknown-total/not-saved/paused copy is untestable and unverified",
            missing,
        )
    }

    private fun prop(value: Any?, name: String): Any? =
        value?.javaClass?.getMethod("get" + name.replaceFirstChar { it.uppercase() })?.invoke(value)

    private fun actionsOf(copy: Any): Set<String> =
        (prop(copy, "actions") as Set<*>).map { it.toString() }.toSet()

    @Test
    fun `running trusted totals render terminal progress on the notification`() {
        val snapshot = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATING)
            .copy(
                donePages = 1,
                totalPages = 2,
                totalStages = 8,
                expectedPageCountTrusted = true,
            )
        val copy = notificationCopyOf("Chapter A", snapshot)
        withClue("trusted totals keep the numeric terminal progress") {
            prop(copy, "text").toString() shouldBe "1 of 2 pages translated"
            prop(copy, "ongoing") shouldBe true
            actionsOf(copy) shouldBe setOf("STOP")
        }
    }

    @Test
    fun `running untrusted totals render the unknown-total fact without a percentage`() {
        val snapshot = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATING)
            .copy(
                donePages = 3,
                totalPages = 3,
                totalStages = 12,
                // Partial download: 3 available pages, source total unknown.
                expectedPageCountTrusted = false,
            )
        val copy = notificationCopyOf("Chapter A", snapshot)
        withClue("an unknown source total must never look like a completed chapter") {
            val text = prop(copy, "text").toString()
            text shouldBe "3 pages available · source total unknown"
            text.contains("%") shouldBe false
            prop(copy, "ongoing") shouldBe true
            actionsOf(copy) shouldBe setOf("STOP")
        }
    }

    @Test
    fun `a persistence rejection names the not saved truth on the notification`() {
        val snapshot = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATING)
            .copy(
                nonDurableFailure = true,
                nonDurableFailureReason = "T924 envelope plan publication rejected: stale manifest snapshot",
            )
        val copy = notificationCopyOf("Chapter A", snapshot)
        withClue("the not-saved warning must be visible, never completion copy") {
            //   the typed rejection reason rides along (bounded) so
            // the notification names WHICH seam rejected the publication.
            prop(copy, "text").toString() shouldBe
                "Translation not saved — retry required: " +
                "T924 envelope plan publication rejected: stale manifest snapshot"
            actionsOf(copy) shouldBe setOf("RETRY", "STOP")
        }
    }

    @Test
    fun `a persistence rejection without a reason keeps the bare not saved copy`() {
        val snapshot = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATING)
            .copy(
                nonDurableFailure = true,
                nonDurableFailureReason = null,
            )
        val copy = notificationCopyOf("Chapter A", snapshot)
        withClue("no reason — the historical not-saved copy is unchanged") {
            prop(copy, "text").toString() shouldBe "Translation not saved — retry required"
            actionsOf(copy) shouldBe setOf("RETRY", "STOP")
        }
    }

    @Test
    fun `a persistence rejection reason is truncated on the notification`() {
        val snapshot = TranslationProgressSnapshot.empty(1L, Translation.State.TRANSLATING)
            .copy(
                nonDurableFailure = true,
                nonDurableFailureReason = "x".repeat(500),
            )
        val copy = notificationCopyOf("Chapter A", snapshot)
        val text = prop(copy, "text").toString()
        //   bounded reason — 120 chars after the fixed prefix.
        text shouldBe "Translation not saved — retry required: " + "x".repeat(120)
    }

    @Test
    fun `an automatic retry pause offers no retry button and says when it retries`() {
        val snapshot = TranslationProgressSnapshot.empty(1L, Translation.State.PAUSED)
            .copy(
                pauseReason = "provider rate limited",
                nextEligibleRetryAtEpochMs = 9_000L,
            )
        val copy = notificationCopyOf("Chapter A", snapshot)
        val text = prop(copy, "text").toString()
        withClue("batch-owned pauses retry automatically; no explicit Retry button") {
            text.contains("Paused — provider rate limited") shouldBe true
            text.contains("automatic retry") shouldBe true
            actionsOf(copy) shouldBe setOf("STOP")
            prop(copy, "retryAtEpochMs") shouldBe 9_000L
            prop(copy, "ongoing") shouldBe false
        }
    }

    @Test
    fun `an aborted batch stays visible as cancelled with an explicit retry`() {
        val snapshot = TranslationProgressSnapshot.empty(1L, Translation.State.ERROR)
            .copy(
                aborted = true,
                abortedReason = "user stop requested",
                cancelledPages = 2,
            )
        val copy = notificationCopyOf("Chapter A", snapshot)
        withClue("cancellation of paid work must be acknowledged, not silent") {
            prop(copy, "text").toString() shouldBe "Batch translation cancelled — saved pages kept"
            prop(copy, "ongoing") shouldBe false
            actionsOf(copy) shouldBe setOf("RETRY")
        }
    }

    // ------------------------------------------------------------------
    // Partial-download decision body
    // ------------------------------------------------------------------

    private fun partialBody(decisions: List<Pair<Int, Int?>>): String = try {
        val truth = Class.forName("eu.kanade.translation.presentation.TranslationUiTruth")
            .getDeclaredField("INSTANCE").get(null)
        val decisionClass = Class.forName("eu.kanade.translation.presentation.TranslationUiTruth\$PartialDecision")
        val constructor = decisionClass.constructors.first { it.parameterCount == 2 }
        val args = decisions.map { (downloaded, expected) ->
            constructor.newInstance(downloaded, expected)
        }
        val array = java.lang.reflect.Array.newInstance(decisionClass, args.size)
        args.forEachIndexed { i, d -> java.lang.reflect.Array.set(array, i, d) }
        truth::class.java.declaredMethods
            .first { it.name == "partialDownloadBody" && it.parameterTypes.size == 1 }
            .apply { isAccessible = true }(truth, array) as String
    } catch (missing: ReflectiveOperationException) {
        throw AssertionError(
            "T917 P5 RED defect (partial download copy): TranslationUiTruth has no " +
                "partialDownloadBody — the finish-first/translate-subset decision copy is " +
                "hardcoded inline in the Compose dialog and untestable",
            missing,
        )
    }

    @Test
    fun `a known source total states what is downloaded and the decision`() {
        partialBody(listOf(3 to 12)) shouldBe
            "3 of 12 pages are downloaded. Translate the pages that exist now, or finish the download first?"
    }

    @Test
    fun `an unknown source total says the total is unknown`() {
        partialBody(listOf(3 to null)) shouldBe
            "The download is still in progress and the page total is unknown. " +
            "Translate the pages that exist now, or finish the download first?"
    }

    @Test
    fun `a multi chapter decision describes every chapter not only the first`() {
        // A multi-chapter decision must describe every chapter, not only the first.
        val body = partialBody(listOf(2 to 12, 5 to 20))
        withClue("both chapters' counts must be visible in the decision body") {
            body shouldContain "2 of 12"
            body shouldContain "5 of 20"
            body shouldContain "finish the download first?"
        }
    }

    // ------------------------------------------------------------------
    // Chapter indicator + page mini chips (labels, not color alone)
    // ------------------------------------------------------------------

    @Test
    fun `chapter indicator labels warn on ready with warnings without color`() {
        val defect =
            "T917 P5 RED defect (accessibility): no pure chapter-indicator truth exists — " +
                "the indicator conveys READY_WITH_WARNINGS by tint alone"
        val warnings = callTruth("forChapterIndicator", 2, defect, Translation.State.READY_WITH_WARNINGS, null)
        val description = prop(warnings, "contentDescription").toString()
        withClue("warnings must be discoverable without color") {
            description.lowercase().contains("warning") shouldBe true
            description.lowercase().contains("ready") shouldBe true
        }

        val translated = callTruth("forChapterIndicator", 2, defect, Translation.State.TRANSLATED, null)
        prop(translated, "contentDescription").toString() shouldBe
            "Chapter translation ready"
    }

    @Test
    fun `drawer page mini chips have non empty semantic labels for every stage`() {
        val defect =
            "T917 P5 RED defect (accessibility): no pure page mini chip label exists — " +
                "the drawer's page overview conveys failed/partial state by icon and tint only"

        fun labelOf(row: TranslationProgressSnapshot.Page): String =
            callTruth("pageMiniChipLabel", 1, defect, row) as String

        val done = TranslationProgressSnapshot.Page(
            pageKey = "p0",
            index = 1,
            stage = eu.kanade.translation.model.TranslationProgressStage.DONE,
            displayReady = true,
            processed = true,
        )
        withClue("terminal success chips say translated") {
            labelOf(done) shouldBe "Translated."
        }
        withClue("failed chips say failed with retry") {
            labelOf(
                TranslationProgressSnapshot.Page(
                    pageKey = "p1",
                    index = 2,
                    stage = eu.kanade.translation.model.TranslationProgressStage.FAILED,
                ),
            ) shouldBe "Translation failed — retry available."
        }
        withClue("partial chips warn without calling the page translated") {
            labelOf(
                TranslationProgressSnapshot.Page(
                    pageKey = "p2",
                    index = 3,
                    stage = eu.kanade.translation.model.TranslationProgressStage.DONE,
                    displayReady = true,
                    processed = true,
                    partial = true,
                ),
            ) shouldBe "Partial translation — retry available."
        }
        withClue("queued chips never imply work started") {
            labelOf(
                TranslationProgressSnapshot.Page(
                    pageKey = "p3",
                    index = 4,
                    stage = eu.kanade.translation.model.TranslationProgressStage.QUEUED,
                ),
            ) shouldBe "Queued."
        }
    }
}
