package eu.kanade.translation.workflow

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.chapter.TranslationFileProvider
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class CleanedImageLifecycleControllerTest {
    @Test
    fun `page retirement retains an image while another page owns it`() {
        val image = mockk<UniFile>(relaxed = true)
        every { image.exists() } returns true
        val provider = mockk<TranslationFileProvider>(relaxed = true)
        every { provider.findPageCleanedImage(any(), any(), any(), any(), any()) } returns image
        val source = mockk<Source>(relaxed = true) {
            every { id } returns 1L
        }
        val manga = mockk<Manga>(relaxed = true) {
            every { id } returns 2L
            every { title } returns "fixture"
        }
        val chapter = mockk<Chapter>(relaxed = true) {
            every { id } returns 3L
            every { name } returns "Chapter 1"
            every { scanlator } returns null
        }
        val controller = CleanedImageLifecycleController(
            applicationScopeProvider = { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) },
            streamRegistryProvider = { TranslationStreamRegistry() },
            providerProvider = { provider },
        )

        controller.retirePageCompanionImage(
            manga = manga,
            chapter = chapter,
            source = source,
            pageKey = "old-page-key",
            imageName = "shared-cleaned.jpg",
            isReferencedElsewhere = { true },
        )

        verify(exactly = 0) { image.delete() }
        verify(exactly = 0) {
            provider.findPageCleanedImage(any(), any(), any(), any(), any())
        }
    }
}
