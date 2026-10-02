package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.ArtifactDocumentJson
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.CandidateGenerationMetadata
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.CleanedImageIdentity
import eu.kanade.translation.persistence.artifact.CleanedImageIdentitySidecar
import eu.kanade.translation.persistence.artifact.CommittedBundleMetadata
import eu.kanade.translation.persistence.artifact.DisplayBaseKind
import eu.kanade.translation.persistence.artifact.DisplayBaseReference
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.StageFingerprints
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test

class ChapterJournalArtifactResolverTest {
    private val pageKey = "page.jpg"
    private val imageName = "page.cleaned.legacy-token.jpg"
    private val imageBytes = "cleaned-image-bytes".encodeToByteArray()
    private val imageHash = StageFingerprints.sha256Hex(imageBytes)
    private val layout = ChapterArtifactLayout("Resolver chapter")
    private val io = FakeChapterDocumentIo()
    private val engine = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)

    @Test
    fun `production resolver checks candidate committed and previous snapshot references`() {
        val page = page(cleanedImageName = imageName, cleanedImageContentHash = imageHash).toPublishedPage()
        val expectedHash = StageFingerprints.pageSnapshot(page)
        io.files["${layout.chapterKey}_images/$imageName"] = imageBytes
        val imageIdentity = CleanedImageIdentity.create(pageKey, imageName, imageBytes.inputStream())
        io.files["${layout.chapterKey}_images/${CleanedImageIdentity.sidecarName(imageName)}"] =
            CleanedImageIdentity.encode(imageIdentity)

        val references = listOf(
            "candidate-snapshot.json" to PageArtifactRecord(
                pageKey = pageKey,
                candidate = CandidateGenerationMetadata(
                    generationId = "candidate-g1",
                    pageSnapshotFileName = "candidate-snapshot.json",
                    pageSnapshotFingerprint = expectedHash,
                ),
            ),
            "committed-snapshot.json" to PageArtifactRecord(
                pageKey = pageKey,
                committed = CommittedBundleMetadata(
                    generationId = "committed-g1",
                    displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE),
                    translationFingerprint = expectedHash,
                    pageSnapshotFileName = "committed-snapshot.json",
                ),
            ),
            "previous-snapshot.json" to PageArtifactRecord(
                pageKey = pageKey,
                previousCommitted = CommittedBundleMetadata(
                    generationId = "committed-g0",
                    displayBase = DisplayBaseReference(DisplayBaseKind.ORIGINAL_SOURCE),
                    translationFingerprint = expectedHash,
                    pageSnapshotFileName = "previous-snapshot.json",
                ),
            ),
        )

        references.forEach { (fileName, record) ->
            io.files[fileName] = ArtifactDocumentJson.encodeToString(page.toDraft()).encodeToByteArray()
            val manifest = ChapterArtifactManifest(
                chapterKey = layout.chapterKey,
                pages = mapOf(pageKey to record),
            )
            ChapterJournalReplayReducer.artifactResolver(engine, manifest)
                .matches(pageKey, expectedHash, page) shouldBe true
        }
    }

    @Test
    fun `resolver rejects replaced missing corrupt snapshot and cleaned image bytes`() {
        val page = page(cleanedImageName = imageName, cleanedImageContentHash = imageHash).toPublishedPage()
        val expectedHash = StageFingerprints.pageSnapshot(page)
        val snapshotName = "candidate-snapshot.json"
        val record = PageArtifactRecord(
            pageKey = pageKey,
            candidate = CandidateGenerationMetadata(
                generationId = "candidate-g1",
                pageSnapshotFileName = snapshotName,
                pageSnapshotFingerprint = expectedHash,
            ),
        )
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(pageKey to record),
        )
        val resolver = ChapterJournalReplayReducer.artifactResolver(engine, manifest)
        val snapshotBytes = ArtifactDocumentJson.encodeToString(page.toDraft()).encodeToByteArray()
        val imagePath = "${layout.chapterKey}_images/$imageName"
        val identityPath = "${layout.chapterKey}_images/${CleanedImageIdentity.sidecarName(imageName)}"
        io.files[snapshotName] = snapshotBytes
        io.files[imagePath] = imageBytes
        io.files[identityPath] = CleanedImageIdentity.encode(
            CleanedImageIdentity.create(pageKey, imageName, imageBytes.inputStream()),
        )
        resolver.matches(pageKey, expectedHash, page) shouldBe true

        val replacedPage = page(
            cleanedImageName = imageName,
            cleanedImageContentHash = imageHash,
            translation = "replacement",
        ).toPublishedPage()
        io.files[snapshotName] = ArtifactDocumentJson.encodeToString(replacedPage.toDraft()).encodeToByteArray()
        resolver.matches(pageKey, expectedHash, page) shouldBe false

        io.files.remove(snapshotName)
        resolver.matches(pageKey, expectedHash, page) shouldBe false

        io.files[snapshotName] = byteArrayOf('{'.code.toByte(), 'x'.code.toByte())
        resolver.matches(pageKey, expectedHash, page) shouldBe false

        io.files[snapshotName] = snapshotBytes
        io.files.remove(imagePath)
        resolver.matches(pageKey, expectedHash, page) shouldBe false

        io.files[imagePath] = "replaced-cleaned-image".encodeToByteArray()
        resolver.matches(pageKey, expectedHash, page) shouldBe false

        io.files[imagePath] = imageBytes
        io.files.remove(identityPath)
        resolver.matches(pageKey, expectedHash, page) shouldBe false

        io.files[identityPath] = byteArrayOf('{'.code.toByte(), 'x'.code.toByte())
        resolver.matches(pageKey, expectedHash, page) shouldBe false

        io.files[identityPath] = CleanedImageIdentity.encode(
            CleanedImageIdentitySidecar(
                pageKey = "another-page.jpg",
                imageName = imageName,
                contentSha256 = StageFingerprints.sha256Hex(imageBytes),
            ),
        )
        // Sidecar pageKey is provenance only. A legitimate store rekey keeps
        // the same immutable image name and byte identity under the new page key.
        resolver.matches(pageKey, expectedHash, page) shouldBe true
    }

    @Test
    fun `resolver rejects embedded journal state that differs from the referenced artifact identity`() {
        val artifactPage = page(cleanedImageName = imageName, cleanedImageContentHash = imageHash).toPublishedPage()
        val expectedHash = StageFingerprints.pageSnapshot(artifactPage)
        val snapshotName = "candidate-snapshot.json"
        io.files[snapshotName] = ArtifactDocumentJson.encodeToString(artifactPage.toDraft()).encodeToByteArray()
        val imagePath = "${layout.chapterKey}_images/$imageName"
        io.files[imagePath] = imageBytes
        io.files["${layout.chapterKey}_images/${CleanedImageIdentity.sidecarName(imageName)}"] =
            CleanedImageIdentity.encode(CleanedImageIdentity.create(pageKey, imageName, imageBytes.inputStream()))
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                pageKey to PageArtifactRecord(
                    pageKey = pageKey,
                    candidate = CandidateGenerationMetadata(
                        generationId = "candidate-g1",
                        pageSnapshotFileName = snapshotName,
                        pageSnapshotFingerprint = expectedHash,
                    ),
                ),
            ),
        )
        val resolver = ChapterJournalReplayReducer.artifactResolver(engine, manifest)
        val forgedJournalState = page(
            cleanedImageName = imageName,
            cleanedImageContentHash = imageHash,
            translation = "different",
        ).toPublishedPage()

        resolver.matches(pageKey, expectedHash, forgedJournalState) shouldBe false
    }

    @Test
    fun `rekeyed page verifies unchanged image by record identity despite sidecar provenance key`() {
        val oldKey = "online/page.jpg"
        val newKey = "downloaded/page.jpg"
        val imageBytes = "rekeyed-image-bytes".encodeToByteArray()
        val imageHash = StageFingerprints.sha256Hex(imageBytes)
        val page = page(
            cleanedImageName = imageName,
            cleanedImageContentHash = imageHash,
            sourceFileName = newKey,
        ).toPublishedPage()
        val expectedHash = StageFingerprints.pageSnapshot(page)
        val snapshotName = "rekeyed-candidate.json"
        io.files[snapshotName] = ArtifactDocumentJson.encodeToString(page.toDraft()).encodeToByteArray()
        val imagePath = "${layout.chapterKey}_images/$imageName"
        io.files[imagePath] = imageBytes
        io.files["${layout.chapterKey}_images/${CleanedImageIdentity.sidecarName(imageName)}"] =
            CleanedImageIdentity.encode(CleanedImageIdentity.create(oldKey, imageName, imageBytes.inputStream()))
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                newKey to PageArtifactRecord(
                    pageKey = newKey,
                    candidate = CandidateGenerationMetadata(
                        generationId = "candidate-rekeyed",
                        pageSnapshotFileName = snapshotName,
                        pageSnapshotFingerprint = expectedHash,
                    ),
                ),
            ),
        )

        ChapterJournalReplayReducer.artifactResolver(engine, manifest)
            .matches(newKey, expectedHash, page) shouldBe true

        val wrongDigestState = page.toDraft().apply {
            cleanedImageContentHash = StageFingerprints.sha256Hex("different image".encodeToByteArray())
        }.toPublishedPage()
        ChapterJournalReplayReducer.artifactResolver(engine, manifest)
            .matches(newKey, StageFingerprints.pageSnapshot(wrongDigestState), wrongDigestState) shouldBe false
    }

    @Test
    fun `sidecar binds immutable image name and bytes while journal binds page ownership`() {
        val imageBytes = "immutable-cleaned-image".encodeToByteArray()
        val expectedHash = StageFingerprints.sha256Hex(imageBytes)
        val sidecar = CleanedImageIdentity.encode(
            CleanedImageIdentity.create(pageKey, imageName, imageBytes.inputStream()),
        )

        CleanedImageIdentity.verifyExisting(
            sidecarBytes = sidecar,
            imageName = imageName,
            expectedContentSha256 = expectedHash,
        ) { imageBytes.inputStream() } shouldBe true
        CleanedImageIdentity.verifyExisting(
            sidecarBytes = sidecar,
            imageName = imageName,
            expectedContentSha256 = StageFingerprints.sha256Hex("different".encodeToByteArray()),
        ) { imageBytes.inputStream() } shouldBe false
        CleanedImageIdentity.verifyExisting(
            sidecarBytes = sidecar,
            imageName = imageName,
            expectedContentSha256 = expectedHash,
        ) { imageBytes.inputStream() } shouldBe true
        CleanedImageIdentity.verifyExisting(
            sidecarBytes = sidecar,
            imageName = "other.cleaned.jpg",
            expectedContentSha256 = expectedHash,
        ) { imageBytes.inputStream() } shouldBe false
        CleanedImageIdentity.verifyExisting(
            sidecarBytes = sidecar,
            imageName = imageName,
            expectedContentSha256 = expectedHash,
        ) { "replaced bytes".byteInputStream() } shouldBe false
        CleanedImageIdentity.verifyExisting(
            sidecarBytes = sidecar.dropLast(1).toByteArray(),
            imageName = imageName,
            expectedContentSha256 = expectedHash,
        ) { imageBytes.inputStream() } shouldBe false
    }

    @Test
    fun `replacing image and sidecar together cannot change the digest anchored by the journal record`() {
        val page = page(cleanedImageName = imageName, cleanedImageContentHash = imageHash).toPublishedPage()
        val expectedHash = StageFingerprints.pageSnapshot(page)
        val snapshotName = "candidate-snapshot.json"
        val originalPageBytes = ArtifactDocumentJson.encodeToString(page.toDraft()).encodeToByteArray()
        val imagePath = "${layout.chapterKey}_images/$imageName"
        val identityPath = "${layout.chapterKey}_images/${CleanedImageIdentity.sidecarName(imageName)}"
        io.files[snapshotName] = originalPageBytes
        io.files[imagePath] = imageBytes
        io.files[identityPath] = CleanedImageIdentity.encode(
            CleanedImageIdentity.create("original-page.jpg", imageName, imageBytes.inputStream()),
        )
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                pageKey to PageArtifactRecord(
                    pageKey = pageKey,
                    candidate = CandidateGenerationMetadata(
                        generationId = "candidate-g1",
                        pageSnapshotFileName = snapshotName,
                        pageSnapshotFingerprint = expectedHash,
                    ),
                ),
            ),
        )
        val resolver = ChapterJournalReplayReducer.artifactResolver(engine, manifest)
        resolver.matches(pageKey, expectedHash, page) shouldBe true

        val replacementBytes = "replacement bytes with a replacement self-asserted sidecar".encodeToByteArray()
        io.files[imagePath] = replacementBytes
        io.files[identityPath] = CleanedImageIdentity.encode(
            CleanedImageIdentity.create(pageKey, imageName, replacementBytes.inputStream()),
        )

        resolver.matches(pageKey, expectedHash, page) shouldBe false
    }

    @Test
    fun `cleaned image without a journal anchored digest is not resolvable`() {
        val page = page(cleanedImageName = imageName).toPublishedPage()
        val snapshotName = "candidate-snapshot.json"
        val imagePath = "${layout.chapterKey}_images/$imageName"
        io.files[snapshotName] = ArtifactDocumentJson.encodeToString(page.toDraft()).encodeToByteArray()
        io.files[imagePath] = imageBytes
        io.files["${layout.chapterKey}_images/${CleanedImageIdentity.sidecarName(imageName)}"] =
            CleanedImageIdentity.encode(CleanedImageIdentity.create(pageKey, imageName, imageBytes.inputStream()))
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(
                pageKey to PageArtifactRecord(
                    pageKey = pageKey,
                    candidate = CandidateGenerationMetadata(
                        generationId = "candidate-g1",
                        pageSnapshotFileName = snapshotName,
                        pageSnapshotFingerprint = StageFingerprints.pageSnapshot(page),
                    ),
                ),
            ),
        )

        ChapterJournalReplayReducer.artifactResolver(engine, manifest)
            .matches(pageKey, StageFingerprints.pageSnapshot(page), page) shouldBe false
    }

    private fun page(
        cleanedImageName: String,
        cleanedImageContentHash: String? = null,
        sourceFileName: String = pageKey,
        translation: String = "translated",
    ) = PageTranslation(
        sourceFileName = sourceFileName,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = translation,
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        cleanedImageName = cleanedImageName,
        cleanedImageContentHash = cleanedImageContentHash,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
    )
}
