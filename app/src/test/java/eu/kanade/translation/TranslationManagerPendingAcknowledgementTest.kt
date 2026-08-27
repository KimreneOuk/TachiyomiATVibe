package eu.kanade.translation

import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TranslationManagerPendingAcknowledgementTest {

    @Test
    fun `starting acknowledgement publishes immediately without dropping other requests`() {
        val current = mapOf(
            7L to TranslationRequestState(7L, TranslationRequestPhase.WAITING_FOR_DOWNLOAD),
        )

        val acknowledged = acknowledgePendingTranslationState(current, listOf(7L, 8L))

        acknowledged[7L]?.phase shouldBe TranslationRequestPhase.STARTING
        acknowledged[8L]?.phase shouldBe TranslationRequestPhase.STARTING
    }
}
