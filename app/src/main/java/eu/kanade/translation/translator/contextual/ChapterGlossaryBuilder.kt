package eu.kanade.translation.translator.contextual

/**
 * Pure, deterministic, **advisory** chapter-level term→target glossary builder.
 *
 * Mines recurring, consistent source→target mappings from translated OCR pairs
 * so proper nouns (names, places, recurring terms) are rendered consistently
 * across a chapter — including after the rolling-context window evicts them.
 *
 * It is intentionally high-precision / low-recall and NEVER causes a fallback:
 * a wrong or empty entry can only nudge a translation; it cannot break parsing
 * or rendering (it is injected only as advisory context via
 * [TranslationPrompts.contextPrefix]).
 *
 * Candidate = a maximal CJK run (a name/compound). A candidate is kept only when
 * it recurs in ≥ [MIN_RECURRENCE] bubbles AND a single capitalized Latin token
 * in the target is recalled in ≥ [RECALL_THRESHOLD] of those bubbles and is not
 * a common English sentence-starter/pronoun (which would be a coincidental, not
 * a discriminative, rendering).
 */
object ChapterGlossaryBuilder {

    private const val MIN_RECURRENCE = 3
    private const val MAX_ENTRIES = 30
    private const val MIN_CANDIDATE_LEN = 2
    private const val MIN_RENDERING_LEN = 2
    private const val RECALL_THRESHOLD = 0.8

    // Function words / particles / pronouns / common verbs that recur in CJK but
    // are not terms. CJK sources; Latin sources rarely reach this path.
    private val CJK_BLOCKLIST = setOf(
        "の", "に", "を", "は", "が", "で", "と", "も", "へ", "や", "から", "まで", "こと", "もの",
        "これ", "それ", "あれ", "この", "その", "あの", "ここ", "そこ", "あそこ",
        "私", "僕", "俺", "君", "彼", "彼女", "俺達", "私達",
        "です", "ます", "だ", "ある", "いる", "する", "なる", "ない",
        "今日", "明日", "昨日",
        "什么", "的", "了", "是", "在", "和", "与", "把", "被", "都", "也", "就", "还", "又",
        "我", "你", "他", "她", "它", "们", "这", "那", "这里", "那里", "这个", "那个",
        "一个", "可以", "不", "没有", "怎么", "为什么", "现在", "今天", "明天", "昨天",
    )

    // Common English sentence-starters / pronouns / articles that are capitalized
    // by virtue of position, not because they render a name. Excluded as renderings
    // so they are not mistaken for a candidate's stable name rendering.
    private val ENGLISH_STARTER_BLOCKLIST = setOf(
        "The", "A", "An", "It", "He", "She", "They", "We", "I", "You",
        "This", "That", "These", "Those", "But", "And", "So", "Or", "Then",
        "Now", "Here", "There", "What", "When", "Why", "How", "Who", "Where",
        "My", "Your", "His", "Her", "Our", "Their", "Its", "Me", "Him", "Us",
        "Yes", "No", "Oh", "Ah", "Wait", "Hey", "Look",
    )

    /**
     * Incremental accumulator: call [add] for each translated (source, target)
     * pair, then [build] for the capped glossary map. Pure; holds no Android or
     * I/O state. Safe to seed from an existing glossary's pairs on batch resume.
     */
    class Stats {
        private val sourceCounts = HashMap<String, Int>()
        private val renderings = HashMap<String, HashMap<String, Int>>()

        fun add(source: String, translation: String) {
            val s = source.trim()
            val t = translation.trim()
            if (s.isEmpty() || t.isEmpty() || t == s) return
            val cands = candidates(s)
            if (cands.isEmpty()) return
            val targetTokens = capitalizedTokens(t)
            for (c in cands) {
                sourceCounts[c] = (sourceCounts[c] ?: 0) + 1
                if (targetTokens.isNotEmpty()) {
                    val map = renderings.getOrPut(c) { HashMap() }
                    for (token in targetTokens) {
                        map[token] = (map[token] ?: 0) + 1
                    }
                }
            }
        }

        fun build(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            sourceCounts.entries
                .filter {
                    it.value >= MIN_RECURRENCE &&
                        it.key.length >= MIN_CANDIDATE_LEN &&
                        it.key !in CJK_BLOCKLIST
                }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .forEach { (cand, total) ->
                    val rendering = bestRendering(cand, total) ?: return@forEach
                    out[cand] = rendering
                    if (out.size >= MAX_ENTRIES) return out
                }
            return out
        }

        private fun bestRendering(cand: String, total: Int): String? {
            val map = renderings[cand] ?: return null
            for ((token, count) in map.entries.sortedByDescending { it.value }) {
                if (token in ENGLISH_STARTER_BLOCKLIST) continue
                val recall = count.toDouble() / total
                if (recall >= RECALL_THRESHOLD) return token
            }
            return null
        }
    }

    /** Maximal CJK-ideograph (kanji/hanzi) runs (≥ [MIN_CANDIDATE_LEN]).
     *  Kana/hangul are excluded on purpose: in Japanese a name is a kanji run and
     *  hiragana particles/okurigana must break it (so "太郎は走る" yields "太郎",
     *  not the whole string). */
    private fun candidates(source: String): List<String> {
        val out = LinkedHashSet<String>()
        val run = StringBuilder()
        for (ch in source) {
            if (isIdeograph(ch)) {
                run.append(ch)
            } else {
                if (run.length >= MIN_CANDIDATE_LEN) out.add(run.toString())
                run.setLength(0)
            }
        }
        if (run.length >= MIN_CANDIDATE_LEN) out.add(run.toString())
        return out.toList()
    }

    /** Capitalized Latin tokens (≥ [MIN_RENDERING_LEN]) in the target — names
     *  romanize to a capitalized token. Single letters (e.g. "I") are excluded. */
    private fun capitalizedTokens(target: String): List<String> {
        return target.split(Regex("[^\\p{L}\\p{M}\\-']+"))
            .filter { it.length >= MIN_RENDERING_LEN && it[0].isUpperCase() }
    }

    /** Renders a glossary map as compact `source => target` lines for the prompt. */
    fun formatGlossary(glossary: Map<String, String>): String =
        glossary.entries.joinToString("\n") { "${it.key} => ${it.value}" }

    private fun isIdeograph(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }
}
