package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.stableFingerprint
import java.security.MessageDigest
import java.text.Normalizer

/**
 * TachiyomiAT: deterministic provenance fingerprints for the chapter artifact
 * manifest (artifact lifecycle contract §§4–8). Each builder canonicalizes its
 * direct inputs and configuration with length-prefixed fields, so no delimiter
 * ambiguity can make two different input sets collide.
 *
 * A stage is reusable only when its recorded fingerprint equals the newly
 * computed expected fingerprint; timestamps and queue state are never inputs.
 */
object StageFingerprints {

    /**
     * Fingerprint a stage configuration when the large content-addressed
     * artifact is not available yet.  Keeping configuration fields length
     * prefixed makes this safe for persisted page provenance and tests.
     */
    fun configuration(stage: ArtifactStage, vararg fields: Any?): String =
        fingerprintIndexed(listOf("configuration", stage.name) + fields.toList())

    /** Detection, segmentation, masks, and reading order (contract §4). */
    fun detection(
        sourceHash: String,
        detectorModelHash: String,
        segmenterModelHash: String,
        nativeProtocolVersion: Int,
        thresholds: String,
        maskPostprocessVersion: Int,
        panelAssignmentVersion: Int,
        readingOrderVersion: Int,
    ): String = fingerprint(
        "detection",
        sourceHash,
        detectorModelHash,
        segmenterModelHash,
        nativeProtocolVersion,
        thresholds,
        maskPostprocessVersion,
        panelAssignmentVersion,
        readingOrderVersion,
    )

    /** OCR source blocks (contract §5). */
    fun ocr(
        detectionArtifactId: String,
        ocrEngineVersion: String,
        ocrModelHash: String,
        sourceLanguage: String,
        preprocessingVersion: Int,
        textNormalizationVersion: Int,
    ): String = fingerprint(
        "ocr",
        detectionArtifactId,
        ocrEngineVersion,
        ocrModelHash,
        sourceLanguage,
        preprocessingVersion,
        textNormalizationVersion,
    )

    /** Cleaned image / inpaint artifact (contract §6). */
    fun inpaint(
        sourceHash: String,
        maskArtifactId: String,
        inpaintEngineVersion: String,
        inpaintModelHash: String,
        inpaintMode: String,
        inpaintSettings: String,
        cleanupRevision: Int,
    ): String = fingerprint(
        "inpaint",
        sourceHash,
        maskArtifactId,
        inpaintEngineVersion,
        inpaintModelHash,
        inpaintMode,
        inpaintSettings,
        cleanupRevision,
    )

    /** Render layout artifact (contract §8). */
    fun layout(
        translationArtifactId: String,
        cleanedImageArtifactIdOrOriginalSourceId: String,
        layoutEngineVersion: String,
        fontIdentity: String,
        fontScalePreferences: String,
        stylePreferences: String,
        outputDimensions: String,
    ): String = fingerprint(
        "layout",
        translationArtifactId,
        cleanedImageArtifactIdOrOriginalSourceId,
        layoutEngineVersion,
        fontIdentity,
        fontScalePreferences,
        stylePreferences,
        outputDimensions,
    )

    /**
     * Chapter glossary version fingerprint: vocabulary hints only (contract
     * §14.8). Each key and value is fed as its own length-prefixed field in
     * sorted-key order, so no comma/equals composite can alias a different
     * key/value split.
     */
    fun glossaryVersion(entries: Map<String, String>): String {
        val fields = mutableListOf<Any?>("glossary")
        entries.keys.sorted().forEach { key ->
            fields += key
            fields += entries.getValue(key)
        }
        return fingerprintIndexed(fields)
    }

    /** Failure fingerprint: identifies the failing configuration/source shape (contract §13). */
    fun failure(stage: ArtifactStage, failureMessage: String?, stageStatuses: List<String>): String =
        fingerprintIndexed("failure", stage.name, failureMessage ?: "", stageStatuses)

