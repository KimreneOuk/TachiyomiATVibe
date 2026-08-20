package eu.kanade.translation.batch

/**
 * Deterministic parse + validation of a page-scoped CONTEXT_DELTA
 * (BatchTranslationProtocol v1, Phase 6 context-quality contract).
 *
 * The model proposes; this parser decides. Fail-closed: any malformed line,
 * unknown citation, or unknown section invalidates the whole page delta so the
 * page cannot advance the trusted context chain. Model-supplied confidence
 * values are never read — only the deterministic evidence weight table in
 * [SceneContextEngine] computes confidence.
 *
 * Delta grammar (line-oriented, all lowercase keywords):
 * ```
 * LINK|<temporaryLabel>|<name>|<blockId>[,<blockId>...]
 * FACT|<who>|gender=<FEMALE|MALE|OTHER>|<STRONG|MEDIUM|WEAK>|<blockId>[,<blockId>...]
 * FACT|<who>|relationship=<text>|<STRONG|MEDIUM|WEAK>|<blockId>[,<blockId>...]
 * ROLE|<blockId>|speaker=<who>|addressee=<who>
 * UNRESOLVED|<text>
 * ```
 * `who` is a temporary label or a linked name. Every citation must be a block
 * ID from the current request envelope or a previously committed turn.
 */
data class SceneLink(
    val temporaryLabel: String,
    val name: String,
    val citations: List<String>,
    /** blockId -> ROLE speaker for that block, used for vocative suspicion. */
    val roleContext: Map<String, String> = emptyMap(),
)

data class SceneFact(
    val who: String,
    val kind: String,
    val value: String,
    val weight: SceneEvidenceWeight,
    val citations: List<String>,
)

data class SceneRole(
    val blockId: String,
    val speaker: String,
    val addressee: String,
)

data class SceneContextDelta(
    val links: List<SceneLink>,
    val facts: List<SceneFact>,
    val roles: Map<String, SceneRole>,
    val unresolved: List<String>,
    /** blockId -> OCR confidence when the caller could supply one; default 1f. */
    val citedConfidences: Map<String, Float> = emptyMap(),
)

object SceneContextDeltaParser {

    data class ParseResult(
        val delta: SceneContextDelta?,
        val errors: List<String>,
    ) {
        val isValid: Boolean get() = delta != null && errors.isEmpty()
    }

    fun parse(
        raw: String?,
        knownBlockIds: Set<String>,
    ): ParseResult {
        if (raw.isNullOrBlank()) {
            return ParseResult(delta = SceneContextDelta(emptyList(), emptyList(), emptyMap(), emptyList()), errors = emptyList())
        }
        val errors = mutableListOf<String>()
        val links = mutableListOf<SceneLink>()
        val facts = mutableListOf<SceneFact>()
        val roles = linkedMapOf<String, SceneRole>()
        val unresolved = mutableListOf<String>()

        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "LINK" -> parseLink(parts, knownBlockIds, roles, errors)?.let(links::add)
                "FACT" -> parseFact(parts, knownBlockIds, errors)?.let(facts::add)
                "ROLE" -> parseRole(parts, knownBlockIds, roles, errors)?.let { roles[it.blockId] = it }
                "UNRESOLVED" -> {
                    if (parts.size != 2 || parts[1].isBlank()) {
                        errors += "Malformed UNRESOLVED line"
                    } else {
                        unresolved += parts[1]
                    }
                }
                else -> errors += "Unknown delta section '${parts.firstOrNull()?.take(24)}'"
            }
        }

        if (errors.isNotEmpty()) {
            return ParseResult(delta = null, errors = errors)
        }
        return ParseResult(
            delta = SceneContextDelta(links = links, facts = facts, roles = roles, unresolved = unresolved),
            errors = emptyList(),
        )
    }

    private fun parseLink(
        parts: List<String>,
        knownBlockIds: Set<String>,
        roles: Map<String, SceneRole>,
        errors: MutableList<String>,
    ): SceneLink? {
        if (parts.size != 4) {
            errors += "Malformed LINK line"
            return null
        }
        val temp = parts[1].trim()
        val name = parts[2].trim()
        if (temp.isEmpty() || name.isEmpty()) {
            errors += "Malformed LINK line"
            return null
        }
        val citations = parseCitations(parts[3], knownBlockIds, errors, "LINK") ?: return null
        return SceneLink(
            temporaryLabel = temp,
            name = name,
            citations = citations,
            roleContext = roles.entries.associate { (blockId, role) -> blockId to role.speaker },
        )
    }

    private fun parseFact(
        parts: List<String>,
        knownBlockIds: Set<String>,
        errors: MutableList<String>,
    ): SceneFact? {
        if (parts.size != 5) {
            errors += "Malformed FACT line"
            return null
        }
        val who = parts[1].trim()
        val assertion = parts[2].trim()
        val weight = when (parts[3].trim().uppercase()) {
            "STRONG" -> SceneEvidenceWeight.STRONG
            "MEDIUM" -> SceneEvidenceWeight.MEDIUM
            "WEAK" -> SceneEvidenceWeight.WEAK
            else -> {
                errors += "Unknown FACT weight"
                null
            }
        } ?: return null
        val citations = parseCitations(parts[4], knownBlockIds, errors, "FACT") ?: return null
        val (kind, value) = when {
            assertion.startsWith("gender=") -> "gender" to assertion.removePrefix("gender=").trim().uppercase()
            assertion.startsWith("relationship=") -> "relationship" to assertion.removePrefix("relationship=").trim()
            else -> {
                errors += "Unknown FACT assertion"
                null
            }
        } ?: return null
        if (who.isEmpty()) {
            errors += "Malformed FACT line"
            return null
        }
        if (kind == "gender" && value !in setOf("FEMALE", "MALE", "OTHER")) {
            errors += "Unknown gender value"
            return null
        }
        if (kind == "relationship" && value.isEmpty()) {
            errors += "Empty relationship value"
            return null
        }
        return SceneFact(who = who, kind = kind, value = value, weight = weight, citations = citations)
    }

    private fun parseRole(
        parts: List<String>,
        knownBlockIds: Set<String>,
        roles: MutableMap<String, SceneRole>,
        errors: MutableList<String>,
    ): SceneRole? {
        if (parts.size != 4) {
            errors += "Malformed ROLE line"
            return null
        }
        val blockId = parts[1].trim()
        if (blockId !in knownBlockIds) {
            errors += "ROLE cites unknown block '$blockId'"
            return null
        }
        val speaker = parts[2].removePrefix("speaker=").trim()
        val addressee = parts[3].removePrefix("addressee=").trim()
        if (speaker.isEmpty() && addressee.isEmpty()) {
            errors += "Malformed ROLE line"
            return null
        }
        return SceneRole(blockId = blockId, speaker = speaker, addressee = addressee)
    }

    private fun parseCitations(
        raw: String,
        knownBlockIds: Set<String>,
        errors: MutableList<String>,
        section: String,
    ): List<String>? {
        val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            errors += "$section cites no block"
            return null
        }
        val unknown = ids.filter { it !in knownBlockIds }
        if (unknown.isNotEmpty()) {
            errors += "$section cites unknown block '${unknown.first()}'"
            return null
        }
        return ids.distinct()
    }
}
