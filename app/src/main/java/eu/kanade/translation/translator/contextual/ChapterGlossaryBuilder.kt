package eu.kanade.translation.translator.contextual

import java.util.TreeSet

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

    private val CAPITALIZED_TOKEN_REGEX = Regex("[^\\p{L}\\p{M}\\-']+")

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
     * Incrementally maintained candidate rank entry for [Stats.rankedCandidates].
     * Sorts descending by recurrence count, then ascending by candidate string.
     */
    private data class Candidate(val cand: String, val count: Int) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int {
            val cmp = other.count.compareTo(this.count)
            return if (cmp != 0) cmp else this.cand.compareTo(other.cand)
        }
    }

    /**
     * Incremental accumulator: call [add] for each translated (source, target)
     * pair, then [build] for the capped glossary map. Pure; holds no Android or
     * I/O state. Safe to seed from translated pairs ([translatedPairs]) on batch/session
     * resume; NEVER seed from the capped 30-entry glossary map.
     */
    class Stats {
        private val sourceCounts = HashMap<String, Int>()
        private val renderings = HashMap<String, HashMap<String, Int>>()
        private val rankedCandidates = TreeSet<Candidate>()

        fun add(source: String, translation: String) {
            val s = source.trim()
            val t = translation.trim()
            if (s.isEmpty() || t.isEmpty() || t == s) return
            val cands = candidates(s)
            if (cands.isEmpty()) return
            val targetTokens = capitalizedTokens(t)
            for (c in cands) {
                val currentCount = sourceCounts[c] ?: 0
                val isEligible = c.length >= MIN_CANDIDATE_LEN && c !in CJK_BLOCKLIST
                if (isEligible && currentCount >= MIN_RECURRENCE) {
                    rankedCandidates.remove(Candidate(c, currentCount))
                }
                val newCount = currentCount + 1
                sourceCounts[c] = newCount
                if (isEligible && newCount >= MIN_RECURRENCE) {
                    rankedCandidates.add(Candidate(c, newCount))
                }
                if (targetTokens.isNotEmpty()) {
                    val map = renderings.getOrPut(c) { HashMap() }
                    for (token in targetTokens) {
                        map[token] = (map[token] ?: 0) + 1
                    }
                }
            }
        }

        fun remove(source: String, translation: String) {
            val s = source.trim()
            val t = translation.trim()
            if (s.isEmpty() || t.isEmpty() || t == s) return
            val cands = candidates(s)
            if (cands.isEmpty()) return
            val targetTokens = capitalizedTokens(t)
            for (c in cands) {
                val currentCount = sourceCounts[c] ?: continue
                val isEligible = c.length >= MIN_CANDIDATE_LEN && c !in CJK_BLOCKLIST
                if (isEligible && currentCount >= MIN_RECURRENCE) {
                    rankedCandidates.remove(Candidate(c, currentCount))
                }
                val newCount = currentCount - 1
                if (newCount <= 0) {
                    sourceCounts.remove(c)
                } else {
                    sourceCounts[c] = newCount
                    if (isEligible && newCount >= MIN_RECURRENCE) {
                        rankedCandidates.add(Candidate(c, newCount))
                    }
                }
                if (targetTokens.isNotEmpty()) {
                    val map = renderings[c]
                    if (map != null) {
                        for (token in targetTokens) {
                            val tokenCount = map[token] ?: continue
                            val newTokenCount = tokenCount - 1
                            if (newTokenCount <= 0) {
                                map.remove(token)
                            } else {
                                map[token] = newTokenCount
                            }
                        }
                        if (map.isEmpty()) {
                            renderings.remove(c)
                        }
                    }
                }
            }
        }

        fun addAll(pairs: Iterable<Pair<String, String>>) {
            for ((s, t) in pairs) {
                add(s, t)
            }
        }

        fun removeAll(pairs: Iterable<Pair<String, String>>) {
            for ((s, t) in pairs) {
                remove(s, t)
            }
        }

        fun replace(oldPairs: List<Pair<String, String>>, newPairs: List<Pair<String, String>>) {
            removeAll(oldPairs)
            addAll(newPairs)
        }

        fun build(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (candidate in rankedCandidates) {
                val rendering = bestRendering(candidate.cand, candidate.count) ?: continue
                out[candidate.cand] = rendering
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

    /**
     * Labeled fallback per  memory contract: recomputes a glossary map from
     * a stream or collection of translated pairs.
     */
    fun streamedRecompute(pairs: Iterable<Pair<String, String>>): Map<String, String> {
        val stats = Stats()
        for ((s, t) in pairs) {
            stats.add(s, t)
        }
        return stats.build()
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
        return target.split(CAPITALIZED_TOKEN_REGEX)
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
