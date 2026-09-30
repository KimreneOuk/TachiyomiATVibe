package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.persistence.journal.ChapterJournalCredit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StagedJournalCreditOwnershipTest {

    @Test
    fun `staging keeps the first retained credit and releases a later one`() {
        val releases = mutableListOf<String>()
        val kept = ChapterJournalCredit { releases += "kept" }
        val superseded = ChapterJournalCredit { releases += "superseded" }
        kept.retain() shouldBe true
        superseded.retain() shouldBe true

        retainStagedJournalCredit(kept, superseded) shouldBe kept
        releases shouldBe listOf("superseded")

        kept.releaseIfRetained() shouldBe true
        releases shouldBe listOf("superseded", "kept")
    }

    @Test
    fun `first staged credit is retained for the page`() {
        var released = false
        val incoming = ChapterJournalCredit { released = true }

        retainStagedJournalCredit(existing = null, incoming = incoming) shouldBe incoming
        incoming.releaseIfRetained() shouldBe true
        released shouldBe true
    }
}
