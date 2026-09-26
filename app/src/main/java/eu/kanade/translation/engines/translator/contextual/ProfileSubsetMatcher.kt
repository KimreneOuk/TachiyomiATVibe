package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.FactScope
import eu.kanade.translation.persistence.artifact.FactType
import eu.kanade.translation.persistence.artifact.ProfileFact
import eu.kanade.translation.persistence.artifact.ProfileGender
import eu.kanade.translation.persistence.artifact.SceneRegister
import eu.kanade.translation.persistence.artifact.ToneFlag

/**
 *  Stage-6 slice B (design §7.1/§7.2): the PURE frozen-profile subset
 * matcher behind the profile-aware Batch translation prompt.
 *
 * Design contract (chapter-profile-batch-design §7 items 1-2):
 *  - "A local matcher scans current source text for canonical forms, aliases,
 *    titles and terms, then adds scene participants and directly related
 *    facts. Include entity IDs so the model can link aliases. Cap this
 *    subset; full series/chapter profiles are never blindly repeated."
 *  - "Range-safe scene context: include only the current scene/range facts …
 *    plus chapter-wide canonical facts. Explicit-scene context guides lexical
 *    meaning; it never creates a global replacement rule."
 *
 * Purity and determinism: [match] is a pure function of
 * (frozen profile, envelope source texts) — no I/O, no clocks, no store
 * access. The output order follows the profile's own fact/scene order
 * (frozen content order), the subset is deterministically capped at
 * [MAX_SUBSET_FACTS] entries and [MAX_SCENE_CONTEXTS] scenes, and identical
 * inputs always produce an identical result.
 */
object ProfileSubsetMatcher {

    /** Hard cap on prompt subset entries (bounded constant,  "T"). */
    const val MAX_SUBSET_FACTS = 24

    /** Hard cap on scene-context blocks carried in ONE prompt. */
    const val MAX_SCENE_CONTEXTS = 4

    /** Cap on resolved-entity lines derived from the rolling history. */
    const val MAX_RESOLVED_ENTITY_LINES = 8

    /** Cap on compact unresolved-reference lines carried in ONE prompt. */
    const val MAX_UNRESOLVED_LINES = 4

    /** What kind of prompt line a subset entry renders as. */
    enum class EntryKind { ENTITY, TERM, GENDER, PRONOUN, RELATED }

    /** One capped, prompt-renderable profile fact with its entity id. */
    data class SubsetEntry(
        val factId: String,
        val kind: EntryKind,
        val sourceForm: String,
        val targetForm: String,
        val aliases: List<String>,
        val gender: ProfileGender? = null,
        val note: String? = null,
    )

    /** One range-safe scene context block (ONLY scenes overlapping the envelope). */
    data class SceneContext(
        val sceneId: String,
        val firstNaturalPageIndex: Int,
        val lastNaturalPageIndex: Int,
        val register: SceneRegister?,
        val toneFlags: List<ToneFlag>,
        val narrativeContext: String?,
        val participantIds: List<String>,
    )

    /** The matched, capped subset for ONE envelope. */
    data class ProfileSubset(
        val entries: List<SubsetEntry>,
        val scenes: List<SceneContext>,
        /** True when the matcher hit a cap and deterministically dropped tail content. */
        val truncated: Boolean,
    )

    /** The CURRENT source text of one contributing envelope page. */
    data class EnvelopeSource(
        val naturalPageIndex: Int,
        val sourceText: String,
    )

