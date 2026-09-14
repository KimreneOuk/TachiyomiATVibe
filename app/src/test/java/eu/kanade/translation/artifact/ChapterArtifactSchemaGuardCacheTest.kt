package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.security.MessageDigest

/**
 * T930 Slice A3 (Amendment A): tests that the schema-guard cache applies
 * to the schema-normalization decision only, while future-schema guard reads
 * remain fresh from disk.
 */
class ChapterArtifactSchemaGuardCacheTest {

    private val layout = ChapterArtifactLayout("Chapter 1")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun identity(tag: String) = LegacySourceIdentity(
        sha256 = "sha-$tag",
        lengthBytes = tag.length.toLong(),
        lastModifiedMs = 1L,
    )

    private fun block(userEditedAt: Long? = null) = eu.kanade.translation.model.TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        userEditedAt = userEditedAt,
    )

    private fun displayablePage() = PageTranslation(
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    private fun legacySnapshot(identityTag: String = "v1") = LegacyChapterSnapshot(
        pages = mapOf("page.jpg" to LegacyPageFacts(
            displayablePage(),
            CleanedFileState.VALID,
        )),
        glossary = emptyMap(),
        legacyIdentity = identity(identityTag),
        sourceFileName = "Chapter 1.json",
        glossaryFileName = null,
        glossaryIdentity = null,
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
    )

    private fun createStore(io: FakeChapterDocumentIo): ChapterArtifactStore =
        ChapterArtifactStore(
            AtomicChapterDocuments(io),
            layout,
            object : CleanedImageProbe {
                override fun probe(input: InputStream): ProbedImage? = ProbedImage(100, 100)
            },
        )

    @Test
    fun `schema normalization decision is cached and can be invalidated`() {
        val io = FakeChapterDocumentIo()
        val store = createStore(io)

        // v1, v2, and v3 need normalization to current schema version (4)
        store.isNormalizationRequired(1) shouldBe true
        store.isNormalizationRequired(2) shouldBe true
        store.isNormalizationRequired(3) shouldBe true
        // v4 is already current schema version
        store.isNormalizationRequired(ChapterArtifactManifest.SCHEMA_VERSION) shouldBe false
        // future schemas are untouched
        store.isNormalizationRequired(99) shouldBe false

        // Invalidation clears the cache without error
        store.invalidateSchemaGuardCache()
        store.isNormalizationRequired(1) shouldBe true
    }

    @Test
    fun `future-schema backup appearing mid-instance is detected fresh from disk`() {
        val io = FakeChapterDocumentIo()
        val store = createStore(io)

        // First open loads the legacy snapshot and creates a normal v3 primary
        val first = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))
        first.manifest.schemaVersion shouldBe ChapterArtifactManifest.SCHEMA_VERSION

        // A newer build writes a future-schema backup (schema 99) mid-instance
        val future = ChapterArtifactManifest(schemaVersion = 99, chapterKey = "Chapter 1")
        io.write(
            AtomicChapterDocuments.backupNameFor(layout.manifestFileName),
            json.encodeToString(future).toByteArray(Charsets.UTF_8),
        )

        // The next loadOrMigrate call MUST read fresh from disk (not a cached guard)
        // and see the future backup untouched
        val second = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))
        second.manifest shouldBe first.manifest

        // Fast path must not delete or overwrite the future backup
        io.files.containsKey(AtomicChapterDocuments.backupNameFor(layout.manifestFileName)) shouldBe true
    }

    @Test
    fun `future-schema primary appearing mid-instance is refused fresh from disk`() {
        val io = FakeChapterDocumentIo()
        val store = createStore(io)

        store.loadOrMigrate(legacySnapshot(identityTag = "v1"))

        // Mid-instance, primary is replaced by a future-schema primary
        val future = ChapterArtifactManifest(schemaVersion = 99, chapterKey = "Chapter 1")
        io.write(
            layout.manifestFileName,
            json.encodeToString(future).toByteArray(Charsets.UTF_8),
        )

        val result = store.loadOrMigrate(legacySnapshot(identityTag = "v1"))
        result.manifest.schemaVersion shouldBe 99
        result.migratedFromLegacy shouldBe false
    }
}