    /** Bundle fingerprint of a committed display bundle (contract §9). */
    fun committedBundle(
        sourceIdentity: SourceIdentity?,
        displayBase: DisplayBaseReference,
        translationFingerprint: String?,
        layoutFingerprint: String?,
    ): String = fingerprint(
        "bundle",
        sourceIdentity?.sha256 ?: "",
        displayBase.kind.name,
        displayBase.fileName ?: "",
        translationFingerprint ?: "",
        layoutFingerprint ?: "",
    )

    /** Fingerprint of the complete live-store page snapshot used by Phase 3. */
    fun pageSnapshot(page: PageTranslation): String = fingerprintIndexed(
        "page-snapshot",
        page.sourceFileName,
        page.cleanedImageName,
        page.ocrStatus,
        page.translationStatus,
        page.inpaintStatus,
        page.renderStatus,
        page.inpaintRevision,
        page.sourceFingerprint,
        page.detectionFingerprint,
        page.ocrFingerprint,
        page.inpaintFingerprint,
        page.translationFingerprint,
        page.layoutFingerprint,
        page.translationOrigin,
        page.retryCount,
        page.attemptCount,
        page.errorMessage,
        page.blocks.map { it.stableFingerprint() },
    )

    /**
     * 02 (`PageOcrContentFingerprint`): semantic content fingerprint
     * over one page's OCR payload. Inputs, in fixed order: corpus schema
     * version; pageKey; naturalPageIndex; source sha256 + width + height +
     * orientation; detection fingerprint (or the explicit [DETECTION_SKIPPED]
     * marker when detection was legitimately skipped); OCR engine/model/config
     * fingerprint (the persisted `StageFingerprints.ocr(...)` value);
     * per-block, in reading order: stable block id, NFC/LF-normalized source
     * text, geometry (`toRawBits` of x/y/width/height/angle) and label; the
     * textless state; the ordered `inpaintMaskBoxes` content (with label) plus
     * `inpaintMaskRevision`.
     *
     * Excluded per  block `translation`, `userEditedAt`, colors,
     * score-derived data (already covered by the detection fingerprint),
     * candidate/page versions, sidecar file names, generation ids. A pure OCR
     * content key must not change when a user edits target text — deliberately
     * differs from `TranslationBlock.stableFingerprint()`.
     */
    fun pageOcrContentFingerprint(
        pageKey: String,
        naturalPageIndex: Int?,
        sourceSha256: String,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceOrientation: String,
        detectionFingerprint: String?,
        ocrFingerprint: String,
        textless: Boolean,
        inpaintMaskRevision: Int,
        blocks: List<OcrBlockContent>,
        inpaintMaskBoxes: List<InpaintMaskBox>,
    ): String {
        val fields = mutableListOf<Any?>(
            "page-ocr-content",
            OCR_CORPUS_SCHEMA_VERSION,
            pageKey,
            naturalPageIndex,
            sourceSha256,
            sourceWidth,
            sourceHeight,
            sourceOrientation,
            detectionFingerprint ?: DETECTION_SKIPPED,
            ocrFingerprint,
        )
        blocks.forEachIndexed { index, block ->
            fields += "block[$index]"
            fields += block.stableBlockId
            fields += normalizeText(block.sourceText)
            fields += block.x.toRawBits()
            fields += block.y.toRawBits()
            fields += block.width.toRawBits()
            fields += block.height.toRawBits()
            fields += block.angle.toRawBits()
            fields += block.label
        }
        fields += textless
        inpaintMaskBoxes.forEachIndexed { index, box ->
            fields += "mask[$index]"
            fields += box.x1
            fields += box.y1
            fields += box.x2
            fields += box.y2
            fields += box.label
        }
        fields += inpaintMaskRevision
        return fingerprintIndexed(fields)
    }

