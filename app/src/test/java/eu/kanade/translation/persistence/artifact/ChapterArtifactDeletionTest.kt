package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.context.*
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.TranslationProvenancePage
import eu.kanade.translation.model.OcrBlockContent
import eu.kanade.translation.model.EnvelopePlanInputPage

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class ChapterArtifactDeletionTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun manifest() = ChapterArtifactManifest(chapterKey = layout.chapterKey)

    @Test
    fun `raw delete removes authority tree and matching preserved names but retains mismatch`() {
        val io = FakeChapterDocumentIo()
        val source = "legacy-source".toByteArray()
        AtomicChapterDocuments(io).publishJson(layout.manifestFileName, manifest()) shouldBe true
        io.write("${layout.manifestFileName}.tmp", byteArrayOf(1)) shouldBe true
        io.write("${layout.manifestFileName}.bak", byteArrayOf(2)) shouldBe true
        io.write("${layout.manifestFileName}.corrupt.deadbeef", byteArrayOf(3)) shouldBe true
        io.write("${layout.artifactRootDirectoryName}/pages/p/committed-g.json", byteArrayOf(4)) shouldBe true
        io.write("${layout.chapterKey}_images/page.cleaned.jpg", byteArrayOf(5)) shouldBe true
        io.write("Chapter 1.json", "external-source".toByteArray()) shouldBe true
        io.write("Chapter 1.json.migrated", source) shouldBe true
        io.write("Chapter 1.glossary.json.migrated", "external".toByteArray()) shouldBe true

        val plan = ChapterArtifactDeletionPlan.capture(io, "Chapter 1.json")
        plan shouldNotBe null
        val result = plan!!.delete()

        result.complete shouldBe true
        result.manifestRemoved shouldBe true
        result.artifactTreeRemoved shouldBe true
        io.exists(layout.manifestFileName) shouldBe false
        io.exists("${layout.manifestFileName}.tmp") shouldBe false
        io.exists("${layout.manifestFileName}.bak") shouldBe false
        io.exists("${layout.manifestFileName}.corrupt.deadbeef") shouldBe false
        io.exists(layout.artifactRootDirectoryName) shouldBe false
        io.exists("Chapter 1.json") shouldBe true
        // Legacy flat files are user data and remain untouched by artifact
        // deletion; the app simply no longer reads them.
        io.exists("Chapter 1.json.migrated") shouldBe true
        io.exists("Chapter 1.glossary.json.migrated") shouldBe true
        ChapterArtifactDeletionPlan.capture(io, "Chapter 1.json") shouldBe null
    }

    @Test
    fun `URI style backend failure retains authoritative tree for retry`() {
        val io = FakeChapterDocumentIo().apply {
            supportsNoReplaceRename = false
        }
        val source = "legacy-source".toByteArray()
        AtomicChapterDocuments(io).publishJson(layout.manifestFileName, manifest()) shouldBe true
        io.write("${layout.artifactRootDirectoryName}/pages/p/committed-g.json", byteArrayOf(4)) shouldBe true
        io.deleteNamesToFail += layout.manifestFileName

        val plan = ChapterArtifactDeletionPlan.capture(io, "Chapter 1.json")
        plan shouldNotBe null
        val result = plan!!.delete()

        result.complete shouldBe false
        result.manifestRemoved shouldBe false
        result.artifactTreeRemoved shouldBe false
        io.exists(layout.manifestFileName) shouldBe true
        io.exists(layout.artifactRootDirectoryName) shouldBe true
    }

    @Test
    fun `deletion publishes a verified empty-manifest destructive checkpoint before removal`() {
        val io = FakeChapterDocumentIo()
        val engine = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val seeded = engine.loadArtifact(
            ArtifactSeed(
                pages = mapOf(
                    "p0" to ArtifactPageFacts(
                        eu.kanade.translation.model.PageTranslation(sourceFileName = "p0"),
                    ),
                ),
                createdAtEpochMs = 1L,
            ),
        ).manifest
        seeded.pages.getValue("p0").committed shouldNotBe null
        AtomicChapterDocuments(io).publishJson(layout.manifestFileName, seeded) shouldBe true
        io.write("${layout.artifactRootDirectoryName}/pages/p0/committed-g.json", byteArrayOf(4)) shouldBe true
        io.renamed.clear()

        val plan = ChapterArtifactDeletionPlan.capture(io, "Chapter 1.json").shouldNotBeNull()
        val result = plan.delete()

        result.complete shouldBe true
        result.destructiveCheckpointUnverified shouldBe false
        result.manifestRemoved shouldBe true
        result.artifactTreeRemoved shouldBe true
        // The empty-manifest tombstone went through the destructive
        // publication (primary rotated to the backup name) BEFORE the
        // authority files were removed: a crash mid-deletion could only ever
        // restore an EMPTY authority, never the deleted generation state.
        io.renamed.any { it.first == layout.manifestFileName } shouldBe true
        io.exists(layout.manifestFileName) shouldBe false
        io.exists("${layout.manifestFileName}.bak") shouldBe false
        io.exists(layout.artifactRootDirectoryName) shouldBe false
    }

    @Test
    fun `an unverified checkpoint backup completes the removal but reports typed incompleteness`() {
        val io = FakeChapterDocumentIo()
        AtomicChapterDocuments(io).publishJson(layout.manifestFileName, manifest()) shouldBe true
        // Fail exactly the checkpoint's same-state backup copy.
        io.writeNamesToFail += "${layout.manifestFileName}.bak"

        val plan = ChapterArtifactDeletionPlan.capture(io, "Chapter 1.json").shouldNotBeNull()
        val result = plan.delete()

        result.complete shouldBe true
        result.destructiveCheckpointUnverified shouldBe true
        result.manifestRemoved shouldBe true
        io.exists(layout.manifestFileName) shouldBe false
        io.exists(layout.artifactRootDirectoryName) shouldBe false
    }

    @Test
    fun `a crash after the destructive checkpoint cannot resurrect the deleted generation`() {
        val io = FakeChapterDocumentIo()
        val engine = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val seeded = engine.loadArtifact(
            ArtifactSeed(
                pages = mapOf(
                    "p0" to ArtifactPageFacts(
                        eu.kanade.translation.model.PageTranslation(sourceFileName = "p0"),
                    ),
                ),
                createdAtEpochMs = 1L,
            ),
        ).manifest
        AtomicChapterDocuments(io).publishJson(layout.manifestFileName, seeded) shouldBe true

        val plan = ChapterArtifactDeletionPlan.capture(io, "Chapter 1.json").shouldNotBeNull()
        // Simulate the crash window: the tombstone ran, then the process died
        // before any authority file was removed.
        plan.delete()

        // Whatever survives the crash window can only be the EMPTY current
        // format manifest — recovery re-running the idempotent plan finishes.
        val recovery = ChapterArtifactDeletionPlan.capture(io, "Chapter 1.json")
        if (recovery != null) {
            val finished = recovery.delete()
            finished.complete shouldBe true
            io.exists(layout.manifestFileName) shouldBe false
        } else {
            io.exists(layout.manifestFileName) shouldBe false
        }
    }
}
