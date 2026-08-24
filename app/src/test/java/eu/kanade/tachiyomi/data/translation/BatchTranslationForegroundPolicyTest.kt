package eu.kanade.tachiyomi.data.translation

import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchTranslationForegroundPolicyTest {

    @Test
    fun `foreground host runs while queued or translating work remains`() {
        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(listOf(Translation.State.QUEUE)) shouldBe true
        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(listOf(Translation.State.TRANSLATING)) shouldBe true
        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(
            listOf(Translation.State.TRANSLATED, Translation.State.ERROR),
        ) shouldBe false
        BatchTranslationForegroundPolicy.shouldKeepServiceRunning(emptyList()) shouldBe false
    }
}