    /**
     * Matches the frozen profile against the envelope's CURRENT source text
     * and returns the capped, deterministically ordered subset.
     */
    fun match(
        profile: ChapterTranslationProfile,
        envelopeSources: List<EnvelopeSource>,
    ): ProfileSubset {
        if (envelopeSources.isEmpty()) {
            return ProfileSubset(emptyList(), emptyList(), truncated = false)
        }
        val firstPage = envelopeSources.minOf { it.naturalPageIndex }
        val lastPage = envelopeSources.maxOf { it.naturalPageIndex }
        val corpus = envelopeSources.joinToString("\n") { it.sourceText }
        val corpusLower = corpus.lowercase()

        // Scenes overlapping the envelope's natural page range (range safety,
        // design §7.2): ONLY these carry narrative/participant context.
        val overlappingScenes = profile.scenes
            .filter { overlaps(it.pageRange.firstNaturalPageIndex, it.pageRange.lastNaturalPageIndex, firstPage, lastPage) }
        val participantIds = linkedSetOf<String>().apply {
            overlappingScenes.forEach { scene -> addAll(scene.participants) }
        }

        val factById = (profile.entities + profile.terms).associateBy { it.factId }

        fun usable(fact: ProfileFact): Boolean = usableAt(fact, firstPage, lastPage)

        fun matchedText(fact: ProfileFact): Boolean =
            formsOf(fact).any { containsForm(corpus, corpusLower, it) }

        // 1. Entities: text-matched OR current-scene participants.
        val entityFacts = profile.entities
            .filter { fact ->
                fact.type == FactType.ENTITY_IDENTITY &&
                    usable(fact) &&
                    (matchedText(fact) || fact.factId in participantIds)
            }

        // 2. Terms: text-matched canonical terms (chapter-wide or in-range).
        val termFacts = profile.terms
            .filter { fact -> fact.type == FactType.TERM && usable(fact) && matchedText(fact) }

        // 3. Directly related facts: GENDER/PRONOUN facts linked to an included
        // entity by the entity's canonical source form, plus any other
        // usable fact that matched the text directly. Weak REJECTED/CONFLICTING
        // evidence is carried as a note (never averaged into a guess, §6.3).
        val includedForms = (entityFacts + termFacts)
            .mapNotNull { it.canonicalSourceForm?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val relatedFacts = (profile.entities + profile.terms)
            .filter { fact ->
                fact.type != FactType.ENTITY_IDENTITY &&
                    fact.type != FactType.TERM &&
                    usable(fact) &&
                    (linksToIncludedForm(fact, includedForms) || matchedText(fact))
            }

        val entries = ArrayList<SubsetEntry>()
        val seen = HashSet<String>()
        var truncated = false
        for (fact in entityFacts.asSequence() + termFacts.asSequence() + relatedFacts.asSequence()) {
            if (!seen.add(fact.factId)) continue
            if (entries.size >= MAX_SUBSET_FACTS) {
                truncated = true
                break
            }
            entries += toEntry(fact)
        }

        val scenes = overlappingScenes.take(MAX_SCENE_CONTEXTS).map { scene ->
            SceneContext(
                sceneId = scene.sceneId,
                firstNaturalPageIndex = scene.pageRange.firstNaturalPageIndex,
                lastNaturalPageIndex = scene.pageRange.lastNaturalPageIndex,
                register = scene.register,
                toneFlags = scene.toneFlags.sorted().toList(),
                narrativeContext = scene.narrativeContext?.trim()?.takeIf { it.isNotEmpty() },
                participantIds = scene.participants.toList(),
            )
        }
        if (overlappingScenes.size > MAX_SCENE_CONTEXTS) truncated = true

        return ProfileSubset(entries = entries, scenes = scenes, truncated = truncated)
    }

    /**
     * Rolling-history enrichment (design §7.3): the profile entities whose
     * source forms appear anywhere in the ALREADY-COMMITTED rolling text,
     * rendered as `"[factId] source -> target"` lines (capped, profile order).
     * Pure text scanning — the frontier's gap-free discipline is unchanged.
     */
    fun resolvedEntityLines(
        profile: ChapterTranslationProfile,
        observedText: String,
    ): List<String> {
        if (observedText.isBlank()) return emptyList()
        val observedLower = observedText.lowercase()
        val lines = ArrayList<String>(MAX_RESOLVED_ENTITY_LINES)
        for (fact in profile.entities) {
            if (fact.type != FactType.ENTITY_IDENTITY) continue
            if (lines.size >= MAX_RESOLVED_ENTITY_LINES) break
            val form = fact.canonicalSourceForm?.trim().orEmpty()
            if (form.isEmpty()) continue
            val hit = containsForm(observedText, observedLower, form) ||
                formsOf(fact).drop(1).any { containsForm(observedText, observedLower, it) }
            if (hit) {
                val target = fact.canonicalTargetForm?.trim().orEmpty()
                lines += "[${fact.factId}] $form -> $target"
            }
        }
        return lines
    }

    /**
     * Compact unresolved-reference state (design §7.3): the frozen profile's
     * retained ambiguity, rendered as bounded note lines. Never averaged away,
     * never guessed (§6.3) — the prompt carries them as background, not orders.
     */
    fun unresolvedReferenceLines(profile: ChapterTranslationProfile): List<String> {
        val lines = ArrayList<String>(MAX_UNRESOLVED_LINES)
        for (fact in profile.unresolvedFacts) {
            if (lines.size >= MAX_UNRESOLVED_LINES) break
            val form = fact.canonicalSourceForm?.trim().orEmpty()
            val note = fact.note?.trim().orEmpty()
            val line = when {
                form.isNotEmpty() && note.isNotEmpty() -> "[${fact.factId}] $form: $note"
                form.isNotEmpty() -> "[${fact.factId}] $form (unresolved)"
                note.isNotEmpty() -> "[${fact.factId}] $note"
                else -> null
            } ?: continue
            lines += line
        }
        return lines
    }

    // ------------------------------------------------------------------

    private fun toEntry(fact: ProfileFact): SubsetEntry = SubsetEntry(
        factId = fact.factId,
        kind = when (fact.type) {
            FactType.ENTITY_IDENTITY -> EntryKind.ENTITY
            FactType.TERM -> EntryKind.TERM
            FactType.GENDER -> EntryKind.GENDER
            FactType.PRONOUN -> EntryKind.PRONOUN
            else -> EntryKind.RELATED
        },
        sourceForm = fact.canonicalSourceForm?.trim().orEmpty(),
        targetForm = fact.canonicalTargetForm?.trim().orEmpty(),
        aliases = fact.aliases.map { it.trim() }.filter { it.isNotEmpty() },
        gender = fact.gender,
        note = fact.note?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** Canonical form + aliases + titles ("forms" per design §7.1). */
    private fun formsOf(fact: ProfileFact): List<String> = buildList {
        fact.canonicalSourceForm?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        fact.aliases.forEach { alias -> alias.trim().takeIf { it.isNotEmpty() }?.let(::add) }
    }

    /** A GENDER/PRONOUN/RELATIONSHIP fact links to an entity via the SAME source form. */
    private fun linksToIncludedForm(fact: ProfileFact, includedForms: Set<String>): Boolean {
        val form = fact.canonicalSourceForm?.trim().orEmpty()
        if (form.isEmpty()) return false
        if (form in includedForms) return true
        // Latin forms compare case-insensitively; CJK forms exactly.
        val lowered = if (isCjkLike(form)) form else form.lowercase()
        return includedForms.any { included ->
            if (isCjkLike(included)) included == lowered else included.lowercase() == lowered
        }
    }

    /**
     * Range safety fence (design §7.2): chapter-wide canonical facts are
     * always usable; RANGE_SCOPED facts only inside their range; AVAILABLE_FROM
     * facts only from their page onward — evaluated at the envelope's FIRST
     * page (the earliest point the prompt is used).
     */
    private fun usableAt(fact: ProfileFact, firstPage: Int, lastPage: Int): Boolean = when (fact.scope) {
        FactScope.CANONICAL_CHAPTER_WIDE -> true
        // Wave-7a F-W7-2: a scoped fact with a MISSING scope payload
        // default-DENIES — a malformed sidecar fact must never ride the
        // prompt ahead of its range (§7.2 fence).
        FactScope.RANGE_SCOPED -> fact.applicableRange?.let {
            overlaps(it.firstNaturalPageIndex, it.lastNaturalPageIndex, firstPage, lastPage)
        } ?: false
        FactScope.AVAILABLE_FROM ->
            fact.availableFrom
                ?.let { it.naturalPageIndex <= firstPage }
                ?: false
    }

    private fun overlaps(aFirst: Int, aLast: Int, bFirst: Int, bLast: Int): Boolean =
        aFirst <= bLast && aLast >= bFirst

    /**
     * Whole-string containment against the CURRENT envelope source text.
     * CJK forms match exactly (no casing); other scripts match
     * case-insensitively so Latin titles/aliases hit any capitalization.
     */
    private fun containsForm(corpus: String, corpusLower: String, form: String): Boolean {
        val trimmed = form.trim()
        if (trimmed.isEmpty()) return false
        return if (isCjkLike(trimmed)) {
            corpus.contains(trimmed)
        } else {
            corpusLower.contains(trimmed.lowercase())
        }
    }

    private fun isCjkLike(text: String): Boolean = text.any { it.isCjk() }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
}
