package eu.kanade.translation.coordinator

import eu.kanade.translation.processing.TranslationStageSignal
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * TachiyomiAT: the stale-token fence guarding the reader status pill's live
 * stage signals. A retried attempt owns the slot; a late callback from a
 * superseded attempt must never overwrite it, and only the owning attempt may
 * clear it.
 */
class TranslationStageSignalBoardTest {

    @Test
    fun `publish stores the newest stage for its own key`() {
        val board = TranslationStageSignalBoard()
        val token = board.nextAttemptToken()

        board.publish("ch:pg", TranslationStageSignal.TRANSLATING, token)

        board.flow.value shouldBe mapOf(
            "ch:pg" to PageStageSignal(TranslationStageSignal.TRANSLATING, token),
        )
    }

    @Test
    fun `a stale attempt callback never overwrites a newer attempt`() {
        val board = TranslationStageSignalBoard()
        val oldAttempt = board.nextAttemptToken()
        val newAttempt = board.nextAttemptToken()

        board.publish("ch:pg", TranslationStageSignal.TRANSLATING, newAttempt)
        // The old attempt's processor lambda fires late (retry already started).
        board.publish("ch:pg", TranslationStageSignal.READING, oldAttempt)

        board.flow.value["ch:pg"] shouldBe PageStageSignal(TranslationStageSignal.TRANSLATING, newAttempt)
    }

    @Test
    fun `same attempt still advances its own stage`() {
        val board = TranslationStageSignalBoard()
        val token = board.nextAttemptToken()

        board.publish("ch:pg", TranslationStageSignal.PREPARING, token)
        board.publish("ch:pg", TranslationStageSignal.READING, token)

        board.flow.value["ch:pg"] shouldBe PageStageSignal(TranslationStageSignal.READING, token)
    }

    @Test
    fun `keys never cross-contaminate between chapters`() {
        val board = TranslationStageSignalBoard()
        val tokenA = board.nextAttemptToken()
        val tokenB = board.nextAttemptToken()

        board.publish("1:pg", TranslationStageSignal.CLEANING, tokenA)
        board.publish("2:pg", TranslationStageSignal.READING, tokenB)

        board.flow.value["1:pg"]?.stage shouldBe TranslationStageSignal.CLEANING
        board.flow.value["2:pg"]?.attemptToken shouldBe tokenB
    }

    @Test
    fun `only the owning attempt clears the slot`() {
        val board = TranslationStageSignalBoard()
        val oldAttempt = board.nextAttemptToken()
        val newAttempt = board.nextAttemptToken()
        board.publish("ch:pg", TranslationStageSignal.FINISHING, newAttempt)

        // The superseded attempt finishes first and tries to clean up.
        board.clear("ch:pg", oldAttempt)
        board.flow.value["ch:pg"] shouldBe PageStageSignal(TranslationStageSignal.FINISHING, newAttempt)

        // The owning attempt's cleanup removes it.
        board.clear("ch:pg", newAttempt)
        board.flow.value shouldBe emptyMap()
    }

    @Test
    fun `clear on an unknown key is a no-op`() {
        val board = TranslationStageSignalBoard()
        val token = board.nextAttemptToken()

        board.clear("ch:pg", token)

        board.flow.value shouldBe emptyMap()
    }

    @Test
    fun `flow emits the accumulated map after each mutation`() = runTest {
        val board = TranslationStageSignalBoard()
        val token = board.nextAttemptToken()

        board.publish("ch:pg", TranslationStageSignal.PREPARING, token)

        board.flow.first() shouldBe mapOf(
            "ch:pg" to PageStageSignal(TranslationStageSignal.PREPARING, token),
        )
    }
}
