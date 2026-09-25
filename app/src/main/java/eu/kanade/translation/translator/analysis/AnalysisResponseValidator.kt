package eu.kanade.translation.translator.analysis

import eu.kanade.translation.artifact.EvidenceRef
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.translator.providers.OcrArtifactSanitizer
import kotlinx.serialization.json.Json

/**
 *  WP5 slice A ( DR-A): classifies one raw analysis
 * response against the request's evidence universe.
 *
 * Malformed-response taxonomy (design §9.2 + provider-analysis contract §6):
 *  - [AnalysisResponseOutcome.Refusal] = TERMINAL_REFUSAL: typed, no
 *    auto-retry, nothing retained.
 *  - [AnalysisResponseOutcome.Malformed] = AMBIGUOUS_PROTOCOL (any V1..V9
 *    violation, version error, unparseable/truncated body, id conflicts):
 *    the whole response is rejected — response-fatal, no partial retention
 *    (cross-referential records would build a silently incoherent graph).
 *  - [AnalysisResponseOutcome.Validated] with coverage = the MISSING_ONLY
 *    / COMPLETE family: every validated element is independently complete;
 *    a MISSING_ONLY subset commits per DR-A Option 1 and the remainder is
 *    marked pending — it never blocks the chapter.
 *
 * Chapter-only authority (provider-analysis contract §4): any response field
 * resembling user/series-level canon is DROPPED + reported
 * ([AnalysisResponseOutcome.Validated.droppedAuthorityKeys]), never
 * persisted, and never auto-promoted to series canon.
 */
object AnalysisResponseValidator {

    /** 06 field caps (v1 defaults). */
    private const val MAX_TEXT_FIELD_CHARS = 120
    private const val MAX_ALIAS_ITEMS = 8
    private const val MAX_ENTITIES = 48
    private const val MAX_TERMS = 96
    private const val MAX_SCENES = 32
    private const val MAX_QUESTIONS = 24
    private const val MAX_EQUIVALENCES = 24
    private const val MAX_EVIDENCE_PER_FACT = 8
    private const val MAX_SCENE_NARRATIVE_CHARS = 600
    private const val MAX_SUMMARY_CHARS = 2000
    private const val MAX_RELATIONSHIPS_PER_ENTITY = 12
    private const val MAX_TOTAL_EVIDENCE_REFS =
        eu.kanade.translation.artifact.AnalysisChunkResult.MAX_EVIDENCE_REFS

    /** Record id pattern, scoped per chunk. */
    private val RECORD_ID_REGEX = Regex("^[tesuc]\\d{3,4}$")

    /** 05 wire form is `e:` + 16 hex, nothing else (wave-4 F-W4-4). */
    private val EXCERPT_HASH_REGEX = Regex("^e:([0-9a-f]{16})$")

    private val GENDER_VALUES = setOf("MALE", "FEMALE", "UNKNOWN", "CONFLICTING")
    private val STRENGTH_VALUES = setOf("EXPLICIT", "STRONG_CONTEXTUAL", "WEAK")
    private val TERM_KINDS = setOf("NAME", "PLACE", "TERM", "TITLE", "ORG")
    private val APPLICABILITY_VALUES = setOf("CANONICAL_CHAPTER_WIDE", "RANGE_SCOPED")
    private val TONE_VALUES = setOf("COMEDIC", "SERIOUS", "ACTION", "ROMANCE", "HORROR", "SLICE_OF_LIFE")
    private val CONTENT_TAG_VALUES = setOf("EXPLICIT", "INTIMATE", "VIOLENT", "GORE")
    private val REGISTER_VALUES = setOf("CASUAL", "FORMAL", "ARCHAIC", "ROUGH", "POLITE", "OTHER")
    private val HYPOTHESIS_VALUES = setOf("SAME_ENTITY", "SAME_TERM", "NOT_EQUIVALENT", "UNCLEAR")
    private val CONFIDENCE_VALUES = setOf("LOW", "MEDIUM", "HIGH")

    /** Response keys that resemble durable user/series canon — always dropped. */
    private val AUTHORITY_KEY_REGEX = Regex("(?i)(series|user)[_-]?(authority|canon|glossary)")

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    /** Classification outcome of one raw response. */
    sealed interface AnalysisResponseOutcome {
        val droppedAuthorityKeys: List<String>

