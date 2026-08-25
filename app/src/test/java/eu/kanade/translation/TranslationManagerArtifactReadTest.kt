package eu.kanade.translation

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

class TranslationManagerArtifactReadTest {

    @TempDir
    lateinit var mangaDir: File

    @AfterEach
    fun restoreProductionProbe() {
        ChapterTranslationStore.artifactImageProbe = eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
    }

    private fun installImageProbe() {
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 100) }
    }

    private fun page() = PageTranslation(
        blocks = mutableListOf(
            eu.kanade.translation.model.TranslationBlock(
                text = "source",
                translation = "hello",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun writeLegacyChapter(chapterName: String) {
        File(mangaDir, "${chapterName}_images").mkdirs()
        File(mangaDir, "${chapterName}_images/page.cleaned.jpg").writeBytes(byteArrayOf(1))
        File(mangaDir, "$chapterName.json").writeText(
            Json.encodeToString(mapOf("page.jpg" to page())),
        )
    }

    private fun translationFile(chapterName: String): UniFile =
        FakeUniFile(parent = null, backing = mangaDir).findFile("$chapterName.json")!!

    private fun newManager(file: UniFile): TranslationManager {
        val source = mockk<Source>(relaxed = true)
        every { source.id } returns 77L
        val provider = mockk<TranslationProvider>(relaxed = true)
        every { provider.findTranslationFile(any(), any(), any(), any()) } returns file
        every { provider.findMangaDir(any(), any()) } returns FakeUniFile(parent = null, backing = mangaDir)
        val sourceManager = mockk<SourceManager>(relaxed = true)
        every { sourceManager.get(77L) } returns source
        val translator = mockk<ChapterTranslator>(relaxed = true)
        every { translator.queueState } returns MutableStateFlow(emptyList())
        val activeStores = ActiveChapterStoreRegistry()

        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateInstance.invoke(unsafe, TranslationManager::class.java) as TranslationManager
        setField(manager, "provider", provider)
        setField(manager, "sourceManager", sourceManager)
        setField(manager, "translator", translator)
        setField(manager, "activeStores", activeStores)
        setField(manager, "legacyPageJson", Json { ignoreUnknownKeys = true })
        setField(manager, "durableStatusCache", ConcurrentHashMap<Any, Any>())
        return manager
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val field: Field = cls.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("Field $fieldName not found on ${target.javaClass}")
    }

    @Test
    fun `artifact authority status and reader reads survive a fresh manager`() = runTest {
        installImageProbe()
        writeLegacyChapter("Chapter 1")
        val file = translationFile("Chapter 1")
        val store = ChapterTranslationStore.open(file)
        store.updatePage("page.jpg") { current ->
            current!!.apply { blocks.single().userEditedAt = 42L }
        }
        store.closeAndFlush()

        ChapterTranslationStore.probeArtifactManifest(file).manifest?.authority shouldBe ManifestAuthority.ARTIFACTS
        ChapterTranslationStore.probeArtifactManifest(file).manifest?.expectedPageCount shouldBe 1
        ChapterTranslationStore.probeArtifactManifest(file).manifest?.expectedPageCountTrusted shouldBe false
        file.delete() shouldBe true
        File(mangaDir, "Chapter 1.summary.json").exists() shouldBe false

        val manager = newManager(file)
        manager.getChapterTranslationStatus(42L, "Chapter 1", null, "Manga", 77L) shouldBe
            Translation.State.READY_WITH_WARNINGS
        val pages = manager.getChapterTranslationForReader(42L, "Chapter 1", null, "Manga", mockk<Source>(relaxed = true))
        pages["page.jpg"]?.blocks?.single()?.translation shouldBe "hello"
        pages["page.jpg"]?.blocks?.single()?.userEditedAt shouldBe 42L
    }

    @Test
    fun `artifact status reports warnings for a partial page after restart`() = runTest {
        installImageProbe()
        writeLegacyChapter("Chapter 4")
        val file = translationFile("Chapter 4")
        val store = ChapterTranslationStore.open(file)
        store.updatePage("page.jpg") { current ->
            current!!.copy(
                blocks = mutableListOf(),
                translationStatus = StageStatus.PARTIAL,
                inpaintStatus = StageStatus.PENDING,
                renderStatus = StageStatus.PENDING,
                cleanedImageName = null,
            )
        }
        store.closeAndFlush()

        ChapterTranslationStore.probeArtifactManifest(file).manifest?.authority shouldBe ManifestAuthority.ARTIFACTS
        val manager = newManager(file)
        manager.getChapterTranslationStatus(45L, "Chapter 4", null, "Manga", 77L) shouldBe
            Translation.State.READY_WITH_WARNINGS
    }

    @Test
    fun `in-flight artifact page does not report chapter error`() = runTest {
        installImageProbe()
        val root = FakeUniFile(parent = null, backing = mangaDir)
        File(mangaDir, "Chapter 5.json").createNewFile()
        val file = root.findFile("Chapter 5.json")!!
        File(mangaDir, "Chapter 5_images").mkdirs()
        File(mangaDir, "Chapter 5_images/p1.cleaned.jpg").writeBytes(byteArrayOf(1))
        val store = ChapterTranslationStore.lazy(
            fileCreator = { error("in-flight fixture must use artifacts") },
            artifactParent = root,
            artifactFileName = "Chapter 5.json",
        )
        store.preRegisterPages(listOf("p1.jpg", "p2.jpg"))
        store.updatePage("p1.jpg") { page().copy(cleanedImageName = "p1.cleaned.jpg") }
        store.updatePage("p2.jpg") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.flush()
        store.closeAndFlush()
        file.delete()

        val manager = newManager(file)
        manager.getChapterTranslationStatus(46L, "Chapter 5", null, "Manga", 77L) shouldBe
            Translation.State.READY_WITH_WARNINGS
    }

    @Test
    fun `fresh orphaned cleaned image stays protected during write commit window`() {
        val now = System.currentTimeMillis()
        isFreshOrphanedCleanedImage(now, now) shouldBe true
        isFreshOrphanedCleanedImage(now - 1_000L, now) shouldBe true
        isFreshOrphanedCleanedImage(now - 31_000L, now) shouldBe false
        isFreshOrphanedCleanedImage(0L, now) shouldBe true
    }

    @Test
    fun `legacy authority chapter still resolves from the flat file`() = runTest {
        writeLegacyChapter("Chapter 2")
        val file = translationFile("Chapter 2")
        val manager = newManager(file)
        manager.getChapterTranslationStatus(43L, "Chapter 2", null, "Manga", 77L) shouldBe Translation.State.READY_WITH_WARNINGS
        val pages = manager.getChapterTranslationForReader(43L, "Chapter 2", null, "Manga", mockk<Source>(relaxed = true))
        pages["page.jpg"]?.blocks?.single()?.translation shouldBe "hello"
    }

    @Test
    fun `corrupt flat file is quarantined without deletion`() {
        val original = File(mangaDir, "Chapter 3.json")
        original.writeText("{ not json")
        val file = translationFile("Chapter 3").also { check(it.exists()) }
        val manager = newManager(file)

        manager.getChapterTranslationStatus(44L, "Chapter 3", null, "Manga", 77L) shouldBe Translation.State.NOT_TRANSLATED
        manager.getChapterTranslation(file).isEmpty() shouldBe true

        original.exists() shouldBe false
        File(mangaDir, "Chapter 3.json.corrupt").readText() shouldBe "{ not json"
    }

    @Test
    fun `corrupt quarantine preserves an existing target copy`() {
        val original = File(mangaDir, "Chapter 6.json")
        val corruptBytes = "{ corrupt-v2".toByteArray()
        original.writeBytes(corruptBytes)
        File(mangaDir, "Chapter 6.json.corrupt").writeText("older-quarantine")
        val file = translationFile("Chapter 6").also { check(it.exists()) }
        val manager = newManager(file)

        manager.getChapterTranslation(file).isEmpty() shouldBe true

        original.exists() shouldBe false
        File(mangaDir, "Chapter 6.json.corrupt").readText() shouldBe "older-quarantine"
        mangaDir.listFiles()!!.filter { it.name.startsWith("Chapter 6.json.corrupt.") }
            .map { it.readBytes().toList() } shouldBe listOf(corruptBytes.toList())
    }

    @Test
    fun `corrupt quarantine retains the source when every destination races`() {
        File(mangaDir, "Chapter 8.json").writeText("{ corrupt-v3")
        val manager = newManager(translationFile("Chapter 8"))
        val io = eu.kanade.translation.artifact.FakeChapterDocumentIo()
        val corruptBytes = "{ corrupt-v3".toByteArray()
        io.files["Chapter 8.json"] = corruptBytes
        io.beforeRenameAttempt = { from, to ->
            if (from == "Chapter 8.json" && to.startsWith("Chapter 8.json.corrupt")) {
                io.files[to] = "external-quarantine".toByteArray()
            }
        }

        manager.quarantineCorruptDocument(io, "Chapter 8.json") shouldBe null
        io.files["Chapter 8.json"] shouldBe corruptBytes
        io.files.filterKeys { it.startsWith("Chapter 8.json.corrupt") }
            .values.forEach { it shouldBe "external-quarantine".toByteArray() }
    }

    @Test
    fun `recoverable missing legacy input is retried by the same manager`() {
        File(mangaDir, "Chapter 7.json").createNewFile()
        val file = translationFile("Chapter 7")
        val manager = newManager(file)
        manager.getChapterTranslationStatus(47L, "Chapter 7", null, "Manga", 77L) shouldBe
            Translation.State.NOT_TRANSLATED

        File(mangaDir, "Chapter 7.json").writeText(
            Json.encodeToString(mapOf("page.jpg" to page())),
        )
        manager.getChapterTranslationStatus(47L, "Chapter 7", null, "Manga", 77L) shouldBe
            Translation.State.READY_WITH_WARNINGS
    }
}
