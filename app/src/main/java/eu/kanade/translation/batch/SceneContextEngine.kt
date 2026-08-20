package eu.kanade.translation.batch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * TachiyomiAT: deterministic, source-grounded rolling scene context for chapter
 * batch AI translation (Phase 6 context-quality contract).
 *
 * Everything in this file is computed by code from source evidence. A model
 * translation can never prove a fact: translations are never inputs here, a
 * model-proposed delta is only accepted through [SceneContextEngine], and the
 * relationship ambiguity prior is a prompt-level tie-break that is never
 * stored as evidence.
 */

enum class SceneGender { UNKNOWN, FEMALE, MALE, OTHER }

enum class SceneConfidence { UNKNOWN, TENTATIVE, PROBABLE, CONFIRMED }

enum class SceneEvidenceWeight { STRONG, MEDIUM, WEAK }

@Serializable
data class SceneCitation(
    val blockId: String,
    val ocrConfidence: Float = 1f,
)

@Serializable
data class SceneEvidence(
    /** Fact family: "gender", or "relationship". Links are tracked separately. */
    val kind: String,
    val value: String,
    val weight: SceneEvidenceWeight,
    val citations: List<SceneCitation>,
    val contradicted: Boolean = false,
)

@Serializable
data class SceneProfile(
    val profileId: String,
    /** Named once an anonymous-to-named link is corroborated; null while temporary. */
    val displayName: String? = null,
    /** Temporary label while unnamed: "Speaker A", "Narrator", ... */
    val temporaryLabel: String,
    val gender: SceneGender = SceneGender.UNKNOWN,
    val genderConfidence: SceneConfidence = SceneConfidence.UNKNOWN,
    val evidence: List<SceneEvidence> = emptyList(),
    /** Pending name -> distinct citing page count; -1 marks a vocative-suspicion block. */
    val linkCandidates: Map<String, Int> = emptyMap(),
    /** Monotonic turn ordinal of the last turn this profile spoke or was addressed. */
    val lastActiveTurn: Int = 0,
) {
    val label: String get() = displayName ?: temporaryLabel
}

@Serializable
data class SceneTurn(
    val blockId: String,
    val pageId: String,
    /** Resolved speaker label, or "" when the turn's speaker is unknown. */
    val speakerLabel: String,
    val sourceText: String,
)

@Serializable
data class SceneCardState(
    val protocolVersion: Int = SCENE_PROTOCOL_VERSION,
    /** Hash of the checkpoint this state was built from; "" for genesis. */
    val inputCheckpointHash: String = "",
    val profiles: List<SceneProfile> = emptyList(),
    val recentTurns: List<SceneTurn> = emptyList(),
    /** Deterministically composed summary; model summaries are never trusted. */
    val summary: String = "",
    val unresolved: List<String> = emptyList(),
    /** Durable fact key -> committed page indexes whose translation used it. */
    val factPages: Map<String, List<Int>> = emptyMap(),
    /** Monotonic turn counter; drives profile activity bound and LRU. */
    val turnCounter: Int = 0,
    /** Hash of this checkpoint's canonical content (chained to [inputCheckpointHash]). */
    val checkpointHash: String = "",
) {
    companion object {
        const val SCENE_PROTOCOL_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun genesis(): SceneCardState {
            val state = SceneCardState()
            return state.copy(checkpointHash = SceneContextEngine.hashOf(state))
        }

        fun decode(serialized: String): SceneCardState? = runCatching { json.decodeFromString(serializer(), serialized) }
            .getOrNull()
            ?.takeIf { it.protocolVersion == SCENE_PROTOCOL_VERSION }

        fun encode(state: SceneCardState): String = json.encodeToString(serializer(), state)
    }
}

/** A durable fact changed; dependent translations must rerun (translation/layout only). */
data class SceneCorrection(
    val factKey: String,
    val invalidatedPages: List<Int>,
)

/** Result of committing one page into the scene context. */
data class SceneCommit(
    val state: SceneCardState,
    val correction: SceneCorrection? = null,
)

object SceneContextEngine {