        data class Validated(
            val response: ValidatedAnalysisResponse,
            val coverage: AnalysisCoverage,
            override val droppedAuthorityKeys: List<String>,
        ) : AnalysisResponseOutcome

        /** AMBIGUOUS_PROTOCOL: the whole response is rejected, nothing retained. */
        data class Malformed(
            val violations: List<String>,
            override val droppedAuthorityKeys: List<String> = emptyList(),
        ) : AnalysisResponseOutcome

        /** TERMINAL_REFUSAL: typed, no auto-retry, nothing retained. */
        data class Refusal(
            val marker: String,
            override val droppedAuthorityKeys: List<String> = emptyList(),
        ) : AnalysisResponseOutcome
    }

    /** Fully validated, persistable response content (persistable subset). */
    data class ValidatedAnalysisResponse(
        val chunkId: String?,
        val terms: List<ValidatedTerm>,
        val entities: List<ValidatedEntity>,
        val scenes: List<ValidatedScene>,
        val narrativeSummary: String?,
        val conflictNotes: List<String>,
        /** Every validated evidence anchor, request resolution order. */
        val evidenceRefs: List<EvidenceRef>,
    ) {
        val hasExtractionContent: Boolean
            get() = terms.isNotEmpty() ||
                entities.isNotEmpty() ||
                scenes.isNotEmpty() ||
                !narrativeSummary.isNullOrBlank()
    }

