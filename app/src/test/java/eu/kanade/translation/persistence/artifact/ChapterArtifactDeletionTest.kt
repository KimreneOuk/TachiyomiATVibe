package eu.kanade.translation.persistence.artifact

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class ChapterArtifactDeletionTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun identity(bytes: ByteArray) = LegacySourceIdentity(
        sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) },
        lengthBytes = bytes.size.toLong(),
        lastModifiedMs = 1L,
    )

    private fun manifest(source: ByteArray): ChapterArtifactManifest {
        val sourceIdentity = identity(source)
        return ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            legacySource = sourceIdentity,
            legacyMigration = LegacyMigrationMetadata(
                sourceFileName = "Chapter 1.json",
                sourcePreservation = LegacyPreservationState.PRESERVED,
                requestedSourceFileName = "Chapter 1.json",
                resolvedSourceFileName = "Chapter 1.json.migrated",
                sourcePreservedAtEpochMs = 2L,
                sourceIdentity = sourceIdentity,
                migratedByVersionCode = 1L,
                migratedAtEpochMs = 1L,
            ),
        )
    }

    @Test
    fun `raw delete removes authority tree and matching preserved names but retains mismatch`() {
        val io = FakeChapterDocumentIo()
        val source = "legacy-source".toByteArray()
        AtomicChapterDocuments(io).publishJson(layout.manifestFileName, manifest(source)) shouldBe true
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
        AtomicChapterDocuments(io).publishJson(layout.manifestFileName, manifest(source)) shouldBe true
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
}