    const val MAX_PROFILES = 6
    const val MAX_TURNS = 12
    const val MAX_SUMMARY_WORDS = 80
    const val MAX_UNRESOLVED = 8
    const val OCR_CONFIDENCE_FLOOR = 0.6f
    const val LINK_PROMOTION_PAGES = 2

    /** Setting mirror of the domain batch preference. */
    enum class PriorSetting { NEUTRAL, MALE_FEMALE }

    /**
     * Commits one validated page into the scene context. [blocks] are the
     * page's source blocks in reading order (blockId + text); [delta] is the
     * deterministic parse of the model's page-scoped CONTEXT_DELTA, or null
     * when the provider supplied none. Callers only invoke this for pages
     * inside the committed prefix — context never crosses an invalid page.
     */
    fun commitPage(
        state: SceneCardState,
        pageIndex: Int,
        pageId: String,
        blocks: List<Pair<String, String>>,
        delta: SceneContextDelta?,
    ): SceneCommit {
        var profiles = state.profiles.map { it.copy() }.toMutableList()
        val unresolved = state.unresolved.toMutableList()
        var turnCounter = state.turnCounter
        val newTurns = mutableListOf<SceneTurn>()
        val roles = delta?.roles ?: emptyMap()
        val ocrConfidences = delta?.citedConfidences ?: emptyMap()

        // Seed profiles for every label the delta references (ROLE speakers and
        // addressees, LINK temporary labels) so turn matching and link
        // promotion see them on this page already.
        buildSet {
            roles.values.forEach { role ->
                if (role.speaker.isNotBlank()) add(role.speaker)
                if (role.addressee.isNotBlank()) add(role.addressee)
            }
            delta?.links?.forEach { link -> add(link.temporaryLabel) }
        }.forEach { label ->
            if (profiles.none { it.label == label }) {
                profiles += SceneProfile(
                    profileId = "sp${nextProfileId(profiles)}",
                    temporaryLabel = label,
                )
            }
        }

        blocks.forEach { (blockId, text) ->
            if (text.isBlank()) return@forEach
            turnCounter++
            val requestedSpeaker = roles[blockId]?.speaker.orEmpty()
            val matched = requestedSpeaker.takeIf { label -> label.isNotBlank() && profiles.any { it.label == label } }
            if (matched != null) {
                profiles = profiles.map {
                    if (it.label == matched) it.copy(lastActiveTurn = turnCounter) else it
                }.toMutableList()
            }
            newTurns += SceneTurn(
                blockId = blockId,
                pageId = pageId,
                speakerLabel = matched.orEmpty(),
                sourceText = text,
            )
        }

        var correction: SceneCorrection? = null

        delta?.links.orEmpty().forEach { link ->
            profiles = applyLink(profiles, link, unresolved).toMutableList()
        }

        delta?.facts.orEmpty().forEach { fact ->
            val outcome = applyFact(
                profiles = profiles,
                fact = fact,
                unresolved = unresolved,
                ocrConfidences = ocrConfidences,
            )
            profiles = outcome.profiles.toMutableList()
            if (outcome.correctionKey != null && correction == null) {
                val key = outcome.correctionKey!!
                correction = SceneCorrection(
                    factKey = key,
                    invalidatedPages = state.factPages[key].orEmpty().filter { it < pageIndex },
                )
            }
        }

        delta?.unresolved.orEmpty().forEach { item ->
            val trimmed = item.trim().take(160)
            if (trimmed.isNotBlank() && trimmed !in unresolved && unresolved.size < MAX_UNRESOLVED) {
                unresolved += trimmed
            }
        }

        // Speaker labels that appeared in turns but match no profile become
        // fresh temporary profiles so the card can reference them.
        newTurns.map { it.speakerLabel }.filter { it.isNotBlank() }.distinct().forEach { label ->
            if (profiles.none { it.label == label }) {
                profiles += SceneProfile(
                    profileId = "sp${nextProfileId(profiles)}",
                    temporaryLabel = label,
                    lastActiveTurn = turnCounter,
                )
            }
        }

        // Keep the most active 4–6 profiles; linked (named) profiles win ties.
        val boundedProfiles = profiles
            .sortedWith(
                compareByDescending<SceneProfile> { it.displayName != null }
                    .thenByDescending { it.lastActiveTurn },
            )
            .take(MAX_PROFILES)
            .sortedBy { it.profileId }

        val turns = (state.recentTurns + newTurns).takeLast(MAX_TURNS)
        val summary = composeSummary(pageIndex, boundedProfiles, unresolved)
        val factPages = accumulateFactPages(state.factPages, boundedProfiles, pageIndex)

        val next = SceneCardState(
            protocolVersion = state.protocolVersion,
            inputCheckpointHash = state.checkpointHash,
            profiles = boundedProfiles,
            recentTurns = turns,
            summary = summary,
            unresolved = unresolved.takeLast(MAX_UNRESOLVED),
            factPages = factPages,
            turnCounter = turnCounter,
        )
        return SceneCommit(
            state = next.copy(checkpointHash = hashOf(next)),
            correction = correction,
        )
    }

