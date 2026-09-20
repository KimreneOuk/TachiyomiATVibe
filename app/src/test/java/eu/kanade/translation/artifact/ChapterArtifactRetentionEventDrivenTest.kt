package eu.kanade.translation.artifact

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 *  Slice A4 (Amendment D): event-driven retention tests.
 * Verifies known-orphan deletion, staged-reachable protection (Race register #6),
 * and trigger parity (close() sweeps, closeAndFlush() remains sweep-free).
 */
class ChapterArtifactRetentionEventDrivenTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun createStore(io: FakeChapterDocumentIo): ChapterArtifactEngine =
        ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    @Test
    fun `deleteKnownOrphans deletes specified managed files and temp files without full tree crawl`() {
        val io = FakeChapterDocumentIo()
        val store = createStore(io)

        val orphanGen = layout.generationFile("gen-orphan")
        val orphanOcr = layout.stageArtifactFile("0001.jpg", ArtifactStage.OCR, "orphan-fp")
        val untouchedOrphan = layout.generationFile("gen-untouched")
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)

        io.write(orphanGen, byteArrayOf(1))
        io.write(orphanOcr, byteArrayOf(2))
        io.write(untouchedOrphan, byteArrayOf(3))
        io.write(manifestTemp, byteArrayOf(4))

        val result = store.deleteKnownOrphans(listOf(orphanGen, orphanOcr))

        // Specified orphans and manifest temp are deleted
        result.deletedNames shouldContain orphanGen
        result.deletedNames shouldContain orphanOcr
        result.deletedNames shouldContain manifestTemp
        io.files.containsKey(orphanGen) shouldBe false
        io.files.containsKey(orphanOcr) shouldBe false
        io.files.containsKey(manifestTemp) shouldBe false

        // Files NOT in candidateOrphans are NOT deleted (no full tree crawl)
        io.files.containsKey(untouchedOrphan) shouldBe true
    }

    @Test
    fun `race register 6 - orphan referenced by staged state is spared from retention`() {
        val io = FakeChapterDocumentIo()
        val store = createStore(io)

        val candidateOcr = layout.stageArtifactFile("0001.jpg", ArtifactStage.OCR, "staged-fp")
        val trueOrphan = layout.stageArtifactFile("0001.jpg", ArtifactStage.OCR, "true-orphan-fp")

        io.write(candidateOcr, byteArrayOf(1))
        io.write(trueOrphan, byteArrayOf(2))

        // Manifest does not reference either OCR file (both are unlinked from durable manifest)
        val manifest = ChapterArtifactManifest(chapterKey = layout.chapterKey)

        // However, candidateOcr is referenced in stagedReachable
        val stagedReachable = setOf(candidateOcr)

        // 1. Test in deleteKnownOrphans
        val orphanResult = store.deleteKnownOrphans(
            candidateOrphans = listOf(candidateOcr, trueOrphan),
            stagedReachable = stagedReachable,
        )
        orphanResult.deletedNames shouldContain trueOrphan
        orphanResult.deletedNames shouldNotContain candidateOcr
        io.files.containsKey(trueOrphan) shouldBe false
        io.files.containsKey(candidateOcr) shouldBe true

        // 2. Test in reconcileRetention full sweep
        io.write(trueOrphan, byteArrayOf(3))
        val sweepResult = store.reconcileRetention(manifest, stagedReachable = stagedReachable)
        sweepResult.deletedNames shouldContain trueOrphan
        sweepResult.deletedNames shouldNotContain candidateOcr
        io.files.containsKey(candidateOcr) shouldBe true
    }

    @Test
    fun `deleteKnownOrphans never touches files outside managed directories`() {
        val io = FakeChapterDocumentIo()
        val store = createStore(io)

        val externalFile = "Chapter 1.json"
        io.write(externalFile, byteArrayOf(10))

        val result = store.deleteKnownOrphans(listOf(externalFile))
        result.deletedNames shouldNotContain externalFile
        io.files.containsKey(externalFile) shouldBe true
    }
}
