package eu.kanade.translation.context

import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterContextSnapshot
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.CleanedImageProbe
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.EvidenceStrength
import eu.kanade.translation.persistence.artifact.FactConflictState
import eu.kanade.translation.persistence.artifact.FactProvenance
import eu.kanade.translation.persistence.artifact.FactScope
import eu.kanade.translation.persistence.artifact.FactType
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.GroupCommitConfiguration
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.ProbedImage
import eu.kanade.translation.persistence.artifact.ProfileFact
import eu.kanade.translation.persistence.artifact.ProfilePointer
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChapterContextLegacySnapshotCompatibilityTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    @BeforeEach
    fun setUp() {
        GroupCommitConfiguration.enabled = true
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 100) }
    }

    @AfterEach
    fun tearDown() {
        GroupCommitConfiguration.enabled = false
        ChapterTranslationStore.artifactImageProbe =
            eu.kanade.translation.persistence.artifact.BitmapFactoryCleanedImageProbe
    }

    @Test
    fun legacySnapshotSidecarIsReadableButDoesNotSeedPreparedHistory() {
        val fingerprint = "a".repeat(64)
        val fileName = layout.contextFile(fingerprint)
        val pointer = SidecarPointer(
            fileName = fileName,
            schemaVersion = ChapterContextSnapshot.SCHEMA_VERSION,
            contentFingerprint = fingerprint,
        )
        val legacySnapshot = ChapterContextSnapshot(
            chapterKey = layout.chapterKey,
            targetLang = "en",
            contentFingerprint = fingerprint,
            glossaryFingerprint = "legacy-glossary-fingerprint",
            profileInputFingerprint = "legacy-profile-fingerprint",
            characterAndTermSheet = "LEGACY PROFILE SHEET",
            rollingContext = "OUTSIDE WINDOW => stale term",
            selectedPairs = listOf("OUTSIDE WINDOW" to "stale term"),
        )
        val manifest = ChapterArtifactManifest(
            schemaVersion = 4,
            chapterKey = layout.chapterKey,
            context = pointer,
            pages = mapOf(
                "0001.jpg" to PageArtifactRecord(pageKey = "0001.jpg", naturalPageIndex = 0),
                "0002.jpg" to PageArtifactRecord(pageKey = "0002.jpg", naturalPageIndex = 1),
            ),
        )
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val documents = AtomicChapterDocuments(io)
        documents.publishJson(fileName, legacySnapshot)
        documents.publishJson(layout.manifestFileName, manifest)
        val artifact = ChapterArtifactEngine(
            documents,
            layout,
            displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) },
        )
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
            artifactStore = artifact,
            initialArtifactManifest = manifest,
        )

        val read = artifact.readContextSnapshot(pointer)
        (read is ChapterArtifactEngine.ContextSnapshotRead.Usable) shouldBe true
        (read as ChapterArtifactEngine.ContextSnapshotRead.Usable).snapshot.characterAndTermSheet shouldBe
            "LEGACY PROFILE SHEET"

        val prepared = ChapterContextService(store).prepare(
            ContextRequest(
                pageKeys = listOf("0002.jpg"),
                targetLang = "en",
                profile = TranslationContextChunkPlanner.Profile.DEFAULT,
                laneCapability = LaneCapability.MANUAL,
            ),
        )

        prepared.rollingContext shouldBe ""
        prepared.selectedPairs shouldBe emptyList()
    }

    @Test
    fun legacyProfileSidecarRemainsReadableWithoutAProductionWriter() {
        val fingerprint = "b".repeat(64)
        val profileInputFingerprint = "c".repeat(64)
        val draft = ChapterTranslationProfile(
            version = 1,
            contentFingerprint = "",
            profileInputFingerprint = profileInputFingerprint,
            sourceRunId = "legacy-run",
            analyzerProvenance = AnalyzerProvenance("legacy", "legacy-model", 1, 1, "legacy-sig"),
            entities = listOf(
                ProfileFact(
                    factId = "legacy-fact",
                    type = FactType.ENTITY_IDENTITY,
                    canonicalSourceForm = "カイル",
                    canonicalTargetForm = "Kyle",
                    evidenceStrength = EvidenceStrength.STRONG_CONTEXTUAL,
                    evidenceRefs = listOf(EvidenceRef("0001.jpg", "0001_b1", "d".repeat(64))),
                    scope = FactScope.CANONICAL_CHAPTER_WIDE,
                    provenance = FactProvenance.CHAPTER_ANALYSIS,
                    conflictState = FactConflictState.RESOLVED,
                ),
            ),
            frozenAtEpochMs = 42L,
        )
        val profile = draft.copy(contentFingerprint = StageFingerprints.profileContentFingerprint(draft))
        val fileName = layout.profileFile(profile.contentFingerprint)
        val manifest = ChapterArtifactManifest(
            schemaVersion = 4,
            chapterKey = layout.chapterKey,
            profile = ProfilePointer(
                fileName = fileName,
                version = profile.version,
                schemaVersion = ChapterTranslationProfile.SCHEMA_VERSION,
                contentFingerprint = profile.contentFingerprint,
                profileInputFingerprint = profile.profileInputFingerprint,
            ),
        )
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val documents = AtomicChapterDocuments(io)
        documents.publishJson(fileName, profile)
        documents.publishJson(layout.manifestFileName, manifest)
        val artifact = ChapterArtifactEngine(
            documents,
            layout,
            displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) },
        )
        val store = ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = emptyMap(),
            artifactStore = artifact,
            initialArtifactManifest = manifest,
        )

        store.readReusableProfile() shouldBe profile
    }
}