    /**
     * Deterministic weight table (contract §5). Weak evidence (name or speech
     * style alone) never passes TENTATIVE; a single medium signal stays
     * TENTATIVE; strong evidence alone reaches PROBABLE; two independent
     * strong/medium signals reach CONFIRMED. When no citation clears the OCR
     * floor, the fact caps at TENTATIVE.
     */
    fun confidenceFor(
        weight: SceneEvidenceWeight,
        independentSignals: Int,
        ocrFloorCleared: Boolean,
    ): SceneConfidence {
        if (!ocrFloorCleared) return SceneConfidence.TENTATIVE
        return when (weight) {
            SceneEvidenceWeight.WEAK -> SceneConfidence.TENTATIVE
            SceneEvidenceWeight.MEDIUM ->
                if (independentSignals >= 2) SceneConfidence.PROBABLE else SceneConfidence.TENTATIVE
            SceneEvidenceWeight.STRONG ->
                if (independentSignals >= 2) SceneConfidence.CONFIRMED else SceneConfidence.PROBABLE
        }
    }

    private data class FactOutcome(
        val profiles: List<SceneProfile>,
        val correctionKey: String? = null,
    )

    private fun nextProfileId(profiles: List<SceneProfile>): Int =
        (profiles.maxOfOrNull { it.profileId.removePrefix("sp").toIntOrNull() ?: 0 } ?: 0) + 1

    private fun profileFor(
        profiles: MutableList<SceneProfile>,
        who: String,
    ): IndexedValue<SceneProfile> {
        profiles.withIndex().firstOrNull { it.value.label == who }?.let { return it }
        val named = !who.startsWith("Speaker") && who != "Narrator" && who != "Unknown Third Party"
        profiles += SceneProfile(
            profileId = "sp${nextProfileId(profiles)}",
            temporaryLabel = if (named) "Speaker ${nextProfileId(profiles)}" else who,
            displayName = who.takeIf { named },
        )
        return profiles.withIndex().last { it.value.label == who || it.value.displayName == who }
    }

