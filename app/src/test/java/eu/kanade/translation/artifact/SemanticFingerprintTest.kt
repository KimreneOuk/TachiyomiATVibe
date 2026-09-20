package eu.kanade.translation.artifact

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.text.Normalizer

/**
 * 09 determinism and equivalence gate for the semantic fingerprint
 * builders: repeated computation over equal semantic
 * inputs is identical;  exclusions do not affect values;
 * length-prefix collision fixtures pass; golden fixtures for a 200-page
 * synthetic corpus and a frozen profile are byte-stable.
 */
class SemanticFingerprintTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun hex(seed: Char) = seed.toString().repeat(64)

    private fun blockContent(
        stableBlockId: String? = "p1_b1",
        sourceText: String = "テスト",
        x: Float = 10f,
        y: Float = 20f,
        width: Float = 100f,
        height: Float = 30f,
        angle: Float = 0f,
        label: Int = 1,
    ) = OcrBlockContent(stableBlockId, sourceText, x, y, width, height, angle, label)

    private fun pageOcrContent(
        pageKey: String = "0001.jpg",
        naturalPageIndex: Int? = 0,
        sourceSha256: String = hex('a'),
        sourceWidth: Int = 1200,
        sourceHeight: Int = 1800,
        sourceOrientation: String = "PORTRAIT",
        detectionFingerprint: String? = hex('b'),
        ocrFingerprint: String = hex('c'),
        textless: Boolean = false,
        inpaintMaskRevision: Int = 10,
        blocks: List<OcrBlockContent> = listOf(blockContent()),
        inpaintMaskBoxes: List<InpaintMaskBox> = listOf(InpaintMaskBox(0, 0, 100, 40, 1)),
    ) = StageFingerprints.pageOcrContentFingerprint(
        pageKey,
        naturalPageIndex,
        sourceSha256,
        sourceWidth,
        sourceHeight,
        sourceOrientation,
        detectionFingerprint,
        ocrFingerprint,
        textless,
        inpaintMaskRevision,
        blocks,
        inpaintMaskBoxes,
    )

    private fun translationBlock(
        text: String,
        translation: String = "",
        score: Float = 1f,
        userEditedAt: Long? = null,
        textColor: Long = 0xFF000000,
        strokeColor: Long = 0xFFFFFFFF,
        strokeWidth: Float = 0f,
        blockId: String? = "p1_b1",
    ) = TranslationBlock(
        blockId = blockId,
        text = text,
        translation = translation,
        width = 100f,
        height = 30f,
        x = 10f,
        y = 20f,
        symHeight = 30f,
        symWidth = 100f,
        angle = 0f,
        label = 1,
        score = score,
        textColor = textColor,
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
        userEditedAt = userEditedAt,
    )

    private fun snapshotPage(blocks: List<TranslationBlock>, masks: List<InpaintMaskBox>) =
        PageTranslation(blocks = blocks.toMutableList(), inpaintMaskBoxes = masks)

    private fun profile(
        version: Int = 1,
        frozenAtEpochMs: Long = 1_757_050_800_000,
        sourceRunId: String = "run-1757050000000-a1b2c3d4",
        entitySourceForm: String = "レイナ",
        sceneToneFlags: Set<ToneFlag> = setOf(ToneFlag.EXPLICIT, ToneFlag.SERIOUS),
    ) = ChapterTranslationProfile(
        version = version,
        contentFingerprint = hex('0'),
        profileInputFingerprint = hex('1'),
        sourceRunId = sourceRunId,
        analyzerProvenance = AnalyzerProvenance("gemini", "gemini-2.5", 3, 1),
        entities = listOf(
            ProfileFact(
                factId = "f-1",
                type = FactType.ENTITY_IDENTITY,
                canonicalSourceForm = entitySourceForm,
                canonicalTargetForm = "Reina Alstella",
                aliases = listOf("レイナ・アルステラ", "Raina"),
                confidence = 0.93f,
                evidenceStrength = EvidenceStrength.EXPLICIT,
                scope = FactScope.CANONICAL_CHAPTER_WIDE,
                evidenceRefs = listOf(EvidenceRef("0180.jpg", "p180_b4", hex('2'))),
                provenance = FactProvenance.CHAPTER_ANALYSIS,
                conflictState = FactConflictState.RESOLVED,
                note = "weak male cue on p12 rejected",
            ),
        ),
        scenes = listOf(
            ProfileScene(
                sceneId = "s-3",
                pageRange = PageRange(40, 44),
                participants = listOf("f-1"),
                toneFlags = sceneToneFlags,
                register = SceneRegister.ROUGH,
                narrativeContext = "confrontation in the archive",
            ),
        ),
        frozenAtEpochMs = frozenAtEpochMs,
    )

    private fun provenance(
        profileContentFingerprint: String = hex('d'),
        provider: String = "gemini",
        model: String = "gemini-2.5",
        credential: String? = "cred-sig",
        protocolVersion: Int = 2,
        promptVersion: Int = 3,
        sourceLanguage: String = "ja",
        targetLanguage: String = "en",
        pages: List<TranslationProvenancePage> = listOf(
            TranslationProvenancePage(
                pageOcrContentFingerprint = pageOcrContent(),
                orderedStableBlockIds = listOf("p1_b1", "p1_b2"),
                orderedBlockSourceTextHashes = listOf(hex('e'), hex('f')),
            ),
        ),
    ) = StageFingerprints.translationProvenanceFingerprint(
        profileContentFingerprint,
        provider,
        model,
        credential,
        protocolVersion,
        promptVersion,
        sourceLanguage,
        targetLanguage,
        pages,
    )

    private fun layoutCompatible(
        translationArtifactId: String = "t",
        cleanedImageArtifactIdOrOriginalSourceId: String = "c",
        layoutEngineVersion: String = "v1",
        fontIdentity: String = "animeace",
        fontScalePreferences: String = "1.0",
        stylePreferences: String = "none",
        outputDimensions: String = "1200x1800",
        fontAssetName: String = "font/animeace.ttf",
        fontAssetSha256: String = hex('3'),
        typefaceStyle: String = "BOLD",
        paintMeasurementFlags: String = "ANTI_ALIAS|SUBPIXEL_TEXT",
        layoutPlannerVersion: Int = 2,
        platformShapingKey: String = "sdk35-shaping-bucketA",
        strokePolicyVersion: Int = 1,
        decodeSampleSize: Int = 1,
        sourcePageWidth: Float = 1200f,
        sourcePageHeight: Float = 1800f,
    ) = StageFingerprints.layoutCompatibilityFingerprint(
        translationArtifactId,
        cleanedImageArtifactIdOrOriginalSourceId,
        layoutEngineVersion,
        fontIdentity,
        fontScalePreferences,
        stylePreferences,
        outputDimensions,
        fontAssetName,
        fontAssetSha256,
        typefaceStyle,
        paintMeasurementFlags,
        layoutPlannerVersion,
        platformShapingKey,
        strokePolicyVersion,
        decodeSampleSize,
        sourcePageWidth,
        sourcePageHeight,
    )

    private fun colorStyle(
        colorEstimatorVersion: Int = 1,
        cleanedImageFileName: String? = "cleaned_0001.jpg",
        cleanedInpaintRevision: Int? = 3,
        originalSourceSha256: String? = null,
        blockGeometryFingerprints: List<String> = listOf(hex('4'), hex('5')),
        pageWidth: Float = 1200f,
        pageHeight: Float = 1800f,
    ) = StageFingerprints.colorStyleFingerprint(
        colorEstimatorVersion,
        cleanedImageFileName,
        cleanedInpaintRevision,
        originalSourceSha256,
        blockGeometryFingerprints,
        pageWidth,
        pageHeight,
    )

    private fun corpus(pages: Int = 3, naturalOrderProven: Boolean = true) =
        StageFingerprints.ocrCorpusFingerprint(
            pages = (0 until pages).map { i ->
                "page_${i.toString().padStart(4, '0')}.jpg" to
                    StageFingerprints.sourceExcerptHash("synthetic-$i")
            },
            expectedPageCount = pages,
            expectedPageCountTrusted = true,
            naturalOrderProven = naturalOrderProven,
        )

    // ------------------------------------------------------------------
    // 02: PageOcrContentFingerprint
    // ------------------------------------------------------------------

    @Test
    fun `page ocr content is deterministic across repeated computation`() {
        pageOcrContent() shouldBe pageOcrContent()
    }

    @Test
    fun `page ocr content excludes translation state, user edits, colors and score`() {
        // 01/FP-02: a pure OCR content key must not change when a user
        // edits target text; translation/colors/score are excluded.
        val plain = snapshotPage(
            blocks = listOf(translationBlock("テスト")),
            masks = listOf(InpaintMaskBox(0, 0, 100, 40, 1)),
        )
        val edited = snapshotPage(
            blocks = listOf(
                translationBlock(
                    text = "テスト",
                    translation = "user override",
                    score = 0.42f,
                    userEditedAt = 1_757_050_000_000,
                    textColor = 0xFF112233,
                    strokeColor = 0xFF445566,
                    strokeWidth = 4.5f,
                ),
            ),
            masks = listOf(InpaintMaskBox(0, 0, 100, 40, 1)),
        )
        val plainFp = pageOcrContent(blocks = StageFingerprints.pageOcrContentBlocks(plain))
        val editedFp = pageOcrContent(blocks = StageFingerprints.pageOcrContentBlocks(edited))
        plainFp shouldBe editedFp
    }

    @Test
    fun `page ocr content is sensitive to every included field`() {
        val base = pageOcrContent()
        pageOcrContent(pageKey = "0002.jpg") shouldNotBe base
        pageOcrContent(naturalPageIndex = 1) shouldNotBe base
        pageOcrContent(naturalPageIndex = null) shouldNotBe base
        pageOcrContent(sourceSha256 = hex('z')) shouldNotBe base
        pageOcrContent(sourceWidth = 1201) shouldNotBe base
        pageOcrContent(sourceHeight = 1801) shouldNotBe base
        pageOcrContent(sourceOrientation = "LANDSCAPE") shouldNotBe base
        pageOcrContent(detectionFingerprint = hex('y')) shouldNotBe base
        pageOcrContent(detectionFingerprint = null) shouldNotBe base
        pageOcrContent(ocrFingerprint = hex('x')) shouldNotBe base
        pageOcrContent(textless = true) shouldNotBe base
        pageOcrContent(inpaintMaskRevision = 11) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(stableBlockId = "p1_b2"))) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(sourceText = "違う"))) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(x = 11f))) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(y = 21f))) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(width = 101f))) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(height = 31f))) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(angle = 0.5f))) shouldNotBe base
        pageOcrContent(blocks = listOf(blockContent(label = 2))) shouldNotBe base
        pageOcrContent(
            blocks = listOf(blockContent(), blockContent(stableBlockId = "p1_b2")),
        ) shouldNotBe base
        pageOcrContent(inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 101, 40, 1))) shouldNotBe base
        pageOcrContent(inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 100, 40, 2))) shouldNotBe base
        pageOcrContent(
            inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 100, 40, 1), InpaintMaskBox(5, 5, 9, 9, 2)),
        ) shouldNotBe base
    }

    @Test
    fun `page ocr content normalizes NFC and line endings before hashing`() {
        val composed = "カタカナ"
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        pageOcrContent(blocks = listOf(blockContent(sourceText = composed))) shouldBe
            pageOcrContent(blocks = listOf(blockContent(sourceText = decomposed)))
        pageOcrContent(blocks = listOf(blockContent(sourceText = "line1\r\nline2"))) shouldBe
            pageOcrContent(blocks = listOf(blockContent(sourceText = "line1\nline2")))
        pageOcrContent(blocks = listOf(blockContent(sourceText = "line1\rline2"))) shouldBe
            pageOcrContent(blocks = listOf(blockContent(sourceText = "line1\nline2")))
        // Case is semantic (no case folding).
        pageOcrContent(blocks = listOf(blockContent(sourceText = "ABC"))) shouldNotBe
            pageOcrContent(blocks = listOf(blockContent(sourceText = "abc")))
    }

    @Test
    fun `page ocr content resists delimiter forgery`() {
        // Under naive concatenation both sides produce "p1_b1|甲|1:乙|";
        // length-prefixed fields must keep them distinct.
        val one = pageOcrContent(
            blocks = listOf(blockContent(stableBlockId = "p1_b1", sourceText = "甲|1:乙|")),
        )
        val two = pageOcrContent(
            blocks = listOf(blockContent(stableBlockId = "p1_b1|1:甲|", sourceText = "乙")),
        )
        one shouldNotBe two
        // A two-block split must never collide with a forged single block.
        val split = pageOcrContent(
            blocks = listOf(blockContent(stableBlockId = "a", sourceText = "b"), blockContent(stableBlockId = "c", sourceText = "d")),
        )
        val forged = pageOcrContent(
            blocks = listOf(blockContent(stableBlockId = "a", sourceText = "b|1:c|1:d|")),
        )
        split shouldNotBe forged
    }

    // ------------------------------------------------------------------
    // 03: OcrCorpusFingerprint
    // ------------------------------------------------------------------

    @Test
    fun `corpus fingerprint survives an identical OCR re-run`() {
        corpus() shouldBe corpus()
    }

    @Test
    fun `corpus fingerprint detects page insertion, removal and reorder`() {
        val three = corpus(pages = 3)
        corpus(pages = 4) shouldNotBe three
        corpus(pages = 2) shouldNotBe three
        val reordered = StageFingerprints.ocrCorpusFingerprint(
            pages = (2 downTo 0).map { i ->
                "page_${i.toString().padStart(4, '0')}.jpg" to
                    StageFingerprints.sourceExcerptHash("synthetic-$i")
            },
            expectedPageCount = 3,
            expectedPageCountTrusted = true,
            naturalOrderProven = true,
        )
        reordered shouldNotBe three
    }

    @Test
    fun `corpus fingerprint sorts by pageKey when order is unproven`() {
        val pages = (0 until 4).map { i ->
            "page_${i.toString().padStart(4, '0')}.jpg" to
                StageFingerprints.sourceExcerptHash("synthetic-$i")
        }
        val shuffled = StageFingerprints.ocrCorpusFingerprint(
            pages = pages.asReversed(),
            expectedPageCount = 4,
            expectedPageCountTrusted = true,
            naturalOrderProven = false,
        )
        val inOrder = StageFingerprints.ocrCorpusFingerprint(
            pages = pages,
            expectedPageCount = 4,
            expectedPageCountTrusted = true,
            naturalOrderProven = false,
        )
        shuffled shouldBe inOrder
        // The ordered/unproven distinction is an explicit hashed field.
        StageFingerprints.ocrCorpusFingerprint(
            pages = pages,
            expectedPageCount = 4,
            expectedPageCountTrusted = true,
            naturalOrderProven = true,
        ) shouldNotBe inOrder
        StageFingerprints.ocrCorpusFingerprint(
            pages = pages,
            expectedPageCount = 5,
            expectedPageCountTrusted = true,
            naturalOrderProven = false,
        ) shouldNotBe inOrder
        StageFingerprints.ocrCorpusFingerprint(
            pages = pages,
            expectedPageCount = 4,
            expectedPageCountTrusted = false,
            naturalOrderProven = false,
        ) shouldNotBe inOrder
    }

    // ------------------------------------------------------------------
    // 04: ProfileInputFingerprint
    // ------------------------------------------------------------------

    @Test
    fun `profile input is deterministic and sensitive to every input`() {
        val base = StageFingerprints.profileInputFingerprint(
            ocrCorpusFingerprint = corpus(),
            sourceLanguage = "ja",
            targetLanguage = "en",
            analysisSchemaVersion = 1,
            analysisPromptVersion = 3,
            analyzerProvider = "gemini",
            analyzerModel = "gemini-2.5",
            analyzerCredentialSignature = "cred-sig",
            analyzerPolicyFingerprint = hex('6'),
            userAuthorityFingerprint = hex('7'),
            seriesAuthorityFingerprint = null,
        )
        base shouldBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            hex('9'), "ja", "en", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ko", "en", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "pt", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 2, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 4, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "openai", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.0", "cred-sig", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", "other-cred", hex('6'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('8'), hex('7'), null,
        )
        base shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), hex('a'),
        )
    }

    @Test
    fun `profile input absence is the ABSENT literal, never the empty string`() {
        val absent = StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", null, hex('6'), null, null,
        )
        // Absence must be a distinct value, never an empty-string collision.
        absent shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", "", hex('6'), null, null,
        )
        absent shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", null, hex('6'), "", null,
        )
        absent shouldBe StageFingerprints.profileInputFingerprint(
            corpus(), "ja", "en", 1, 3, "gemini", "gemini-2.5", null, hex('6'), null, null,
        )
    }

    @Test
    fun `profile input resists delimiter forgery`() {
        // 08: under naive concatenation "en" + "-US" fuses into the
        // same string as the single "en-US" tag; length-prefixed fields must
        // keep the split and fused identities distinct.
        StageFingerprints.profileInputFingerprint(
            corpus(), "en", "-US", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        ) shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "en-US", "", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
        // A delimiter-bearing field must not fuse the two language fields
        // into one ("en" + "en-US" vs "enen" + "-US" collide naively).
        StageFingerprints.profileInputFingerprint(
            corpus(), "en", "en-US", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        ) shouldNotBe StageFingerprints.profileInputFingerprint(
            corpus(), "enen", "-US", 1, 3, "gemini", "gemini-2.5", "cred-sig", hex('6'), hex('7'), null,
        )
    }

    // ------------------------------------------------------------------
    // 05: ProfileContentFingerprint
    // ------------------------------------------------------------------

    @Test
    fun `profile content ignores operational fields`() {
        val base = StageFingerprints.profileContentFingerprint(profile())
        // Monotonic version is operational ordering ONLY.
        StageFingerprints.profileContentFingerprint(profile(version = 2)) shouldBe base
        StageFingerprints.profileContentFingerprint(profile(frozenAtEpochMs = 1L)) shouldBe base
        StageFingerprints.profileContentFingerprint(profile(sourceRunId = "run-other")) shouldBe base
    }

    @Test
    fun `profile content is sensitive to every hashed field`() {
        val base = StageFingerprints.profileContentFingerprint(profile())
        StageFingerprints.profileContentFingerprint(profile(entitySourceForm = "レイナ2")) shouldNotBe base
        StageFingerprints.profileContentFingerprint(
            profile(sceneToneFlags = setOf(ToneFlag.SERIOUS)),
        ) shouldNotBe base
        StageFingerprints.profileContentFingerprint(
            profile().copy(analyzerProvenance = AnalyzerProvenance("gemini", "gemini-2.5", 4, 1)),
        ) shouldNotBe base
        StageFingerprints.profileContentFingerprint(
            profile().copy(profileInputFingerprint = hex('b')),
        ) shouldNotBe base
    }

    @Test
    fun `profile content survives canonical decode-reencode round trip`() {
        val original = profile()
        val encoded = ArtifactDocumentJson.encodeToString(
            ChapterTranslationProfile.serializer(),
            original,
        )
        val decoded = ArtifactDocumentJson.decodeFromString(
            ChapterTranslationProfile.serializer(),
            encoded,
        )
        StageFingerprints.profileContentFingerprint(decoded) shouldBe
            StageFingerprints.profileContentFingerprint(original)
    }

    @Test
    fun `profile content matches the golden byte-stable fixture`() {
        StageFingerprints.profileContentFingerprint(profile()) shouldBe
            "e18be544e838bf4c59b29cf996b6816cb5382284f3efac4dac79568a54b667e9"
    }

    // ------------------------------------------------------------------
    // 06: TranslationProvenanceFingerprint
    // ------------------------------------------------------------------

    @Test
    fun `translation provenance is deterministic and sensitive to provenance inputs`() {
        val base = provenance()
        base shouldBe provenance()
        provenance(profileContentFingerprint = hex('1')) shouldNotBe base
        provenance(provider = "openai") shouldNotBe base
        provenance(model = "gpt-5") shouldNotBe base
        provenance(credential = "other") shouldNotBe base
        provenance(credential = null) shouldNotBe base
        provenance(protocolVersion = 3) shouldNotBe base
        provenance(promptVersion = 4) shouldNotBe base
        provenance(sourceLanguage = "ko") shouldNotBe base
        provenance(targetLanguage = "pt") shouldNotBe base
        provenance(
            pages = listOf(
                TranslationProvenancePage(pageOcrContent(), listOf("p1_b1"), listOf(hex('e'))),
            ),
        ) shouldNotBe base
        provenance(
            pages = listOf(
                TranslationProvenancePage(pageOcrContent(), listOf("p1_b2", "p1_b1"), listOf(hex('e'), hex('f'))),
            ),
        ) shouldNotBe base
        val otherPage = TranslationProvenancePage(
            pageOcrContentFingerprint = pageOcrContent(pageKey = "0002.jpg"),
            orderedStableBlockIds = listOf("p1_b1"),
            orderedBlockSourceTextHashes = listOf(hex('e')),
        )
        provenance(pages = listOf(otherPage)) shouldNotBe base
        // Contributing page order is part of the provenance input set.
        provenance(
            pages = listOf(
                TranslationProvenancePage(pageOcrContent(), listOf("p1_b1", "p1_b2"), listOf(hex('e'), hex('f'))),
                otherPage,
            ),
        ) shouldNotBe base
    }

    @Test
    fun `source excerpt hash normalizes NFC and line endings`() {
        val composed = "ラーメン"
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        StageFingerprints.sourceExcerptHash(composed) shouldBe
            StageFingerprints.sourceExcerptHash(decomposed)
        StageFingerprints.sourceExcerptHash("a\r\nb") shouldBe
            StageFingerprints.sourceExcerptHash("a\nb")
        StageFingerprints.sourceExcerptHash("abc") shouldNotBe
            StageFingerprints.sourceExcerptHash("ABC")
    }

    @Test
    fun `translation provenance resists delimiter forgery`() {
        // 08 / FP-09(c): naive flattening must not fuse the block-id
        // list inside a page, nor across a contributing-page boundary.
        val pageFingerprint = pageOcrContent()
        val twoBlocks = provenance(
            pages = listOf(
                TranslationProvenancePage(
                    pageOcrContentFingerprint = pageFingerprint,
                    orderedStableBlockIds = listOf("p1_b1", "p1_b2"),
                    orderedBlockSourceTextHashes = listOf(hex('e'), hex('f')),
                ),
            ),
        )
        // The same flattened block stream, forged as a single block id.
        twoBlocks shouldNotBe provenance(
            pages = listOf(
                TranslationProvenancePage(
                    pageOcrContentFingerprint = pageFingerprint,
                    orderedStableBlockIds = listOf("p1_b1|1:p1_b2|"),
                    orderedBlockSourceTextHashes = listOf(hex('e')),
                ),
            ),
        )
        // Contributing-page boundary: [b1,b2]+[b3] must never collide with
        // [b1]+[b2,b3] under naive concatenation of the block stream.
        val leftHeavy = provenance(
            pages = listOf(
                TranslationProvenancePage(
                    pageOcrContentFingerprint = pageFingerprint,
                    orderedStableBlockIds = listOf("p1_b1", "p1_b2"),
                    orderedBlockSourceTextHashes = listOf(hex('e'), hex('f')),
                ),
                TranslationProvenancePage(
                    pageOcrContentFingerprint = pageFingerprint,
                    orderedStableBlockIds = listOf("p1_b3"),
                    orderedBlockSourceTextHashes = listOf(hex('0')),
                ),
            ),
        )
        val rightHeavy = provenance(
            pages = listOf(
                TranslationProvenancePage(
                    pageOcrContentFingerprint = pageFingerprint,
                    orderedStableBlockIds = listOf("p1_b1"),
                    orderedBlockSourceTextHashes = listOf(hex('e')),
                ),
                TranslationProvenancePage(
                    pageOcrContentFingerprint = pageFingerprint,
                    orderedStableBlockIds = listOf("p1_b2", "p1_b3"),
                    orderedBlockSourceTextHashes = listOf(hex('f'), hex('0')),
                ),
            ),
        )
        leftHeavy shouldNotBe rightHeavy
    }

    // ------------------------------------------------------------------
    // 07: LayoutCompatibilityFingerprint
    // ------------------------------------------------------------------

    @Test
    fun `layout compatibility is deterministic and sensitive to every component`() {
        val base = layoutCompatible()
        base shouldBe layoutCompatible()
        layoutCompatible(translationArtifactId = "t2") shouldNotBe base
        layoutCompatible(cleanedImageArtifactIdOrOriginalSourceId = "c2") shouldNotBe base
        layoutCompatible(layoutEngineVersion = "v2") shouldNotBe base
        layoutCompatible(fontIdentity = "other") shouldNotBe base
        layoutCompatible(fontScalePreferences = "1.25") shouldNotBe base
        layoutCompatible(stylePreferences = "bold") shouldNotBe base
        layoutCompatible(outputDimensions = "900x1350") shouldNotBe base
        layoutCompatible(fontAssetName = "font/other.ttf") shouldNotBe base
        layoutCompatible(fontAssetSha256 = hex('4')) shouldNotBe base
        layoutCompatible(typefaceStyle = "NORMAL") shouldNotBe base
        layoutCompatible(paintMeasurementFlags = "ANTI_ALIAS") shouldNotBe base
        layoutCompatible(layoutPlannerVersion = 3) shouldNotBe base
        layoutCompatible(platformShapingKey = "sdk34-shaping-bucketB") shouldNotBe base
        layoutCompatible(strokePolicyVersion = 2) shouldNotBe base
        layoutCompatible(decodeSampleSize = 2) shouldNotBe base
        layoutCompatible(sourcePageWidth = 1201f) shouldNotBe base
        layoutCompatible(sourcePageHeight = 1801f) shouldNotBe base
    }

    // ------------------------------------------------------------------
    // 08: ColorStyleFingerprint
    // ------------------------------------------------------------------

    @Test
    fun `color style is deterministic and sensitive to pixel identity and geometry`() {
        val base = colorStyle()
        base shouldBe colorStyle()
        colorStyle(colorEstimatorVersion = 2) shouldNotBe base
        colorStyle(cleanedImageFileName = "cleaned_0002.jpg") shouldNotBe base
        colorStyle(cleanedInpaintRevision = 4) shouldNotBe base
        colorStyle(blockGeometryFingerprints = listOf(hex('4'))) shouldNotBe base
        colorStyle(
            blockGeometryFingerprints = listOf(hex('5'), hex('4')),
        ) shouldNotBe base
        colorStyle(pageWidth = 1201f) shouldNotBe base
        colorStyle(pageHeight = 1801f) shouldNotBe base
        // Consuming the original source pixels is a different identity.
        colorStyle(
            cleanedImageFileName = null,
            cleanedInpaintRevision = null,
            originalSourceSha256 = hex('a'),
        ) shouldNotBe base
        colorStyle(
            cleanedImageFileName = null,
            cleanedInpaintRevision = null,
            originalSourceSha256 = hex('b'),
        ) shouldNotBe colorStyle(
            cleanedImageFileName = null,
            cleanedInpaintRevision = null,
            originalSourceSha256 = hex('a'),
        )
    }

    // ------------------------------------------------------------------
    // 09d: 200-page synthetic corpus golden
    // ------------------------------------------------------------------

    @Test
    fun `200 page synthetic corpus fingerprint matches the golden fixture`() {
        val pages = (0 until 200).map { i ->
            "page_${i.toString().padStart(4, '0')}.jpg" to
                StageFingerprints.sourceExcerptHash("synthetic-$i")
        }
        val fingerprint = StageFingerprints.ocrCorpusFingerprint(
            pages = pages,
            expectedPageCount = 200,
            expectedPageCountTrusted = true,
            naturalOrderProven = true,
        )
        fingerprint shouldBe
            "e5bda1be83fd681f73c08d5a169b18a2b34c67163cf29f42c824bd80d94ac37f"
    }
}
