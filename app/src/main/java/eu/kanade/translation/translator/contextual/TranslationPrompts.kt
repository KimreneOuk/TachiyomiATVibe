package eu.kanade.translation.translator.contextual
import eu.kanade.translation.artifact.ProfileGender
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage

/**
 * Single source of truth for the AI-translator prompts,
 * shared by all four AI translators (DeepSeek, LM Studio, Gemini, OpenRouter) so localization
 * guidance never diverges between providers.
 */
object TranslationPrompts {

    fun idMappedSourceLine(id: String, block: TranslationBlock): String {
        val flattened = block.text
            .replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
        return "$id|$flattened"
    }

    data class ParsedLine(val id: String, val text: String)

    private val lineIdRegex: Regex = Regex("""\b((?:p\d+_)?b\d+)[^\w]*(.*)""")

    fun parseLine(line: String): ParsedLine? {
        val cleanLine = line.trim(' ', '\t', '\r', '\n', '`', '*')
        val match = lineIdRegex.find(cleanLine) ?: return null
        val id = match.groupValues[1]
        val content = match.groupValues[2].trim().removePrefix("|").removeSuffix("|").trim()
        return ParsedLine(id, content)
    }

    /** Combined context prefix from a glossary (stable term renderings) and a
     *  rolling recent-pairs buffer. Empty when both are blank so the first chunk
     *  of a chapter adds no framing noise. */
    fun contextPrefix(rollingContext: String, glossary: String): String {
        val g = glossary.trim()
        val r = rollingContext.trim()
        if (g.isEmpty() && r.isEmpty()) return ""
        val sb = StringBuilder()
        if (g.isNotEmpty()) {
            sb.append("Established terms (reuse these exact English renderings; keep names consistent):\n")
            sb.append(g).append("\n\n")
        }
        if (r.isNotEmpty()) {
            sb.append("Previous context / recent translated pairs (use for speaker, name & pronoun continuity):\n")
            sb.append(r).append("\n\n")
        }
        return sb.toString()
    }

    /** Manga (Japanese) reads right-to-left; manhwa/manhua (Korean/Chinese) and
     *  Latin sources read left-to-right. Used to nudge reading-order inference. */
    fun readingDirectionHint(from: TextRecognizerLanguage): String = when (from) {
        TextRecognizerLanguage.JAPANESE -> "right-to-left, top-to-bottom"
        else -> "left-to-right, top-to-bottom"
    }

    /** True for source languages that habitually drop the subject pronoun
     *  (Japanese, Chinese, Korean, plus the Romance pro-drop languages Spanish,
     *  Portuguese, Italian). English, German, French, Indonesian, Vietnamese and
     *  Russian are non-pro-drop, so the subject-inference guidance does not apply
     *  to them and would mislead the model if always emitted. */
    fun isProDrop(from: TextRecognizerLanguage): Boolean = when (from) {
        TextRecognizerLanguage.JAPANESE,
        TextRecognizerLanguage.CHINESE,
        TextRecognizerLanguage.KOREAN,
        TextRecognizerLanguage.SPANISH,
        TextRecognizerLanguage.PORTUGUESE,
        TextRecognizerLanguage.ITALIAN,
        -> true
        else -> false
    }

    fun pass1SystemPrompt(
        from: TextRecognizerLanguage,
        to: TextTranslatorLanguage,
        batchProtocol: Boolean = false,
    ): String {
        val proDropContext = if (isProDrop(from)) {
            "Note: ${from.label} frequently omits subjects (pro-drop). Infer explicit subjects and maintain consistent character voice and pronouns.\n"
        } else {
            ""
        }
        val exampleId1 = if (batchProtocol) "p0_b0" else "b0"
        val exampleId2 = if (batchProtocol) "p0_b1" else "b1"

        return """
            You are a manga localization specialist. Translate the comic dialogue text blocks from ${from.label} to ${to.label}.

            $proDropContext
            RULES:
            - Output format: `ID|Translated Text`, exactly one line per block.
            - Naturalize dialogue into lively spoken comic English, preserving tone and humor.
            - Localize sound effects (e.g. *gasp*, *thud*).
            - Output ONLY the `ID|Translated Text` lines. No preambles, markdown formatting, or explanations.

            EXAMPLE:
            Input:
            $exampleId1|行く。
            $exampleId2|あの日、彼と出会った。
            Output:
            $exampleId1|I'm going.
            $exampleId2|That day, I met him.
        """.trimIndent()
    }

    // ------------------------------------------------------------------
    //  Stage-6 slice B (design §7): profile-aware ENRICHED prompt
    // assembly. Used ONLY by the  ProfileEnvelopeExecutor when a frozen
    // chapter profile is present; the legacy Batch/Manual/Auto paths never
    // call these, and every function above stays byte-identical. Without a
    // profile the executor keeps the legacy shape unchanged
    // (degraded-but-correct).
    //
    // The rendered text flows to providers through the EXISTING wire shape:
    // the glossary slot (framed by contextPrefix as "Established terms …")
    // carries the character/term sheet + scene context + decision rules; the
    // rolling slot (framed as "Previous context / recent translated pairs …")
    // carries the gap-free rolling history with the pronoun-marking rule.
    // ------------------------------------------------------------------