    private fun applyFact(
        profiles: MutableList<SceneProfile>,
        fact: SceneFact,
        unresolved: MutableList<String>,
        ocrConfidences: Map<String, Float>,
    ): FactOutcome {
        val target = profileFor(profiles, fact.who)
        val profile = target.value
        val citations = fact.citations.map { SceneCitation(it, ocrConfidences[it] ?: 1f) }
        val ocrFloorCleared = citations.any { it.ocrConfidence >= OCR_CONFIDENCE_FLOOR }
        val priorSignals = profile.evidence.count {
            !it.contradicted && it.kind == fact.kind && it.value == fact.value
        }
        val independentSignals = maxOf(priorSignals + 1, citations.distinctBy { it.blockId }.size)
        var correctionKey: String? = null

        val updated: SceneProfile = when (fact.kind) {
            "gender" -> {
                val newGender = runCatching { SceneGender.valueOf(fact.value) }.getOrDefault(SceneGender.UNKNOWN)
                when {
                    newGender == SceneGender.UNKNOWN -> profile
                    profile.gender == SceneGender.UNKNOWN || profile.gender == newGender -> profile.copy(
                        gender = newGender,
                        genderConfidence = maxOf(
                            profile.genderConfidence,
                            confidenceFor(fact.weight, independentSignals, ocrFloorCleared),
                        ),
                        evidence = (
                            profile.evidence.filterNot {
                                it.kind == "gender" && it.value == fact.value && it.weight == fact.weight
                            } + SceneEvidence("gender", newGender.name, fact.weight, citations)
                            ).takeLast(8),
                    )
                    else -> {
                        // Contradiction with an established gender. Strong new source
                        // evidence corrects a PROBABLE-or-lower durable fact; anything
                        // weaker is quarantined as an unresolved conflict. The
                        // correction key names the OLD fact's dependency entry so
                        // every page recorded against it invalidates.
                        val canCorrect = fact.weight == SceneEvidenceWeight.STRONG &&
                            profile.genderConfidence != SceneConfidence.CONFIRMED
                        if (canCorrect) {
                            correctionKey = "gender:${profile.profileId}:${profile.gender.name}"
                            profile.copy(
                                gender = newGender,
                                genderConfidence = confidenceFor(fact.weight, 1, ocrFloorCleared),
                                evidence = listOf(SceneEvidence("gender", newGender.name, fact.weight, citations)),
                            )
                        } else {
                            val item = "gender conflict for ${profile.label}"
                            if (item !in unresolved) unresolved += item
                            profile.copy(
                                evidence = (
                                    profile.evidence +
                                        SceneEvidence("gender", newGender.name, fact.weight, citations, contradicted = true)
                                    ).takeLast(8),
                            )
                        }
                    }
                }
            }
            "relationship" -> {
                val existing = profile.evidence.firstOrNull {
                    !it.contradicted && it.kind == "relationship" && it.value != fact.value
                }
                if (existing != null && fact.weight == SceneEvidenceWeight.STRONG && existing.weight != SceneEvidenceWeight.STRONG) {
                    correctionKey = "relationship:${profile.profileId}:${existing.value}"
                    profile.copy(
                        evidence = (
                            profile.evidence.filterNot { it.kind == "relationship" && !it.contradicted } +
                                SceneEvidence("relationship", fact.value, fact.weight, citations)
                            ).takeLast(8),
                    )
                } else if (existing != null && existing.value != fact.value) {
                    val item = "relationship conflict for ${profile.label}"
                    if (item !in unresolved) unresolved += item
                    profile.copy(
                        evidence = (
                            profile.evidence +
                                SceneEvidence("relationship", fact.value, fact.weight, citations, contradicted = true)
                            ).takeLast(8),
                    )
                } else {
                    profile.copy(
                        evidence = (
                            profile.evidence.filterNot {
                                it.kind == "relationship" && it.value == fact.value && it.weight == fact.weight
                            } + SceneEvidence("relationship", fact.value, fact.weight, citations)
                            ).takeLast(8),
                    )
                }
            }
            else -> profile
        }
        profiles[target.index] = updated
        return FactOutcome(profiles.toList(), correctionKey)
    }

