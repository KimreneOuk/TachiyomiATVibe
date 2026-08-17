package eu.kanade.translation.scheduling

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * Ticket 02 — boundary data-contract coverage.
 *
 * These tests pin the shape of the production types ([PreparedPage],
 * [TranslationStageEvent], [TranslationStageListener]) via reflection and
 * direct exercise — they do NOT re-implement pipeline logic. The runtime
 * behavior (durability writes, terminal classification, stale rejection) is
 * covered by [PreparedPageRuntimeBoundaryTest], which drives the real
 * production helpers.
 */
class PreparedPageBoundaryTest {

    /**
     * The handoff type MUST NOT expose a bitmap/decoded-image field. Verified
     * by reflection so a future field addition is caught by the test, not by an
     * OOM in the field.
     */
    @Test
    fun `prepared page fields are all durable identity types`() {
        val offendingTypes = PreparedPage::class.memberProperties
            .filter { prop -> prop.returnType.classifier.toString() !in ALLOWED_FIELD_TYPE_NAMES }
        offendingTypes.map { it.name } shouldBe emptyList()
    }

    @Test
    fun `prepared page exposes exactly the durable identity fields the boundary requires`() {
        val actualFields = PreparedPage::class.memberProperties.map { it.name }.toSet()
        actualFields shouldContainExactly setOf(
            "pageKey",
            "chapterId",
            "mangaId",
            "sourceId",
            "cleanedImageName",
            "generation",
            "pageVersion",
            "blockFingerprints",
            "isTerminal",
        )
    }

    @Test
    fun `stage events are a closed four-value contract tied to reader labels`() {
        TranslationStageEvent.entries.map { it.name } shouldContainExactly listOf(
            "READING",
            "CLEANING",
            "TRANSLATING",
            "RENDERING",
        )
    }

    @Test
    fun `listener is a functional interface so callers can pass a lambda`() {
        val captured = mutableListOf<Pair<String, TranslationStageEvent>>()
        val listener = TranslationStageListener { pageKey, stage ->
            captured.add(pageKey to stage)
        }
        listener.onStageEntered("p0", TranslationStageEvent.READING)
        listener.onStageEntered("p0", TranslationStageEvent.TRANSLATING)
        captured shouldContainExactly listOf(
            "p0" to TranslationStageEvent.READING,
            "p0" to TranslationStageEvent.TRANSLATING,
        )
    }
}

private val ALLOWED_FIELD_TYPE_NAMES: Set<String> = setOf(
    "class kotlin.String",
    "class kotlin.Long",
    "class kotlin.Boolean",
    "class kotlin.collections.List",
)
