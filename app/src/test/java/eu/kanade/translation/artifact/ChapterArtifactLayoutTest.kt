package eu.kanade.translation.artifact

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ChapterArtifactLayoutTest {

    @Test
    fun `derives sidecar names from the translation file name`() {
        val layout = ChapterArtifactLayout.fromTranslationFileName("Group_Chapter 1.json")
        layout.chapterKey shouldBe "Group_Chapter 1"
        layout.manifestFileName shouldBe "Group_Chapter 1.manifest.json"
        layout.artifactRootDirectoryName shouldBe "Group_Chapter 1_artifacts"
    }

    @Test
    fun `page directory identity is readable plus a stable hash of the key`() {
        val layout = ChapterArtifactLayout("c")
        layout.pageSegment("pg/1.jpg").startsWith("pg_1.jpg-") shouldBe true
        layout.pageSegment("pg/1.jpg").length shouldBe "pg_1.jpg-".length + 64
        layout.imageDirectory("pg/1.jpg") shouldBe "c_artifacts/images/${layout.pageSegment("pg/1.jpg")}"
    }

    @Test
    fun `distinct keys sanitizing identically never share a directory`() {
        val layout = ChapterArtifactLayout("c")
        val one = layout.pageSegment("pg/1")
        val two = layout.pageSegment("pg_1")
        val three = layout.pageSegment("pg\\1")
        one shouldNotBe two
        two shouldNotBe three
        one shouldNotBe three
    }

    @Test
    fun `unsafe segments are hashed into safe contained names`() {
        val layout = ChapterArtifactLayout("c")
        val traversal = layout.stageArtifactFile("p", ArtifactStage.OCR, "../evil")
        val separator = layout.stageArtifactFile("p", ArtifactStage.OCR, "a/b")
        traversal shouldNotBe separator
        traversal.startsWith("c_artifacts/artifacts/${layout.pageSegment("p")}/ocr/f-") shouldBe true
        separator.startsWith("c_artifacts/artifacts/${layout.pageSegment("p")}/ocr/f-") shouldBe true
    }

    @Test
    fun `control characters and traversal components cannot escape the tree`() {
        val layout = ChapterArtifactLayout("c")
        val traversing = layout.generationFile("../../outside")
        traversing.startsWith("c_artifacts/generations/g-") shouldBe true
        ChapterArtifactLayout.isSafeSegment(traversing.substringAfterLast('/').removeSuffix(".json")) shouldBe true
        layout.isManagedPath(traversing) shouldBe true
    }

    @Test
    fun `file extensions are strictly validated`() {
        val layout = ChapterArtifactLayout("c")
        layout.imageFile("p", "g", "fp", "jpg").startsWith(
            "c_artifacts/images/${layout.pageSegment("p")}/${layout.generationSegment("g")}-f-",
        ) shouldBe true
        assertThrows<IllegalArgumentException> { layout.imageFile("p", "g", "fp", "jpg/../../x") }
        assertThrows<IllegalArgumentException> { layout.imageFile("p", "g", "fp", "") }
        assertThrows<IllegalArgumentException> { layout.imageFile("p", "g", "fp", "waytoolongextension") }
    }

    @Test
    fun `empty and dot segments are rejected as unsafe`() {
        ChapterArtifactLayout.isSafeSegment("") shouldBe false
        ChapterArtifactLayout.isSafeSegment(".") shouldBe false
        ChapterArtifactLayout.isSafeSegment("..") shouldBe false
        ChapterArtifactLayout.isSafeSegment("a/b") shouldBe false
        ChapterArtifactLayout.isSafeSegment("a\\b") shouldBe false
        ChapterArtifactLayout.isSafeSegment("a\u0000b") shouldBe false
        ChapterArtifactLayout.isSafeSegment("ok-name.1") shouldBe true
    }

    @Test
    fun `glossary names follow the layout contract`() {
        val layout = ChapterArtifactLayout("c")
        layout.glossaryFile(2) shouldBe "c_artifacts/glossary/chapter.glossary.2.json"
        layout.generationFile("gen1") shouldBe "c_artifacts/generations/${layout.generationSegment("gen1")}.json"
    }

    @Test
    fun `managed path containment rejects unmanaged paths`() {
        val layout = ChapterArtifactLayout("c")
        layout.isManagedPath("c_artifacts/images/p/g.jpg") shouldBe true
        layout.isManagedPath("c_artifacts/images") shouldBe true
        layout.isManagedPath("Chapter 1.json") shouldBe false
        layout.isManagedPath("Chapter 1_images/p.png") shouldBe false
        layout.isManagedPath("c_artifacts_evil/images/p.jpg") shouldBe false
    }

    @Test
    fun `managed directories cover every managed subtree`() {
        val layout = ChapterArtifactLayout("c")
        layout.managedDirectories shouldBe listOf(
            "c_artifacts/artifacts",
            "c_artifacts/images",
            "c_artifacts/pages",
            "c_artifacts/context",
            "c_artifacts/generations",
            "c_artifacts/glossary",
        )
    }
}
