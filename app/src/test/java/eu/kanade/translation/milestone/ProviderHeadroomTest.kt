package eu.kanade.translation.milestone

import eu.kanade.translation.context.SeriesProfileRegistry
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.ProviderQuotaPolicy
import eu.kanade.translation.engines.translator.ProviderRequestGovernor
import eu.kanade.translation.engines.translator.ProviderRequestKey
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.engines.translator.providers.OpenAiCompatibleTranslator
import eu.kanade.translation.engines.translator.routing.MultiBackendRouter
import eu.kanade.translation.persistence.artifact.AnalyzerProvenance
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.EvidenceRef
import eu.kanade.translation.persistence.artifact.EvidenceStrength
import eu.kanade.translation.persistence.artifact.FactConflictState
import eu.kanade.translation.persistence.artifact.FactProvenance
import eu.kanade.translation.persistence.artifact.FactScope
import eu.kanade.translation.persistence.artifact.FactType
import eu.kanade.translation.persistence.artifact.PageRange
import eu.kanade.translation.persistence.artifact.ProfileFact
import eu.kanade.translation.persistence.artifact.ProfileScene
import eu.kanade.translation.persistence.artifact.SceneRegister
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ProviderHeadroomTest {

    @BeforeEach
    @AfterEach
    fun resetRegistry() {
        SeriesProfileRegistry.clear()
    }

    private fun sampleProfile(
        version: Int = 1,
        entities: List<ProfileFact> = listOf(
            ProfileFact(
                factId = "ent_1",
                type = FactType.ENTITY_IDENTITY,
                canonicalSourceForm = "太郎",
                canonicalTargetForm = "Taro",
                evidenceStrength = EvidenceStrength.STRONG_CONTEXTUAL,
                evidenceRefs = listOf(EvidenceRef("p1", "p1_b1", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")),
                scope = FactScope.CANONICAL_CHAPTER_WIDE,
                provenance = FactProvenance.CHAPTER_ANALYSIS,
                conflictState = FactConflictState.RESOLVED,
            ),
        ),
        terms: List<ProfileFact> = listOf(
            ProfileFact(
                factId = "term_1",
                type = FactType.TERM,
                canonicalSourceForm = "魔力",
                canonicalTargetForm = "Mana",
                evidenceStrength = EvidenceStrength.STRONG_CONTEXTUAL,
                evidenceRefs = listOf(EvidenceRef("p1", "p1_b2", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")),
                scope = FactScope.CANONICAL_CHAPTER_WIDE,
                provenance = FactProvenance.CHAPTER_ANALYSIS,
                conflictState = FactConflictState.RESOLVED,
            ),
        ),
        scenes: List<ProfileScene> = listOf(
            ProfileScene(
                sceneId = "scene_1",
                pageRange = PageRange(0, 5),
                register = SceneRegister.CASUAL,
                participants = listOf("ent_1"),
            ),
        ),
    ): ChapterTranslationProfile {
        val draft = ChapterTranslationProfile(
            version = version,
            contentFingerprint = "",
            profileInputFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            sourceRunId = "run_ch1",
            analyzerProvenance = AnalyzerProvenance(
                providerId = "lm_studio",
                modelId = "qwen-2.5",
                promptVersion = 1,
                analysisSchemaVersion = 1,
            ),
            entities = entities,
            terms = terms,
            scenes = scenes,
            frozenAtEpochMs = 1_000_000L,
        )
        return draft.copy(
            contentFingerprint = StageFingerprints.profileContentFingerprint(draft),
        )
    }

    private fun sampleRunConfig(
        sourceLang: String = "ja",
        targetLang: String = "en",
        providerKey: String = "lm_studio:qwen-2.5",
    ): eu.kanade.translation.persistence.artifact.RunConfigSnapshot = ChapterProfileBatchCoordinator.frozenRunConfig(
        sourceLang = sourceLang,
        targetLang = targetLang,
        ocrEngine = "MangaOcr",
        inpaintMode = "FILL",
        providerKey = providerKey,
        credentialId = "test_cred",
    )

    @Test
    fun `series profile registry records and retrieves valid profiles`() {
        val profile = sampleProfile()
        SeriesProfileRegistry.register(
            seriesKey = "manga_101",
            profile = profile,
            sourceLang = "ja",
            targetLang = "en",
            providerKey = "lm_studio:qwen-2.5",
        )

        val retrieved = SeriesProfileRegistry.get("manga_101")
        retrieved shouldNotBe null
        retrieved!!.seriesKey shouldBe "manga_101"
        retrieved.profile.version shouldBe 1
        retrieved.profile.entities.size shouldBe 1
    }

    @Test
    fun `drift gating approves matching series profile and rejects mismatched configurations`() {
        val profile = sampleProfile()
        SeriesProfileRegistry.register(
            seriesKey = "manga_101",
            profile = profile,
            sourceLang = "ja",
            targetLang = "en",
            providerKey = "lm_studio:qwen-2.5",
        )
        val carried = SeriesProfileRegistry.get("manga_101")!!

        // 1. Matches -> safe
        SeriesProfileRegistry.isDriftSafe(carried, sampleRunConfig()) shouldBe true

        // 2. Mismatched source language -> unsafe
        SeriesProfileRegistry.isDriftSafe(
            carried,
            sampleRunConfig(sourceLang = "ko"),
        ) shouldBe false

        // 3. Mismatched target language -> unsafe
        SeriesProfileRegistry.isDriftSafe(
            carried,
            sampleRunConfig(targetLang = "es"),
        ) shouldBe false

        // 4. Mismatched provider family -> unsafe
        SeriesProfileRegistry.isDriftSafe(
            carried,
            sampleRunConfig(providerKey = "gemini:gemini-1.5-flash"),
        ) shouldBe false
    }

    @Test
    fun `adoptForChapter converts facts to SERIES_CANON, clears scenes, and stamps valid fingerprints`() {
        val original = sampleProfile()
        val carried = SeriesProfileRegistry.CarriedOverProfile(
            seriesKey = "manga_101",
            profile = original,
            sourceLang = "ja",
            targetLang = "en",
            providerKey = "lm_studio:qwen-2.5",
        )

        val newFingerprint = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        val adopted = SeriesProfileRegistry.adoptForChapter(
            carried = carried,
            runId = "run_ch2",
            profileInputFingerprint = newFingerprint,
            nowEpochMs = 2_000_000L,
        )

        adopted.sourceRunId shouldBe "run_ch2"
        adopted.profileInputFingerprint shouldBe newFingerprint
        adopted.version shouldBe 1
        adopted.scenes shouldBe emptyList() // Scenes must NOT carry over across chapters
        adopted.entities.size shouldBe 1
        adopted.entities[0].provenance shouldBe FactProvenance.SERIES_CANON
        adopted.terms.size shouldBe 1
        adopted.terms[0].provenance shouldBe FactProvenance.SERIES_CANON
        adopted.isSemanticallyValid shouldBe true
    }

    @Test
    fun `sse response parsing handles chunked streaming deltas and ignores comments`() {
        val ssePayload = """
            : keep-alive heartbeat
            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"Once upon "}}]}

            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"a time "}}]}
            : intermediate comment
            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"in a manga."}}]}

            data: [DONE]
        """.trimIndent()

        val parsed = OpenAiCompatibleTranslator.parseSseResponse(ssePayload)
        parsed shouldBe "Once upon a time in a manga."
    }

    @Test
    fun `sse response parsing supports legacy text format and handles empty frames`() {
        val ssePayload = """
            data: {"choices":[{"text":"Chapter "}]}
            data: {"choices":[{"text":"42"}]}
            data: [DONE]
        """.trimIndent()

        val parsed = OpenAiCompatibleTranslator.parseSseResponse(ssePayload)
        parsed shouldBe "Chapter 42"
    }

    @Test
    fun `multi backend router routes operations to distinct backends`() {
        val router = MultiBackendRouter.dual(
            translationBackend = "lm_studio",
            analysisBackend = "gemini",
        )

        router.routeOperation("analysis") shouldBe "gemini"
        router.routeOperation("translation") shouldBe "lm_studio"
        router.routeOperation("other") shouldBe "lm_studio"

        val analysisKey = router.requestKeyFor("analysis", model = "gemini-1.5-flash")
        analysisKey.backend shouldBe "gemini"
        analysisKey.model shouldBe "gemini-1.5-flash"

        val translationKey = router.requestKeyFor("translation", model = "qwen-2.5")
        translationKey.backend shouldBe "lm_studio"
        translationKey.model shouldBe "qwen-2.5"
    }

    @Test
    fun `provider request governor executes distinct backends concurrently`() {
        runBlocking {
            val governor = ProviderRequestGovernor(
                policy = {
                    ProviderQuotaPolicy(
                        maxInFlight = 1,
                        minimumSpacingMs = 10L,
                    )
                },
            )

            val activeBackends = AtomicInteger(0)
            val maxSimultaneousBackends = AtomicInteger(0)

            val meta1 = ProviderRequestMetadata(
                key = ProviderRequestKey(backend = "lm_studio", model = "qwen-2.5"),
                priority = AdmissionPriority.BACKGROUND,
            )
            val meta2 = ProviderRequestMetadata(
                key = ProviderRequestKey(backend = "gemini", model = "flash"),
                priority = AdmissionPriority.BACKGROUND,
            )

            val job1 = async {
                governor.executeValue(meta1) {
                    val cur = activeBackends.incrementAndGet()
                    maxSimultaneousBackends.updateAndGet { maxOf(it, cur) }
                    delay(100)
                    activeBackends.decrementAndGet()
                    "done1"
                }
            }

            val job2 = async {
                governor.executeValue(meta2) {
                    val cur = activeBackends.incrementAndGet()
                    maxSimultaneousBackends.updateAndGet { maxOf(it, cur) }
                    delay(100)
                    activeBackends.decrementAndGet()
                    "done2"
                }
            }

            job1.await() shouldBe "done1"
            job2.await() shouldBe "done2"

            // Both distinct backends executed concurrently!
            maxSimultaneousBackends.get() shouldBe 2
        }
    }
}