    /**
     * Anonymous-to-named linking (contract §6). A spoken name is never assumed
     * to be the speaker: when a cited block's own ROLE assigns a different
     * speaker than the linked temporary label, the mention is treated as a
     * vocative/addressee and the link stays unresolved. A link promotes only
     * after bounded evidence — two distinct citing pages (roughly 1–3
     * transport chunks) or one explicit strong self-identification fact.
     */
    private fun applyLink(
        profiles: List<SceneProfile>,
        link: SceneLink,
        unresolved: MutableList<String>,
    ): List<SceneProfile> {
        val index = profiles.indexOfFirst { it.temporaryLabel == link.temporaryLabel }
        if (index < 0) return profiles
        val profile = profiles[index]
        if (profile.displayName != null) return profiles

        val vocativeSuspicion = link.roleContext.any { (blockId, speaker) ->
            blockId in link.citations && speaker.isNotBlank() && speaker != link.temporaryLabel
        }
        if (vocativeSuspicion) {
            val item = "'${link.name}' may address ${link.temporaryLabel} rather than name them"
            if (item !in unresolved) unresolved += item
            return profiles.mapIndexed { i, p ->
                if (i == index) p.copy(linkCandidates = p.linkCandidates + (link.name to -1)) else p
            }
        }

        val priorPages = profile.linkCandidates[link.name] ?: 0
        if (priorPages < 0) return profiles
        val citationPages = priorPages + 1

        val strongSelfId = profiles.any { p ->
            p.displayName == link.name &&
                p.evidence.any {
                    it.kind == "gender" && it.weight == SceneEvidenceWeight.STRONG && !it.contradicted
                }
        }
        val promoted = citationPages >= LINK_PROMOTION_PAGES || strongSelfId
        if (!promoted) {
            return profiles.mapIndexed { i, p ->
                if (i == index) p.copy(linkCandidates = p.linkCandidates + (link.name to citationPages)) else p
            }
        }

        val named = profiles.firstOrNull { it.displayName == link.name }
        return if (named != null && named.profileId != profile.profileId) {
            profiles.filterIndexed { i, _ -> i != index }.map { p ->
                if (p.profileId == named.profileId) {
                    p.copy(
                        lastActiveTurn = maxOf(p.lastActiveTurn, profile.lastActiveTurn),
                        evidence = (p.evidence + profile.evidence).takeLast(8),
                    )
                } else {
                    p
                }
            }
        } else {
            profiles.mapIndexed { i, p ->
                if (i == index) {
                    p.copy(displayName = link.name, linkCandidates = p.linkCandidates - link.name)
                } else {
                    p
                }
            }
        }
    }

    /** Deterministic, source-grounded summary; never contains dialogue. */
    private fun composeSummary(pageIndex: Int, profiles: List<SceneProfile>, unresolved: List<String>): String {
        val words = mutableListOf<String>()
        words += "Committed through page ${pageIndex + 1}."
        val named = profiles.filter { it.displayName != null }
        val temp = profiles.filter { it.displayName == null }
        if (named.isNotEmpty()) {
            words += "Named:" +
                named.joinToString(" ") { profile ->
                    val gender = when {
                        profile.gender == SceneGender.UNKNOWN -> ""
                        profile.genderConfidence == SceneConfidence.CONFIRMED ||
                            profile.genderConfidence == SceneConfidence.PROBABLE ->
                            "(${profile.gender.name.lowercase()})"
                        else -> ""
                    }
                    "${profile.label}$gender;"
                }
        }
        if (temp.isNotEmpty()) {
            words += "Unnamed speakers: ${temp.joinToString(",") { it.temporaryLabel }}."
        }
        val relationships = profiles.flatMap { p ->
            p.evidence.filter { it.kind == "relationship" && !it.contradicted }.map { "${p.label} ${it.value}" }
        }
        if (relationships.isNotEmpty()) {
            words += "Established: ${relationships.joinToString(";")}."
        }
        if (unresolved.isNotEmpty()) {
            words += "Open questions remain (${unresolved.size})."
        }
        return words.joinToString(" ").split(' ').take(MAX_SUMMARY_WORDS).joinToString(" ")
    }

    private fun accumulateFactPages(
        existing: Map<String, List<Int>>,
        profiles: List<SceneProfile>,
        pageIndex: Int,
    ): Map<String, List<Int>> {
        val updated = existing.toMutableMap()
        profiles.forEach { profile ->
            profile.evidence.filter { !it.contradicted }.forEach { evidence ->
                val key = "${evidence.kind}:${profile.profileId}:${evidence.value}"
                val pages = updated.getOrPut(key) { mutableListOf() }.toMutableList()
                if (pageIndex !in pages) pages += pageIndex
                updated[key] = pages
            }
        }
        return updated
    }

