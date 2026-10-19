package eu.kanade.translation.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MutationClassificationTest {

    @Test
    fun `durable failures use failure publication and ordinary updates use direct publication`() {
        classifyMutation(null) shouldBe ArtifactMutation.Direct
        classifyMutation(
            DurableFailureMetadata(
                pageKey = "page.jpg",
                stage = ArtifactStage.TRANSLATION,
                status = ArtifactStageStatus.FAILED_RETRYABLE,
                category = FailureCategory.TRANSIENT,
                retryCount = 1,
                lastFailedAtEpochMs = 1L,
            ),
        ) shouldBe ArtifactMutation.Failure
    }
}