    /**
     * 03 (`OcrCorpusFingerprint`): order-stable semantic fingerprint
     * over the chapter's full OCR corpus. Inputs: corpus schema version;
     * expected page count (+ trusted flag); the sequence of per-page
     * [OcrBlockContent]-level [StageFingerprints.pageOcrContentFingerprint]
     * values, length-prefixed and indexed.
     *
     * When [naturalOrderProven] is true, [pages] must be in natural page
     * order. When false (unprovable page ordering — the never-guess rule for
     * `naturalPageIndex`), the entries are hashed in sorted pageKey order and
     * `ordered=false` is recorded as an explicit field. Re-running OCR with
     * identical outputs does not change the value; page insertion, removal,
     * or reorder does.
     *
     * Interpretation note (recorded in the  fingerprints report): the
     * pageKey is fed explicitly next to each page content fingerprint so the
     * sorted-key fallback order is itself hashed, not just implied.
     */
    fun ocrCorpusFingerprint(
        pages: List<Pair<String, String>>,
        expectedPageCount: Int,
        expectedPageCountTrusted: Boolean,
        naturalOrderProven: Boolean,
    ): String {
        val orderedPages =
            if (naturalOrderProven) pages else pages.sortedBy { it.first }
        val fields = mutableListOf<Any?>(
            "ocr-corpus",
            OCR_CORPUS_SCHEMA_VERSION,
            expectedPageCount,
            expectedPageCountTrusted,
        )
        orderedPages.forEachIndexed { index, (pageKey, contentFingerprint) ->
            fields += "page[$index]"
            fields += pageKey
            fields += contentFingerprint
        }
        fields += naturalOrderProven
        return fingerprintIndexed(fields)
    }

    /**
     * 04 (`ProfileInputFingerprint`): identity of everything the
     * analysis/profile stage consumes. Absent user/series authority is the
     * explicit [AUTHORITY_ABSENT] literal — absence is a value, never an
     * empty-string collision. Any single input change changes the value; an
     * OCR re-run over an identical corpus does not.
     */
    fun profileInputFingerprint(
        ocrCorpusFingerprint: String,
        sourceLanguage: String,
        targetLanguage: String,
        analysisSchemaVersion: Int,
        analysisPromptVersion: Int,
        analyzerProvider: String,
        analyzerModel: String,
        analyzerCredentialSignature: String?,
        analyzerPolicyFingerprint: String,
        userAuthorityFingerprint: String?,
        seriesAuthorityFingerprint: String?,
    ): String = fingerprintIndexed(
        "profile-input",
        ocrCorpusFingerprint,
        sourceLanguage,
        targetLanguage,
        analysisSchemaVersion,
        analysisPromptVersion,
        analyzerProvider,
        analyzerModel,
        analyzerCredentialSignature,
        analyzerPolicyFingerprint,
        userAuthorityFingerprint ?: AUTHORITY_ABSENT,
        seriesAuthorityFingerprint ?: AUTHORITY_ABSENT,
    )

