package eu.kanade.translation.workflow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class TranslationManagerStateOwnershipFenceTest {

    @Test
    fun movedStateStaysPrivateToItsConsumingCollaborator() {
        val requestStateFields = setOf(
            "pendingRequestWriteVersions",
            "pendingRequestGenerationCounters",
            "downloadAttachGenerations",
            "pendingGroupIdSequence",
        )
        val durableStateFields = setOf(
            "durableStatusCache",
            "durableDocumentCache",
        )
        val managerFields = TranslationManager::class.java.declaredFields.map { it.name }.toSet()

        (managerFields intersect (requestStateFields + durableStateFields)) shouldBe emptySet()
        requestStateFields.all(::isPrivateFieldOfRequestCoordinator) shouldBe true
        durableStateFields.all(::isPrivateFieldOfDurableResolver) shouldBe true
    }

    private fun isPrivateFieldOfRequestCoordinator(name: String): Boolean =
        Modifier.isPrivate(TranslationRequestCoordinator::class.java.getDeclaredField(name).modifiers)

    private fun isPrivateFieldOfDurableResolver(name: String): Boolean =
        Modifier.isPrivate(DurableChapterStatusResolver::class.java.getDeclaredField(name).modifiers)
}