    /** Identity-vs-gender decision rules (design §7, pinned order):
     *  1. resolve the referent first;
     *  2. profile gender only when established;
     *  3. else strong current/rolling source evidence;
     *  4. if unresolved prefer name/title, restructuring, or natural
     *     singular "they". */
    fun profileIdentityGenderRules(): String = listOf(
        "Resolve the referent first: decide WHO speaks or is described before choosing any gendered wording.",
        "Use a profile gender ONLY when the character sheet states it; never infer gender from a prior translation.",
        "Without an established profile gender, use strong evidence in the current or previous source text.",
        "If still unresolved, prefer the character's name or title, restructure the sentence to avoid gender, or use the natural singular \"they\".",
    ).joinToString("\n") { "- $it" }

    /**
     * The enriched glossary-slot text: decision rules + the capped profile
     * subset (with entity ids for alias linking) + range-safe scene context.
     * Empty when the subset is empty and there are no rules to carry, so an
     * unmatched envelope adds no framing noise.
     */
    fun characterAndTermSheetPrefix(
        subset: ProfileSubsetMatcher.ProfileSubset,
        includeScenes: Boolean = true,
    ): String {
        val sb = StringBuilder()
        sb.append("CHARACTER & TERM SHEET (frozen chapter profile; identity before gender):\n")
        sb.append(profileIdentityGenderRules()).append('\n')
        sb.append("Scene context below guides tone and word choice for its page range ONLY — it is never a global replacement rule.\n")
        if (subset.entries.isNotEmpty()) {
            sb.append("Sheet:\n")
            for (entry in subset.entries) {
                sb.append(renderSubsetEntry(entry))
            }
        }
        if (includeScenes) {
            for (scene in subset.scenes) {
                sb.append("Scene [")
                    .append(scene.sceneId)
                    .append("] pages ")
                    .append(scene.firstNaturalPageIndex)
                    .append('-')
                    .append(scene.lastNaturalPageIndex)
                scene.register?.let { sb.append(", register ").append(it.name) }
                if (scene.toneFlags.isNotEmpty()) {
                    sb.append(", tone ").append(scene.toneFlags.joinToString("/") { it.name })
                }
                sb.append(':')
                scene.narrativeContext?.let { sb.append(' ').append(it) }
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    @Deprecated(
        message = "renamed to characterAndTermSheetPrefix per T933",
        replaceWith = ReplaceWith("characterAndTermSheetPrefix(subset, includeScenes)"),
    )
    fun profileAwareGlossaryPrefix(
        subset: ProfileSubsetMatcher.ProfileSubset,
        includeScenes: Boolean = true,
    ): String = characterAndTermSheetPrefix(subset, includeScenes)

    private fun renderSubsetEntry(entry: ProfileSubsetMatcher.SubsetEntry): String {
        val sb = StringBuilder("[${entry.factId}] ")
        when (entry.kind) {
            ProfileSubsetMatcher.EntryKind.ENTITY ->
                sb.append(entry.sourceForm.ifEmpty { "(unnamed)" }).append(" -> ").append(entry.targetForm)
            ProfileSubsetMatcher.EntryKind.TERM ->
                sb.append(entry.sourceForm).append(" always translates to \"").append(entry.targetForm).append('"')
            ProfileSubsetMatcher.EntryKind.GENDER ->
                sb.append(entry.sourceForm.ifEmpty { "(unnamed)" })
                    .append(" gender: ")
                    .append(entry.gender?.name ?: ProfileGender.UNKNOWN.name)
            ProfileSubsetMatcher.EntryKind.PRONOUN ->
                sb.append(entry.sourceForm.ifEmpty { "(unnamed)" }).append(" pronoun evidence: ").append(entry.targetForm)
            ProfileSubsetMatcher.EntryKind.RELATED ->
                sb.append(entry.sourceForm.ifEmpty { "(context)" }).append(": ").append(entry.targetForm)
        }
        if (entry.aliases.isNotEmpty()) {
            sb.append(" (aliases: ").append(entry.aliases.joinToString(", ")).append(')')
        }
        if (entry.kind == ProfileSubsetMatcher.EntryKind.GENDER && entry.gender == ProfileGender.CONFLICTING) {
            sb.append(" (CONFLICTING — do not guess; prefer name/title or singular \"they\")")
        }
        entry.note?.let { sb.append(" [note: ").append(it).append(']') }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * The enriched rolling-slot text (design §7.3): the gap-free frontier's
     * recent source=>target pairs PLUS resolved entity ids PLUS compact
     * unresolved state, with the pronoun-marking rule stated verbatim: prior
     * target-language pronouns are TRANSLATIONS, never canonical gender
     * evidence. Empty when there is nothing to carry.
     */
    fun profileAwareRollingPrefix(
        rollingPairs: String,
        resolvedEntityLines: List<String>,
        unresolvedLines: List<String>,
    ): String {
        val pairs = rollingPairs.trim()
        val resolved = resolvedEntityLines.filter { it.isNotBlank() }
        val unresolved = unresolvedLines.filter { it.isNotBlank() }
        if (pairs.isEmpty() && resolved.isEmpty() && unresolved.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("PRIOR STORY CONTEXT. On each `source => target` line the RIGHT side is a PRIOR TRANSLATION: pronouns in it are translated renderings, NOT canonical gender evidence.\n")
        if (resolved.isNotEmpty()) {
            sb.append("Already resolved characters (link aliases to these ids):\n")
            resolved.forEach { sb.append(it).append('\n') }
        }
        if (unresolved.isNotEmpty()) {
            sb.append("Unresolved references (background only — do not guess):\n")
            unresolved.forEach { sb.append(it).append('\n') }
        }
        if (pairs.isNotEmpty()) {
            sb.append("Recent pairs:\n").append(pairs).append('\n')
        }
        return sb.toString()
    }
}
