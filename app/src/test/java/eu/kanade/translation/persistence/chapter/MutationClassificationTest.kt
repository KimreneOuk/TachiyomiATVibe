package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.artifact.GroupCommitConfiguration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MutationClassificationTest {
    private data class GoldenRow(
        val bits: String,
        val mutation: ArtifactMutation,
    )

    @Test
    fun `all feasible five condition rows match pre extraction dispatch goldens`() {
        val goldenRows = loadGoldenRows()
        val possibleRows = buildSet {
            for (groupCommit in listOf(false, true)) {
                for (rendered in listOf(false, true)) {
                    for (textless in listOf(false, true)) {
                        for (manual in listOf(false, true)) {
                            for (failure in listOf(false, true)) {
                                // Rendered pages require translated blocks, while textless pages require none.
                                // Manual edits require a block, so they cannot coexist with textless terminal.
                                if ((rendered && textless) || (textless && manual)) continue
                                add(listOf(groupCommit, rendered, textless, manual, failure).bits())
                            }
                        }
                    }
                }
            }
        }
        goldenRows.size shouldBe possibleRows.size
        goldenRows.map { it.bits }.toSet() shouldBe possibleRows

        val previousGroupCommit = GroupCommitConfiguration.enabled
        try {
            goldenRows.forEach { row ->
                val conditions = row.bits.map { it == '1' }
                val (groupCommit, rendered, textless, manual, failure) = conditions
                val updated = page(
                    rendered = rendered,
                    textless = textless,
                    manualEdit = manual,
                )
                GroupCommitConfiguration.enabled = groupCommit

                updated.hasRenderedResult shouldBe rendered
                updated.isTextlessTerminal shouldBe textless
                updated.blocks.any { it.userEditedAt != null } shouldBe manual

                val durableFailure = if (failure) {
                    DurableFailureMetadata(
                        pageKey = "page.jpg",
                        stage = ArtifactStage.TRANSLATION,
                        status = ArtifactStageStatus.FAILED_RETRYABLE,
                        category = FailureCategory.TRANSIENT,
                        retryCount = 1,
                        lastFailedAtEpochMs = 1L,
                    )
                } else {
                    null
                }
                classifyMutation(updated, durableFailure) shouldBe row.mutation
            }
        } finally {
            GroupCommitConfiguration.enabled = previousGroupCommit
        }
    }

    private fun page(
        rendered: Boolean,
        textless: Boolean,
        manualEdit: Boolean,
    ): PageTranslation {
        val blocks = if (textless) {
            mutableListOf()
        } else {
            mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "translated",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                    userEditedAt = if (manualEdit) 1L else null,
                ),
            )
        }
        return PageTranslation(
            blocks = blocks,
            ocrStatus = if (rendered || textless) StageStatus.READY else StageStatus.PENDING,
            translationStatus = when {
                textless -> StageStatus.SKIPPED
                rendered -> StageStatus.READY
                else -> StageStatus.PENDING
            },
            inpaintStatus = when {
                textless -> StageStatus.SKIPPED
                rendered -> StageStatus.READY
                else -> StageStatus.RUNNING
            },
            renderStatus = when {
                textless -> StageStatus.SKIPPED
                rendered -> StageStatus.READY
                else -> StageStatus.PENDING
            },
            originalImageFallback = rendered,
            sourceFileName = "page.jpg",
        )
    }

    private fun loadGoldenRows(): List<GoldenRow> = checkNotNull(
        javaClass.getResourceAsStream("/mutation-classification-golden.tsv"),
    ).bufferedReader().useLines { lines ->
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (bits, mutation) = line.split('=', limit = 2)
                check(bits.length == 5 && bits.all { it == '0' || it == '1' })
                GoldenRow(bits, ArtifactMutation.valueOf(mutation))
            }
            .toList()
    }

    private fun List<Boolean>.bits(): String = joinToString("") { if (it) "1" else "0" }
}
