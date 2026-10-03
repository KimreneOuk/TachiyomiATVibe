package eu.kanade.translation.workflow

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
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

    @Test
    fun `orphaned identity sidecars are swept after grace but fresh and paired sidecars remain`() {
        val staleImageName = "page-stale.cleaned.jpg"
        val freshImageName = "page-fresh.cleaned.jpg"
        val pairedImageName = "page-paired.cleaned.jpg"
        val staleSidecar = mockk<UniFile>(relaxed = true)
        val freshSidecar = mockk<UniFile>(relaxed = true)
        val pairedSidecar = mockk<UniFile>(relaxed = true)
        val pairedImage = mockk<UniFile>(relaxed = true)
        val directory = mockk<UniFile>(relaxed = true)
        val provider = mockk<TranslationFileProvider>(relaxed = true)
        val source = mockk<Source>(relaxed = true) {
            every { id } returns 1L
        }
        val now = System.currentTimeMillis()

        every { staleSidecar.name } returns "$staleImageName.identity.json"
        every { staleSidecar.isFile } returns true
        every { staleSidecar.lastModified() } returns now - 60_000L
        every { staleSidecar.delete() } returns true
        every { freshSidecar.name } returns "$freshImageName.identity.json"
        every { freshSidecar.isFile } returns true
        every { freshSidecar.lastModified() } returns now
        every { pairedSidecar.name } returns "$pairedImageName.identity.json"
        every { pairedSidecar.isFile } returns true
        every { pairedSidecar.lastModified() } returns now - 60_000L
        every { pairedImage.exists() } returns true
        every { directory.listFiles() } returns arrayOf(staleSidecar, freshSidecar, pairedSidecar)
        every { directory.findFile(staleImageName) } returns null
        every { directory.findFile(freshImageName) } returns null
        every { directory.findFile(pairedImageName) } returns pairedImage
        every { directory.findFile("$staleImageName.identity.json") } returns staleSidecar
        every { directory.findFile("$freshImageName.identity.json") } returns freshSidecar
        every { directory.findFile("$pairedImageName.identity.json") } returns pairedSidecar
        every { provider.findCompanionImageDir(any(), any(), any(), any()) } returns directory

        val store = ChapterTranslationStore(artifactParentResolver = null)
        val controller = CleanedImageLifecycleController(
            applicationScopeProvider = { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) },
            streamRegistryProvider = { TranslationStreamRegistry() },
            providerProvider = { provider },
        )

        controller.sweepOrphanedCleanedImages(
            store = store,
            chapterId = 3L,
            chapterName = "Chapter 1",
            scanlator = null,
            mangaTitle = "fixture",
            source = source,
            mangaId = 2L,
        )

        verify(exactly = 1) { staleSidecar.delete() }
        verify(exactly = 0) { freshSidecar.delete() }
        verify(exactly = 0) { pairedSidecar.delete() }
    }
}