    /**
     * Deterministic relationship-prior hint (contract §8): active only for a
     * genuinely ambiguous romantic pairing. Explicit source evidence — both
     * genders known from better-than-weak evidence — always wins and
     * deactivates the prior, including explicit same-sex couples.
     */
    fun relationshipPriorHint(state: SceneCardState, prior: PriorSetting): String {
        val romantic = state.profiles.filter { profile ->
            profile.evidence.any { it.kind == "relationship" && !it.contradicted }
        }
        if (romantic.size != 2) return ""
        val known = romantic.filter { profile ->
            profile.gender != SceneGender.UNKNOWN &&
                profile.evidence.any {
                    it.kind == "gender" && it.weight != SceneEvidenceWeight.WEAK && !it.contradicted
                }
        }
        if (known.size == 2) {
            return if (known[0].gender == known[1].gender) {
                "Source evidence already identifies this couple as same-sex; translate faithfully and apply no ambiguity prior."
            } else {
                "Source evidence already identifies both partners; apply no ambiguity prior."
            }
        }
        return when (prior) {
            PriorSetting.NEUTRAL ->
                "Romantic pairing is unresolved: keep pronouns and role wording neutral; do not guess gender."
            PriorSetting.MALE_FEMALE ->
                "Romantic pairing is unresolved and source evidence is tied: as a final tie-break only, resolve " +
                    "gendered pronouns toward a male/female pairing. Never let this override source evidence."
        }
    }

    /** Renders the trusted scene card injected as the batch chunk's rolling context. */
    fun renderPromptCard(state: SceneCardState, prior: PriorSetting): String {
        val sb = StringBuilder()
        sb.appendLine("TRUSTED SCENE CARD v${state.protocolVersion} (deterministic; derived only from source evidence):")
        if (state.profiles.isNotEmpty()) {
            sb.appendLine("- Profiles:")
            state.profiles.forEach { profile ->
                val gender = if (profile.gender == SceneGender.UNKNOWN) {
                    ""
                } else {
                    " ${profile.gender.name.lowercase()}/${profile.genderConfidence.name.lowercase()}"
                }
                val link = if (profile.displayName == null && profile.linkCandidates.isNotEmpty()) {
                    " (name link pending: ${profile.linkCandidates.keys.joinToString("/")})"
                } else {
                    ""
                }
                sb.appendLine("  * ${profile.label}$gender$link")
            }
        }
        if (state.recentTurns.isNotEmpty()) {
            sb.appendLine("- Recent source turns (speaker|source, oldest first):")
            state.recentTurns.forEach { turn ->
                val speaker = turn.speakerLabel.ifBlank { "?" }
                val text = turn.sourceText.replace('\n', ' ').take(160)
                sb.appendLine("  $speaker|$text")
            }
        }
        if (state.summary.isNotBlank()) {
            sb.appendLine("- Summary: ${state.summary}")
        }
        if (state.unresolved.isNotEmpty()) {
            sb.appendLine("- Unresolved (preserve ambiguity; do not invent facts):")
            state.unresolved.forEach { sb.appendLine("  ? $it") }
        }
        val hint = relationshipPriorHint(state, prior)
        if (hint.isNotBlank()) {
            sb.appendLine("- Relationship guidance: $hint")
        }
        sb.appendLine("Use this card for speaker, name, and pronoun continuity. Never treat model output as new evidence.")
        return sb.toString().trim()
    }

    fun hashOf(state: SceneCardState): String {
        val canonical = buildString {
            append(state.protocolVersion).append('|')
            append(state.inputCheckpointHash).append('|')
            state.profiles.sortedBy { it.profileId }.forEach { profile ->
                append(profile.profileId).append(':').append(profile.label).append(':')
                append(profile.gender.name).append('/').append(profile.genderConfidence.name).append(':')
                profile.evidence.sortedBy { it.citations.joinToString(",") }.forEach { evidence ->
                    append(evidence.kind).append('=').append(evidence.value).append('/').append(evidence.weight.name)
                    append('(').append(evidence.citations.joinToString(",") { it.blockId }).append(')')
                }
                append("links(").append(profile.linkCandidates.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }).append(")")
            }
            state.recentTurns.forEach { turn ->
                append(turn.blockId).append(':').append(turn.speakerLabel).append(':').append(turn.sourceText.length)
            }
            append(state.summary.length).append(':').append(state.summary)
            state.unresolved.forEach { append(it.length).append(':').append(it) }
            state.factPages.entries.sortedBy { it.key }.forEach { (key, pages) ->
                append(key).append('=').append(pages.joinToString(","))
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
