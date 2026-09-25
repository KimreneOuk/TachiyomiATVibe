package eu.kanade.translation.artifact

import eu.kanade.translation.artifact.loadArtifact
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 *  LI-x: adoption-write manifest coalescing for the batch-resume rebuild.
 * The resume-hydration loop used to rewrite the FULL manifest JSON once or
 * more PER PAGE (~340 rewrites of a ~360KB document on a 206-page chapter,
 * ~1.3s apart — a main-thread ANR contributor). While a coalescing window is
 * open, each transaction's manifest publication only stages the intended
 * manifest; the durable rewrite happens at most once per 32 staged
 * publications and ALWAYS on [ChapterArtifactEngine.endManifestCoalescing].
 * The pins below:
 *
 *  - N chained adoptions inside the window produce a BOUNDED number of
 *    durable manifest rewrites (<< N) while the final durable state carries
 *    ALL adopted candidate pointers, and the caller-side façade (the last
 *    Committed manifest) equals the durable manifest after the final flush;
 *  - the coalesced CAS baseline lets the serialized transaction chain build
 *    on its own staged manifests without spurious stale rejections, and a
 *    stale façade (the background health verify's VERIFIED stamp) still
 *    rebases — the pointer-set-only adoption mutation carries the fresh
 *    durable `legacyMigration` forward instead of clobbering it;
 *  - a mid-window flush failure fails the owning transaction exactly like a
 *    direct publication failure (Rejected), never silently.
 */
class ChapterArtifactEngineManifestCoalescingTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String = "source", blockId: String? = "b1") = TranslationBlock(
        blockId = blockId,
        text = text,
        translation = "",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun page(key: String) = PageTranslation(
        sourceFileName = key,
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$key"),
        detectionFingerprint = hex64("detection-$key"),
        ocrFingerprint = hex64("ocr-$key"),
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    private fun legacySnapshot(pageKeys: List<String>) = ArtifactSeed(
        pages = pageKeys.associateWith { key -> ArtifactPageFacts(page(key), CleanedFileState.VALID) },
        glossary = emptyMap(),
        legacyIdentity = LegacySourceIdentity(
            sha256 = "sha-chapter",
            lengthBytes = 1L,
            lastModifiedMs = 1L,
        ),
        sourceFileName = "Chapter 1.json",
        glossaryFileName = null,
        glossaryIdentity = null,
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
    )

    /** Durable manifest rewrites so far (temp→primary rename of the manifest document). */
    private fun durableManifestWrites(io: FakeChapterDocumentIo): Int =
        io.renamed.count { it.second == layout.manifestFileName }

    /** One adoption-equivalent publication: opens a BATCH candidate for [pageKey]. */
    private fun openCandidate(
        artifact: ChapterArtifactEngine,
        manifest: ChapterArtifactManifest,
        pageKey: String,
        nowEpochMs: Long,
    ): ChapterArtifactManifest =
        artifact.openCandidate(
            manifest = manifest,
            pageKey = pageKey,
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "deps-$pageKey",
            nowEpochMs = nowEpochMs,
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>().manifest

    @Test
    fun `coalesced adoptions bound durable rewrites and flush every adopted pointer on end`() {
        val io = FakeChapterDocumentIo()
        val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val pageKeys = (1..100).map { index -> "p%03d.jpg".format(index) }
        artifact.loadArtifact(legacySnapshot(pageKeys))
        val writesBeforeWindow = durableManifestWrites(io)

        var facade = artifact.readManifest().shouldNotBeNull()
        artifact.beginManifestCoalescing()
        try {
            pageKeys.forEachIndexed { index, pageKey ->
                // Each adoption chains off the previous Committed manifest —
                // the coalesced CAS baseline, not the lagging durable file.
                facade = openCandidate(artifact, facade, pageKey, nowEpochMs = 1_000L + index)
            }
            // Bounded unflushed state: flushes at publications 32/64/96 only —
            // the durable manifest carries the first 96 adoptions, no more.
            durableManifestWrites(io) - writesBeforeWindow shouldBe 3
            artifact.readManifest().shouldNotBeNull().activeCandidateGenerationIds.size shouldBe 96
        } finally {
            artifact.endManifestCoalescing()
        }

        // Exactly one more durable rewrite: the mandatory final flush.
        durableManifestWrites(io) - writesBeforeWindow shouldBe 4
        val durable = artifact.readManifest().shouldNotBeNull()
        // ALL adopted generation ids landed, and the façade equals durable.
        durable.activeCandidateGenerationIds.size shouldBe pageKeys.size
        pageKeys.forEach { pageKey ->
            durable.pages.getValue(pageKey).candidate.shouldNotBeNull()
                .dependencyFingerprint shouldBe "deps-$pageKey"
        }
        facade shouldBe durable
    }

    @Test
    fun `coalesced adoptions rebase onto a stale façade and carry the VERIFIED marker forward`() {
        val io = FakeChapterDocumentIo()
        val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val pageKeys = (1..3).map { index -> "p%03d.jpg".format(index) }
        artifact.loadArtifact(legacySnapshot(pageKeys))

        // The façade cached the pre-verification snapshot; the background
        // health verify then republished VERIFIED behind its back.
        val facadeSnapshot = artifact.readManifest().shouldNotBeNull()
        val verified = facadeSnapshot.legacyMigration
            ?: LegacyMigrationMetadata(sourceFileName = "Chapter 1.json")
        check(
            artifact.publishManifest(
                facadeSnapshot.copy(
                    legacyMigration = verified.copy(
                        health = LegacyMigrationHealth.VERIFIED,
                        lastVerifiedByVersionCode = 63L,
                        lastVerifiedAtEpochMs = 5_000L,
                    ),
                    updatedAtEpochMs = 5_000L,
                ),
            ),
        ) { "fixture: concurrent verify publication failed" }
        val writesBeforeWindow = durableManifestWrites(io)

        var facade: ChapterArtifactManifest = facadeSnapshot
        artifact.beginManifestCoalescing()
        try {
            pageKeys.forEachIndexed { index, pageKey ->
                facade = openCandidate(artifact, facade, pageKey, nowEpochMs = 6_000L + index)
            }
        } finally {
            artifact.endManifestCoalescing()
        }

        val durable = artifact.readManifest().shouldNotBeNull()
        // The stale first adoption rebased onto the fresh VERIFIED manifest and
        // the whole pointer-set-only adoption chain carried it forward —
        // instead of clobbering health back to the pre-verification value.
        durable.legacyMigration.shouldNotBeNull().health shouldBe LegacyMigrationHealth.VERIFIED
        durable.activeCandidateGenerationIds.size shouldBe pageKeys.size
        facade shouldBe durable
        durableManifestWrites(io) - writesBeforeWindow shouldBe 1
    }

    @Test
    fun `a failed mid-window flush fails the owning transaction like a direct publication failure`() {
        val io = FakeChapterDocumentIo()
        val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val pageKeys = (1..40).map { index -> "p%03d.jpg".format(index) }
        artifact.loadArtifact(legacySnapshot(pageKeys))
        val writesBeforeWindow = durableManifestWrites(io)

        // Arm the durable manifest rewrite to fail; the 32nd staged
        // publication triggers the bounded mid-window flush, which must fail
        // the OWNING transaction instead of pretending to commit.
        io.writeNamesToFail += layout.manifestFileName
        var facade = artifact.readManifest().shouldNotBeNull()
        var firstRejection: String? = null
        artifact.beginManifestCoalescing()
        try {
            for ((index, pageKey) in pageKeys.withIndex()) {
                val outcome = artifact.openCandidate(
                    manifest = facade,
                    pageKey = pageKey,
                    origin = ArtifactOrigin.BATCH,
                    expectedPageVersion = 0L,
                    dependencyFingerprint = "deps-$pageKey",
                    nowEpochMs = 2_000L + index,
                )
                when (outcome) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> facade = outcome.manifest
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        firstRejection = outcome.reason
                        break
                    }
                }
            }
        } finally {
            artifact.endManifestCoalescing()
        }

        firstRejection.shouldNotBeNull() shouldContain "manifest publication failed"
        // The window is closed and a later publication works normally again.
        io.writeNamesToFail.clear()
        val recovered = artifact.readManifest().shouldNotBeNull()
        val afterEnd = openCandidate(artifact, recovered, pageKeys.last(), nowEpochMs = 9_999L)
        artifact.readManifest().shouldNotBeNull() shouldBe afterEnd
        durableManifestWrites(io).shouldBeGreaterThan(writesBeforeWindow)
    }
}