    /**
     * 05 /  (`ProfileContentFingerprint`): SHA-256 over the
     * canonical re-encoded JSON of the validated profile (decode-then-encode
     * under the shared [ArtifactDocumentJson] instance). Operational fields
     * (`version`, `frozenAtEpochMs`, `sourceRunId`) are zeroed in the hashing
     * copy only — stored bytes are never mutated. `contentFingerprint` itself
     * is blanked (a value cannot contain its own hash);
     * `profileInputFingerprint` stays a hashed field.
     */
    fun profileContentFingerprint(profile: ChapterTranslationProfile): String {
        val hashingView = profile.copy(
            version = 0,
            contentFingerprint = "",
            frozenAtEpochMs = 0L,
            sourceRunId = "",
        )
        val canonical = ArtifactDocumentJson.encodeToString(
            ChapterTranslationProfile.serializer(),
            hashingView,
        )
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * 06: translation provenance fingerprint (per page or per
     * envelope). Inputs: [ProfileContentFingerprint]; translator signature
     * (provider/model/credential + protocol version); prompt version; source
     * and target language; per contributing page, in order: that page's
     * [StageFingerprints.pageOcrContentFingerprint] plus the ordered stable
     * block ids and per-block source-text hashes actually sent (use
     * [StageFingerprints.sourceExcerptHash]).
     *
     * Envelope policy and envelope-plan fingerprints are deliberately NOT
     * inputs: an envelope-policy-only change must not invalidate an otherwise
     * compatible translation (invalidation matrix row 7). Rolling-context and
     * profile-subset fingerprints are recorded separately by callers, never
     * merged here.
     */
    fun translationProvenanceFingerprint(
        profileContentFingerprint: String,
        translatorProvider: String,
        translatorModel: String,
        translatorCredentialSignature: String?,
        translatorProtocolVersion: Int,
        promptVersion: Int,
        sourceLanguage: String,
        targetLanguage: String,
        contributingPages: List<TranslationProvenancePage>,
    ): String {
        val fields = mutableListOf<Any?>(
            "translation-provenance",
            profileContentFingerprint,
            translatorProvider,
            translatorModel,
            translatorCredentialSignature,
            translatorProtocolVersion,
            promptVersion,
            sourceLanguage,
            targetLanguage,
        )
        contributingPages.forEachIndexed { pageIndex, page ->
            fields += "contributing[$pageIndex]"
            fields += page.pageOcrContentFingerprint
            page.orderedStableBlockIds.forEachIndexed { blockIndex, blockId ->
                fields += "block[$blockIndex]"
                fields += blockId
            }
            page.orderedBlockSourceTextHashes.forEachIndexed { hashIndex, hash ->
                fields += "sent-hash[$hashIndex]"
                fields += hash
            }
        }
        return fingerprintIndexed(fields)
    }

    /**
     * 07: layout compatibility fingerprint. Carries every existing
     * [StageFingerprints.layout] input (same order, first seven parameters)
     * plus ALL of: font asset identity (asset name + asset sha256),
     * typeface/style, paint measurement flags, layout planner algorithm
     * version, `platformShapingKey`, stroke policy version, decode sample
     * size, and source-image page dimensions (`toRawBits`-encoded floats).
     *
     * Presentation transforms (SSIV zoom/pan/holder size/orientation) are
     * never inputs (final-target §3). Any single component change invalidates
     * the persisted layout (invalidation matrix row 9).
     */
    fun layoutCompatibilityFingerprint(
        translationArtifactId: String,
        cleanedImageArtifactIdOrOriginalSourceId: String,
        layoutEngineVersion: String,
        fontIdentity: String,
        fontScalePreferences: String,
        stylePreferences: String,
        outputDimensions: String,
        fontAssetName: String,
        fontAssetSha256: String,
        typefaceStyle: String,
        paintMeasurementFlags: String,
        layoutPlannerVersion: Int,
        platformShapingKey: String,
        strokePolicyVersion: Int,
        decodeSampleSize: Int,
        sourcePageWidth: Float,
        sourcePageHeight: Float,
    ): String = fingerprintIndexed(
        "layout-compatible",
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
        sourcePageWidth.toRawBits(),
        sourcePageHeight.toRawBits(),
    )

    /**
     * 08: color/style fingerprint. Inputs: color estimator version;
     * consumed image identity — cleaned file name + `inpaintRevision`, or
     * [DisplayBaseKind.ORIGINAL_SOURCE] marker + source sha256 when the
     * original pixels were consumed; per-block geometry fingerprints consumed
     * by estimation; page dimensions. Font, planner version and translated
     * text are NOT inputs (color depends on pixels + geometry only).
     */
    fun colorStyleFingerprint(
        colorEstimatorVersion: Int,
        cleanedImageFileName: String?,
        cleanedInpaintRevision: Int?,
        originalSourceSha256: String?,
        blockGeometryFingerprints: List<String>,
        pageWidth: Float,
        pageHeight: Float,
    ): String {
        val cleaned = cleanedImageFileName != null
        val fields = mutableListOf<Any?>(
            "color-style",
            colorEstimatorVersion,
            if (cleaned) DisplayBaseKind.CLEANED_IMAGE.name else DisplayBaseKind.ORIGINAL_SOURCE.name,
        )
        if (cleaned) {
            fields += cleanedImageFileName
            fields += cleanedInpaintRevision
        } else {
            fields += originalSourceSha256
        }
        blockGeometryFingerprints.forEachIndexed { index, fp ->
            fields += "geometry[$index]"
            fields += fp
        }
        fields += pageWidth.toRawBits()
        fields += pageHeight.toRawBits()
        return fingerprintIndexed(fields)
    }

    // -------------------------------------------------------------------------
    //  wave-2 review F2 consolidation: the envelope planners' composite
    // fingerprint builders (formerly the planner-local `PlannerFingerprints`
    // core in `translator/contextual/GlobalEnvelopePlanner.kt`) moved here so
    // there is exactly ONE canonical encoding for every persisted fingerprint.
    // The retired local core was byte-identical in discipline
    // (`len:value|` fields, `[$index]` list elements, `<null>` literal,
    // SHA-256 lowercase hex over UTF-8), so every value below is byte-for-byte
    // identical to the pre-consolidation values — pinned by the UNCHANGED
    // `t924/golden/envelope-plan-small.json` fixture and its
    // `5643a00c…9df8` plan-fingerprint literal.
    // -------------------------------------------------------------------------

    /**
     * Envelope policy composite (schemas contract §1.5): identity of the
     * measured-experiment [eu.kanade.translation.engines.translator.contextual.EnvelopePlannerPolicy]
     * constants only. A policy-only change changes this value (and the plan
     * input identity) while deliberately NOT invalidating compatible
     * translations (invalidation matrix row 7 — policy is never an input of
     * [translationProvenanceFingerprint]).
     */
    fun envelopePolicyFingerprint(
        maxBlocksPerEnvelope: Int,
        maxContributingPages: Int,
        maxEstimatedInputTokens: Int,
        maxEstimatedOutputTokens: Int,
        preferSceneBreaks: Boolean,
    ): String = fingerprintIndexed(
        listOf(
            "envelope-policy",
            maxBlocksPerEnvelope,
            maxContributingPages,
            maxEstimatedInputTokens,
            maxEstimatedOutputTokens,
            preferSceneBreaks,
        ),
    )

    /**
     * Per-envelope contributing corpus composite (schemas contract §1.5):
     * the contributing pages of ONE envelope, in planned order, each as
     * `pageKey to pageOcrContentFingerprint`. Payload order is hashed
     * verbatim — callers must pass the canonical planned order (same
     * core-then-context convention as  wave-2 F4).
     */
    fun envelopeContributingCorpusFingerprint(
        contributingPages: List<Pair<String, String>>,
    ): String {
        val fields = mutableListOf<Any?>("envelope-contributing")
        contributingPages.forEachIndexed { index, (pageKey, contentFingerprint) ->
            fields += "page[$index]"
            fields += pageKey
            fields += contentFingerprint
        }
        return fingerprintIndexed(fields)
    }

    /**
     * 08 `EnvelopePlan.planInputFingerprint`: corpus slice + planner
     * version + envelope policy + the pending-block set, in canonical page
     * then reading order. Callers pass only pages that carry pending blocks
     * (textless pages contribute nothing) — the composite hashes exactly the
     * planner's pending universe.
     */
    fun envelopePlanInputFingerprint(
        corpusFingerprint: String,
        plannerVersion: Int,
        policyFingerprint: String,
        pages: List<EnvelopePlanInputPage>,
    ): String {
        val fields = mutableListOf<Any?>(
            "envelope-plan-input",
            corpusFingerprint,
            plannerVersion,
            policyFingerprint,
        )
        pages.forEachIndexed { pageIndex, page ->
            fields += "page[$pageIndex]"
            fields += page.pageKey
            page.orderedStableBlockIds.forEachIndexed { blockIndex, blockId ->
                fields += "block[$blockIndex]"
                fields += blockId
            }
        }
        return fingerprintIndexed(fields)
    }

    /**
     * 10 `EnvelopePlan.planFingerprint`: SHA-256 over the canonical
     * re-encoded JSON of the plan DTO with operational fields zeroed and the
     * `planFingerprint` itself blanked (a value cannot contain its own hash).
     * Callers own the hashing view; this is the byte-level core only.
     */
    fun envelopePlanContentFingerprint(canonicalPlanJson: String): String =
        sha256Hex(canonicalPlanJson.toByteArray(Charsets.UTF_8))

    /** Convenience mapping of a live OCR snapshot onto [OcrBlockContent] order. */
    fun pageOcrContentBlocks(page: PageTranslation): List<OcrBlockContent> =
        page.blocks.map { block ->
            OcrBlockContent(
                stableBlockId = block.blockId,
                sourceText = block.text,
                x = block.x,
                y = block.y,
                width = block.width,
                height = block.height,
                angle = block.angle,
                label = block.label,
            )
        }

    /**
     * 09 text normalization applied before any hashing: Unicode NFC,
     * CRLF/CR normalized to LF. No case folding, no whitespace collapsing
     * (case and spacing are semantic in CJK/source text).
     */
    fun normalizeText(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    /**
     * 09 `sourceExcerptHash`: SHA-256 of the NFC/LF-normalized
     * excerpt, lowercase hex.
     */
    fun sourceExcerptHash(excerpt: String): String =
        sha256Hex(normalizeText(excerpt).toByteArray(Charsets.UTF_8))

    /**
     * Public canonical field hasher — the single  encoding core
     * behind every builder in this object (wave-2 F2 consolidation). Named
     * builders are preferred; this is the sanctioned core for planner-domain
     * composites whose inputs do not belong in the `artifact` package (e.g.
     * the golden fixtures' page-content markers).
     */
    fun canonicalFingerprint(fields: List<Any?>): String = fingerprintIndexed(fields)

    private fun fingerprint(vararg fields: Any?): String = fingerprintIndexed(fields.toList())

    /**
     * Canonical encoding where every field is its own length-prefixed unit and
     * list elements carry their index, so no composite input can collide with
     * a different element split.
     */
    private fun fingerprintIndexed(vararg fields: Any?): String = fingerprintIndexed(fields.toList())

    private fun fingerprintIndexed(fields: List<Any?>): String {
        val canonical = buildString {
            fields.forEach { value ->
                when (value) {
                    is List<*> -> value.forEachIndexed { index, element ->
                        appendField("[$index]")
                        appendField(element?.toString() ?: "<null>")
                    }
                    else -> appendField(value?.toString() ?: "<null>")
                }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    /** Schema version of the  OCR corpus content input set (FP-02/FP-03). */
    const val OCR_CORPUS_SCHEMA_VERSION = 1

    /** Explicit marker for a legitimately skipped detection stage (FP-02). */
    const val DETECTION_SKIPPED = "SKIPPED"

    /** Explicit absence value for authority fingerprints (FP-04); never "". */
    const val AUTHORITY_ABSENT = "ABSENT"

    /**
     * Raw SHA-256 over [bytes], lowercase hex — the byte-level core under
     * [profileContentFingerprint], [envelopePlanContentFingerprint] and
     * [sourceExcerptHash] (/SC-10).
     */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * One OCR block's content-facing fields consumed by
 * [StageFingerprints.pageOcrContentFingerprint]. Only the fields
 * listed there are carried; translation, colors, score, panel/bubble context
 * and masks are deliberately absent.
 */
data class OcrBlockContent(
    /** OCR canonical block id (e.g. `p3_b12`); null encodes as `<null>`. */
    val stableBlockId: String?,
    val sourceText: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val angle: Float,
    val label: Int,
)

/**
 * One contributing page's actually-translated content for
 * [StageFingerprints.translationProvenanceFingerprint]. The
 * hashes are over the NFC/LF-normalized source text actually sent (use
 * [StageFingerprints.sourceExcerptHash]); envelope ids and envelope policy
 * are deliberately absent ( matrix row 7).
 */
data class TranslationProvenancePage(
    val pageOcrContentFingerprint: String,
    val orderedStableBlockIds: List<String>,
    val orderedBlockSourceTextHashes: List<String>,
)

/**
 * One pending page's planning-facing input for
 * [StageFingerprints.envelopePlanInputFingerprint] ( wave-2 F2).
 * Only the block ids matter to the composite — text, geometry and content
 * fingerprints enter through [StageFingerprints.ocrCorpusFingerprint]
 * (`corpusFingerprint`) instead, so this carrier keeps the plan input a
 * function of the pending-block SET plus corpus identity.
 */
data class EnvelopePlanInputPage(
    val pageKey: String,
    /** Ordered (reading-order) stable block ids pending on this page. */
    val orderedStableBlockIds: List<String>,
)