    /**
     * Classifies [rawText] against [request]'s evidence universe.
     * [corePageKeys] / [contextPageKeys] split the V9 core-page duty check.
     */
    fun classify(
        rawText: String,
        request: AnalysisRequestBuilder.AnalysisChunkRequest,
        corePageKeys: Set<String>,
        contextPageKeys: Set<String>,
    ): AnalysisResponseOutcome {
        val stripped = OcrArtifactSanitizer.stripThinkingTags(rawText)
        if (TranslationResponseFaithfulness.isStructuralRefusal(stripped)) {
            return AnalysisResponseOutcome.Refusal(marker = "structural refusal marker in response body")
        }

        val violations = mutableListOf<String>()
        val droppedAuthority = mutableListOf<String>()
        val root = parseRootObject(stripped, violations)
            ?: return AnalysisResponseOutcome.Malformed(violations = violations)

        // V7: strict-on-version, never forward-interpreted.
        val schemaVersion = root.long("schemaVersion")
        if (schemaVersion == null || schemaVersion != AnalysisRequestBuilder.SCHEMA_VERSION.toLong()) {
            violations += "V7 schemaVersion must be ${AnalysisRequestBuilder.SCHEMA_VERSION}, " +
                "got $schemaVersion"
        }

        // Wire identity conflict: a different chunkId makes the response
        // protocol-suspect (ambiguous), a missing one only downgrades coverage.
        var chunkId: String? = null
        when (val raw = root.string("chunkId")) {
            null -> Unit // handled in coverage below
            else ->
                if (raw != request.chunkId) {
                    violations += "V1 chunkId conflict: expected ${request.chunkId}, got $raw"
                } else {
                    chunkId = raw
                }
        }

        // Chapter-only authority guard: resembling user/series canon is
        // dropped + reported, never persisted (no auto-promotion, §4.2).
        for (key in root.keys) {
            if (AUTHORITY_KEY_REGEX.containsMatchIn(key) && root[key] !is kotlinx.serialization.json.JsonNull) {
                droppedAuthority += key
            }
        }

        val evidence = EvidenceCollector()
        val terms = mutableListOf<ValidatedTerm>()
        val entities = mutableListOf<ValidatedEntity>()
        val conflictNotes = mutableListOf<String>()

        val knownIds = mutableSetOf<String>()
        val existingCanonIds = mutableSetOf<String>()

        // ---- terms ----
        val rawTerms = optionalArray(root, "terms", violations)
        if (rawTerms != null) {
            if (rawTerms.size > MAX_TERMS) violations += "V4 too many terms: ${rawTerms.size} > $MAX_TERMS"
            rawTerms.forEachIndexed { index, element ->
                val term = element as? kotlinx.serialization.json.JsonObject
                if (term == null) {
                    violations += "V6 terms[$index] is not an object"
                    return@forEachIndexed
                }
                val termId = requiredId(term, "termId", violations, "terms[$index]") ?: return@forEachIndexed
                if (!knownIds.add("t:$termId")) violations += "V2 duplicate termId $termId"
                val sourceForm = requiredText(term, "sourceForm", violations, "terms[$index]") ?: return@forEachIndexed
                val canonicalTarget = requiredText(term, "canonicalTarget", violations, "terms[$index]")
                    ?: return@forEachIndexed
                overlong(sourceForm, MAX_TEXT_FIELD_CHARS, violations, "terms[$index].sourceForm")
                overlong(canonicalTarget, MAX_TEXT_FIELD_CHARS, violations, "terms[$index].canonicalTarget")
                val aliases = textList(term, "aliases", violations, "terms[$index].aliases") ?: emptyList()
                if (aliases.size > MAX_ALIAS_ITEMS) {
                    violations += "V4 terms[$index] aliases exceed $MAX_ALIAS_ITEMS"
                }
                aliases.forEach { overlong(it, MAX_TEXT_FIELD_CHARS, violations, "terms[$index].alias item") }
                // Wave-4 F-W4-4: `kind` is REQUIRED (V3) — previously only an
                // invalid value was fatal; a missing one silently defaulted.
                if (term.string("kind") == null) {
                    violations += "V3 terms[$index].kind required"
                }
                val kind = enumField(term, "kind", TERM_KINDS, violations, "terms[$index]") ?: "TERM"
                applicability(term, violations, "terms[$index]")
                validateEvidenceArray(term, request, corePageKeys, contextPageKeys, evidence, violations, "terms[$index]")
                validateCoreDuty(term, request, corePageKeys, violations, "terms[$index]")
                terms += ValidatedTerm(termId, sourceForm, canonicalTarget, aliases, kind)
            }
        }

        // ---- entities ----
        val rawEntities = optionalArray(root, "entities", violations)
        if (rawEntities != null) {
            if (rawEntities.size > MAX_ENTITIES) {
                violations += "V4 too many entities: ${rawEntities.size} > $MAX_ENTITIES"
            }
            rawEntities.forEachIndexed { index, element ->
                val entity = element as? kotlinx.serialization.json.JsonObject
                if (entity == null) {
                    violations += "V6 entities[$index] is not an object"
                    return@forEachIndexed
                }
                val entityId = requiredId(entity, "entityId", violations, "entities[$index]") ?: return@forEachIndexed
                if (!knownIds.add("e:$entityId")) violations += "V2 duplicate entityId $entityId"
                val sourceNames = textList(entity, "sourceNames", violations, "entities[$index].sourceNames")
                if (sourceNames.isNullOrEmpty()) {
                    violations += "V3 entities[$index].sourceNames must be a non-empty list"
                }
                val canonicalSourceName = requiredText(entity, "canonicalSourceName", violations, "entities[$index]")
                    ?: return@forEachIndexed
                val proposedTargetName = requiredText(entity, "proposedTargetName", violations, "entities[$index]")
                    ?: return@forEachIndexed
                overlong(canonicalSourceName, MAX_TEXT_FIELD_CHARS, violations, "entities[$index].canonicalSourceName")
                overlong(proposedTargetName, MAX_TEXT_FIELD_CHARS, violations, "entities[$index].proposedTargetName")
                val titles = textList(entity, "titles", violations, "entities[$index].titles") ?: emptyList()
                if (titles.size > MAX_ALIAS_ITEMS) violations += "V4 entities[$index] titles exceed $MAX_ALIAS_ITEMS"
                // Wave-5 F-W5-1 (source side): per-item length caps — term
                // aliases already had one; entity-side lists did not, letting
                // an overlong string persist VALID into the chunk.
                sourceNames.orEmpty().forEach {
                    overlong(it, MAX_TEXT_FIELD_CHARS, violations, "entities[$index].sourceNames item")
                }
                titles.forEach {
                    overlong(it, MAX_TEXT_FIELD_CHARS, violations, "entities[$index].titles item")
                }

                // gender fact: evidence REQUIRED (V3), closed enum (V5).
                (entity["gender"] as? kotlinx.serialization.json.JsonObject)?.let { gender ->
                    val value = gender.string("value")
                    if (value == null || value !in GENDER_VALUES) {
                        violations += "V5 entities[$index].gender.value invalid: $value"
                    }
                    strengthOf(gender, violations, "entities[$index].gender")
                    val count = validateEvidenceArray(
                        gender,
                        request,
                        corePageKeys,
                        contextPageKeys,
                        evidence,
                        violations,
                        "entities[$index].gender",
                    )
                    if (count == 0) violations += "V3 entities[$index].gender requires evidence"
                    validateCoreDuty(
                        gender,
                        request,
                        corePageKeys,
                        violations,
                        "entities[$index].gender",
                    )
                }
                (entity["pronounFacts"] as? kotlinx.serialization.json.JsonArray)?.forEachIndexed { pIndex, fact ->
                    val pronoun = fact as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                    if (pronoun.string("value").isNullOrBlank()) {
                        violations += "V3 entities[$index].pronounFacts[$pIndex].value required"
                    }
                    strengthOf(pronoun, violations, "entities[$index].pronounFacts[$pIndex]")
                    val count = validateEvidenceArray(
                        pronoun,
                        request,
                        corePageKeys,
                        contextPageKeys,
                        evidence,
                        violations,
                        "entities[$index].pronounFacts[$pIndex]",
                    )
                    if (count == 0) violations += "V3 entities[$index].pronounFacts[$pIndex] requires evidence"
                }
                (entity["conflicts"] as? kotlinx.serialization.json.JsonArray)?.forEachIndexed { cIndex, conflict ->
                    val note = conflict as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                    if (note.string("topic").isNullOrBlank()) {
                        violations += "V3 entities[$index].conflicts[$cIndex].topic required"
                    }
                    val noteText = note.string("note") ?: ""
                    overlong(noteText, 500, violations, "entities[$index].conflicts[$cIndex].note")
                    strengthOf(conflict, violations, "entities[$index].conflicts[$cIndex]")
                    validateEvidenceArray(
                        conflict,
                        request,
                        corePageKeys,
                        contextPageKeys,
                        evidence,
                        violations,
                        "entities[$index].conflicts[$cIndex]",
                    )
                    if (conflictNotes.size < MAX_TOTAL_EVIDENCE_REFS) {
                        conflictNotes += "topic=${note.string("topic") ?: "?"}: $noteText"
                    }
                }

                val relationships = mutableListOf<ValidatedRelationship>()
                (entity["relationships"] as? kotlinx.serialization.json.JsonArray)?.let { rawRelationships ->
                    if (rawRelationships.size > MAX_RELATIONSHIPS_PER_ENTITY) {
                        violations += "V4 entities[$index] relationships exceed $MAX_RELATIONSHIPS_PER_ENTITY"
                    }
                    rawRelationships.forEachIndexed { rIndex, relElement ->
                        val rel = relElement as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                        val type = rel.string("type")
                        if (type.isNullOrBlank()) {
                            violations += "V3 entities[$index].relationships[$rIndex].type required"
                        }
                        val target = rel.string("targetEntityId")
                        // Wave-4 F-W4-5: resolution is existing canon ids +
                        // pending entity ids of THIS response (forward
                        // references allowed) — never the type-prefixed
                        // knownIds set, whose keys can never match the bare
                        // wire ids used here.
                        if (target == null || (target !in existingCanonIds && !pendingEntityIdWillResolve(target, rawEntities))) {
                            violations += "V1 entities[$index].relationships[$rIndex].targetEntityId " +
                                "does not resolve: $target"
                        }
                        relationships += ValidatedRelationship(type ?: "", entityId, target ?: "")
                    }
                }
                applicability(entity, violations, "entities[$index]")
                validateEvidenceArray(entity, request, corePageKeys, contextPageKeys, evidence, violations, "entities[$index]")
                validateCoreDuty(entity, request, corePageKeys, violations, "entities[$index]")
                entities += ValidatedEntity(
                    entityId = entityId,
                    sourceNames = sourceNames.orEmpty(),
                    canonicalSourceName = canonicalSourceName,
                    proposedTargetName = proposedTargetName,
                    titles = titles,
                    relationships = relationships,
                )
            }
        }

        // ---- scenes ----
        val scenes = mutableListOf<ValidatedScene>()
        val rawScenes = optionalArray(root, "scenes", violations)
        if (rawScenes != null) {
            if (rawScenes.size > MAX_SCENES) violations += "V4 too many scenes: ${rawScenes.size} > $MAX_SCENES"
            rawScenes.forEachIndexed { index, element ->
                val scene = element as? kotlinx.serialization.json.JsonObject
                if (scene == null) {
                    violations += "V6 scenes[$index] is not an object"
                    return@forEachIndexed
                }
                val sceneId = requiredId(scene, "sceneId", violations, "scenes[$index]") ?: return@forEachIndexed
                if (!knownIds.add("s:$sceneId")) violations += "V2 duplicate sceneId $sceneId"
                val range = scene["range"] as? kotlinx.serialization.json.JsonObject
                if (range == null) {
                    violations += "V3 scenes[$index].range required"
                    return@forEachIndexed
                }
                val fromPage = range.string("fromPage")
                val fromBlock = range.string("fromBlock")
                val toPage = range.string("toPage")
                val toBlock = range.string("toBlock")
                validateRangeRef(fromPage, fromBlock, request, violations, "scenes[$index].range.from")
                validateRangeRef(toPage, toBlock, request, violations, "scenes[$index].range.to")

                val participants = textList(scene, "participants", violations, "scenes[$index].participants") ?: emptyList()
                participants.forEach { participant ->
                    if (!knownIds.contains("e:$participant")) {
                        violations += "V1 scenes[$index].participants does not resolve: $participant"
                    }
                }
                val tone = textList(scene, "tone", violations, "scenes[$index].tone") ?: emptyList()
                tone.forEach { value ->
                    if (value !in TONE_VALUES) violations += "V5 scenes[$index].tone invalid: $value"
                }
                val contentTags = textList(scene, "contentTags", violations, "scenes[$index].contentTags") ?: emptyList()
                contentTags.forEach { value ->
                    if (value !in CONTENT_TAG_VALUES) violations += "V5 scenes[$index].contentTags invalid: $value"
                }
                val register = scene.string("register")
                if (register == null || register !in REGISTER_VALUES) {
                    violations += "V5 scenes[$index].register invalid: $register"
                }
                val narrative = scene.string("narrative")
                overlong(narrative, MAX_SCENE_NARRATIVE_CHARS, violations, "scenes[$index].narrative")
                scenes += ValidatedScene(
                    sceneId = sceneId,
                    fromPageWireKey = fromPage ?: "",
                    fromBlockId = fromBlock ?: "",
                    toPageWireKey = toPage ?: "",
                    toBlockId = toBlock ?: "",
                    participants = participants,
                    tone = tone,
                    contentTags = contentTags,
                    register = register ?: "",
                    narrative = narrative,
                )
            }
        }

        // ---- narrative ----
        var narrativeSummary: String? = null
        (root["narrative"] as? kotlinx.serialization.json.JsonObject)?.let { narrative ->
            val summary = narrative.string("summary")
            overlong(summary, MAX_SUMMARY_CHARS, violations, "narrative.summary")
            narrativeSummary = summary?.takeIf { it.isNotBlank() }
            (narrative["revelations"] as? kotlinx.serialization.json.JsonArray)?.forEachIndexed { index, revelation ->
                val rev = revelation as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
                if (rev.string("text").isNullOrBlank()) {
                    violations += "V3 narrative.revelations[$index].text required"
                }
            }
        }

        // ---- unresolvedQuestions + candidateEquivalences ----
        val rawQuestions = optionalArray(root, "unresolvedQuestions", violations)
        if (rawQuestions != null && rawQuestions.size > MAX_QUESTIONS) {
            violations += "V4 too many unresolvedQuestions: ${rawQuestions.size} > $MAX_QUESTIONS"
        }
        rawQuestions?.forEachIndexed { index, element ->
            val question = element as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
            requiredId(question, "id", violations, "unresolvedQuestions[$index]") ?: return@forEachIndexed
            if (question.string("question").isNullOrBlank()) {
                violations += "V3 unresolvedQuestions[$index].question required"
            }
            validateEvidenceArray(
                question,
                request,
                corePageKeys,
                contextPageKeys,
                evidence,
                violations,
                "unresolvedQuestions[$index]",
            )
        }
        val rawEquivalences = optionalArray(root, "candidateEquivalences", violations)
        if (rawEquivalences != null && rawEquivalences.size > MAX_EQUIVALENCES) {
            violations += "V4 too many candidateEquivalences: ${rawEquivalences.size} > $MAX_EQUIVALENCES"
        }
        rawEquivalences?.forEachIndexed { index, element ->
            val equivalence = element as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
            requiredId(equivalence, "id", violations, "candidateEquivalences[$index]") ?: return@forEachIndexed
            val sourceForms = textList(
                equivalence,
                "sourceForms",
                violations,
                "candidateEquivalences[$index].sourceForms",
            )
            if (sourceForms.isNullOrEmpty()) {
                violations += "V3 candidateEquivalences[$index].sourceForms must be non-empty"
            }
            val hypothesis = equivalence.string("hypothesis")
            if (hypothesis == null || hypothesis !in HYPOTHESIS_VALUES) {
                violations += "V5 candidateEquivalences[$index].hypothesis invalid: $hypothesis"
            }
            val confidence = equivalence.string("confidence")
            if (confidence == null || confidence !in CONFIDENCE_VALUES) {
                violations += "V5 candidateEquivalences[$index].confidence invalid: $confidence"
            }
            validateEvidenceArray(
                equivalence,
                request,
                corePageKeys,
                contextPageKeys,
                evidence,
                violations,
                "candidateEquivalences[$index]",
            )
        }

        if (evidence.refs.size > MAX_TOTAL_EVIDENCE_REFS) {
            violations += "V4 too many evidence refs in total: ${evidence.refs.size} > $MAX_TOTAL_EVIDENCE_REFS"
        }
        if (violations.isNotEmpty()) {
            return AnalysisResponseOutcome.Malformed(
                violations = violations,
                droppedAuthorityKeys = droppedAuthority,
            )
        }

        // ---- coverage (DR-A MISSING_ONLY family) ----
        val response = ValidatedAnalysisResponse(
            chunkId = chunkId,
            terms = terms,
            entities = entities,
            scenes = scenes,
            narrativeSummary = narrativeSummary,
            conflictNotes = conflictNotes.distinct(),
            evidenceRefs = evidence.refs,
        )
        val coverage = if (response.hasExtractionContent) {
            AnalysisCoverage(kind = AnalysisCoverageKind.COMPLETE, reasons = emptyList())
        } else {
            AnalysisCoverage(
                kind = AnalysisCoverageKind.MISSING_ONLY,
                reasons = buildList {
                    if (chunkId == null) add("response carried no chunkId")
                    add("response parsed clean but carried no extraction records for the chunk's core pages")
                },
            )
        }
        return AnalysisResponseOutcome.Validated(
            response = response,
            coverage = coverage,
            droppedAuthorityKeys = droppedAuthority,
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Collects validated evidence anchors in request-resolution order. */
    private class EvidenceCollector {
        val refs = mutableListOf<EvidenceRef>()

        fun add(pageKey: String, blockId: String, request: AnalysisRequestBuilder.AnalysisChunkRequest) {
            val text = request.textByBlockId[blockId] ?: return
            val full = StageFingerprints.sourceExcerptHash(text)
            refs += EvidenceRef(pageKey = pageKey, stableBlockId = blockId, sourceExcerptHash = full)
        }
    }

    private fun parseRootObject(
        stripped: String,
        violations: MutableList<String>,
    ): kotlinx.serialization.json.JsonObject? {
        val body = stripCodeFence(stripped).trim()
        val start = body.indexOf('{')
        if (start < 0) {
            violations += "V6 response carries no JSON object"
            return null
        }
        val candidate = body.substring(start)
        val parsed = runCatching { lenientJson.parseToJsonElement(candidate) }.getOrNull()
        val root = parsed as? kotlinx.serialization.json.JsonObject
        if (root == null) {
            violations += "V6 response is not a JSON object (truncated or non-JSON body)"
        }
        return root
    }

    /** Strips ```json / ``` fences the strict translation parser also tolerates. */
    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpening = trimmed.removePrefix("```").removePrefix("json").removePrefix("JSON")
        val closing = withoutOpening.lastIndexOf("```")
        return if (closing >= 0) withoutOpening.substring(0, closing) else withoutOpening
    }

    private fun kotlinx.serialization.json.JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun optionalArray(
        root: kotlinx.serialization.json.JsonObject,
        key: String,
        violations: MutableList<String>,
    ): kotlinx.serialization.json.JsonArray? {
        val element = root[key] ?: return null
        return element as? kotlinx.serialization.json.JsonArray ?: run {
            violations += "V6 $key must be an array"
            null
        }
    }

    private fun requiredId(
        obj: kotlinx.serialization.json.JsonObject,
        key: String,
        violations: MutableList<String>,
        path: String,
    ): String? {
        val value = obj.string(key)
        if (value == null || !RECORD_ID_REGEX.matches(value)) {
            violations += "V1/V2 $path.$key must match ${RECORD_ID_REGEX.pattern}, got $value"
            return null
        }
        return value
    }

    private fun requiredText(
        obj: kotlinx.serialization.json.JsonObject,
        key: String,
        violations: MutableList<String>,
        path: String,
    ): String? {
        val value = obj.string(key)
        if (value.isNullOrBlank()) {
            violations += "V3 $path.$key is required"
            return null
        }
        return value
    }

    private fun textList(
        obj: kotlinx.serialization.json.JsonObject,
        key: String,
        violations: MutableList<String>,
        path: String,
    ): List<String>? {
        val element = obj[key] ?: return null
        val array = element as? kotlinx.serialization.json.JsonArray ?: run {
            violations += "V6 $path must be an array"
            return null
        }
        return array.mapNotNull { item ->
            (item as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
    }

    private fun overlong(
        value: String?,
        cap: Int,
        violations: MutableList<String>,
        path: String,
    ) {
        if ((value?.length ?: 0) > cap) {
            violations += "V4 $path exceeds $cap chars"
        }
    }

    private fun enumField(
        obj: kotlinx.serialization.json.JsonObject,
        key: String,
        allowed: Set<String>,
        violations: MutableList<String>,
        path: String,
    ): String? {
        val value = obj.string(key) ?: return null
        if (value !in allowed) {
            violations += "V5 $path.$key invalid enum: $value"
            return null
        }
        return value
    }

    private fun strengthOf(
        obj: kotlinx.serialization.json.JsonObject,
        violations: MutableList<String>,
        path: String,
    ) {
        val value = obj.string("strength")
        if (value == null || value !in STRENGTH_VALUES) {
            violations += "V5 $path.strength invalid: $value"
        }
    }

    private fun applicability(
        obj: kotlinx.serialization.json.JsonObject,
        violations: MutableList<String>,
        path: String,
    ) {
        val value = obj.string("applicability") ?: return
        if (value !in APPLICABILITY_VALUES) {
            violations += "V5 $path.applicability invalid: $value"
        }
    }

    /**
     * V1 (syntax) + V8 (hash recompute): every evidence element must name a
     * contributing page, a block of that page prefixed by the page key, carry
     * a required strength, and its excerpt hash must equal the first 16 hex
     * chars of the locally recomputed source-text hash. Returns the number of
     * evidence elements validated.
     */
    private fun validateEvidenceArray(
        obj: kotlinx.serialization.json.JsonObject,
        request: AnalysisRequestBuilder.AnalysisChunkRequest,
        corePageKeys: Set<String>,
        @Suppress("UNUSED_PARAMETER") contextPageKeys: Set<String>,
        collector: EvidenceCollector,
        violations: MutableList<String>,
        path: String,
    ): Int {
        val element = obj["evidence"] ?: return 0
        val array = element as? kotlinx.serialization.json.JsonArray ?: run {
            violations += "V6 $path.evidence must be an array"
            return 0
        }
        if (array.isEmpty()) return 0
        if (array.size > MAX_EVIDENCE_PER_FACT) {
            violations += "V4 $path.evidence exceeds $MAX_EVIDENCE_PER_FACT refs"
        }
        var valid = 0
        array.forEachIndexed { index, item ->
            val entry = item as? kotlinx.serialization.json.JsonObject ?: run {
                violations += "V6 $path.evidence[$index] is not an object"
                return@forEachIndexed
            }
            val pageKey = entry.string("pageKey")
            val blockId = entry.string("blockId")
            val strength = entry.string("strength")
            if (strength == null || strength !in STRENGTH_VALUES) {
                violations += "V5 $path.evidence[$index].strength invalid: $strength"
            }
            if (pageKey == null || pageKey !in request.blockIdsByPage) {
                violations += "V1 $path.evidence[$index].pageKey does not resolve into the request: $pageKey"
                return@forEachIndexed
            }
            if (blockId == null || !blockId.startsWith(pageKey) || blockId !in (request.blockIdsByPage[pageKey] ?: emptyList())) {
                violations += "V1 $path.evidence[$index].blockId does not resolve under $pageKey: $blockId"
                return@forEachIndexed
            }
            val rawHash = entry.string("excerptHash")
            val hashMatch = rawHash?.let { EXCERPT_HASH_REGEX.matchEntire(it) }
            val claimed = hashMatch?.groupValues?.getOrNull(1)
            if (claimed == null) {
                violations += "V8 $path.evidence[$index].excerptHash malformed: $rawHash"
                return@forEachIndexed
            }
            val recomputed = StageFingerprints.sourceExcerptHash(request.textByBlockId.getValue(blockId))
            if (!recomputed.startsWith(claimed)) {
                violations += "V8 $path.evidence[$index].excerptHash mismatch on $blockId " +
                    "(invented or paraphrased anchor)"
                return@forEachIndexed
            }
            collector.add(pageKey, blockId, request)
            valid++
        }
        return valid
    }

    /**
     * V9 core-page duty: a record carrying evidence must anchor at least one
     * reference on a CORE page of the chunk (context pages are citation-only).
     */
    private fun validateCoreDuty(
        obj: kotlinx.serialization.json.JsonObject,
        request: AnalysisRequestBuilder.AnalysisChunkRequest,
        corePageKeys: Set<String>,
        violations: MutableList<String>,
        path: String,
    ) {
        val element = obj["evidence"] ?: return
        val array = element as? kotlinx.serialization.json.JsonArray ?: return
        if (array.isEmpty()) return
        val anchorsOnCore = array.any { item ->
            val pageKey = (item as? kotlinx.serialization.json.JsonObject)?.string("pageKey")
            pageKey != null && pageKey in corePageKeys && request.blockIdsByPage.containsKey(pageKey)
        }
        if (!anchorsOnCore) {
            violations += "V9 $path is anchored only on CONTEXT pages (core-page duty violation)"
        }
    }

    private fun validateRangeRef(
        pageKey: String?,
        blockId: String?,
        request: AnalysisRequestBuilder.AnalysisChunkRequest,
        violations: MutableList<String>,
        path: String,
    ) {
        if (pageKey == null || pageKey !in request.blockIdsByPage) {
            violations += "V1 $path.pageKey does not resolve into the request: $pageKey"
            return
        }
        if (blockId == null || !blockId.startsWith(pageKey) || blockId !in (request.blockIdsByPage[pageKey] ?: emptyList())) {
            violations += "V1 $path.blockId does not resolve under $pageKey: $blockId"
        }
    }

    /**
     * Forward references inside one response: a relationship may cite an
     * entity emitted LATER in the same array. The two-pass resolution here
     * accepts the forward edge only when the id is claimed by some entity in
     * this response.
     */
    private fun pendingEntityIdWillResolve(
        target: String,
        rawEntities: kotlinx.serialization.json.JsonArray,
    ): Boolean = rawEntities.any { element ->
        (element as? kotlinx.serialization.json.JsonObject)?.string("entityId") == target
    }
}
