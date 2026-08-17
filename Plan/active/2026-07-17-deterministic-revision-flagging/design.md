# Design - Character-Aware Revision Flagging (replace AI self-tag)

Status: Design complete; implementation not started.
Date: 2026-07-17
Parent plan: `Plan/active/2026-07-16-unified-translation-pipeline/`

## Problem

The Revision feature's `FLAGGED` scope is populated by an AI self-confidence tag
(`[OK]`/`[FLAG]`) produced in the Pass-1 response. AI models are reliably bad at
self-assessment — confidently-wrong output is often self-tagged `[OK]`, and the
current design auto-flags every tagless valid line (`null -> true`), making
`FLAGGED` either unreliable or useless ("flag everything"). The flagging signal
is unreliable, costs prompt tokens, and trusts the one actor least able to judge
its own correctness.

## Objective

Replace the AI self-confidence tag with an **identity-based** heuristic: the AI
extracts a per-chapter character list (name + gender) as a structured side-output
of Pass-1, and deterministic code flags a block for revision when a gendered
pronoun co-occurs with a name whose listed gender the pronoun contradicts.

Key principle: **AI does extraction (factual "who is in this scene"), code does
the flag decision (pronoun vs name gender match).** This separates the failure-
prone self-assessment from the reliable structural check. The character list is
user-visible and user-editable, turning any AI misgender-on-first-appearance
into an auditable, correctable condition rather than a silent error.

EN-target only (v1): the pronoun scan is English.

## Why identity-based (not density-based)

An earlier density heuristic ("gender appearing in ≥10% of chapter blocks =
grounded") was rejected because it is blind to identity:

| Block | Density heuristic | Identity (this design) |
|---|---|---|
| "Yuki said **he** was tired" (Yuki=female) | misses if male established | flags (he ≠ Yuki's gender) |
| "Yuki **she** smiled" 5 blocks later (AI drift) | misses | flags |
| minor char "Kenji" appears once as "he" | false-flags (not 10%) | trusts (Kenji=male in list) |

Identity is precise per-character; density is a blind aggregate. Identity also
respects the original insight: a gendered pronoun is an "invention" only when it
contradicts a named character's known gender, not merely when it appears.

## Decisions (locked)

1. **Drop the AI `[OK]`/`[FLAG]` tag** entirely from Pass-1 prompt and parser.
2. **Flag only identity-mismatch blocks** (pronoun contradicts co-occurring name's gender).
3. **EN-target only** (v1); non-EN target = no-op (drafts trusted).
4. **Per-chapter character list** (no cross-chapter memory; each chapter extracts fresh).
5. **Fold character extraction into Pass-1** (one request, structured output with both translations and characters; no extra API call).
6. **Panel-grouped pronoun-name matching** (pronoun and name in the same panel =
   co-occurrence), with **same-block fallback** when panel metadata is absent/unreliable.
7. **User-editable character list** — user corrects wrong AI gender; flags recompute.

## Component overview

```
Pass-1 request (folded):
  translations:  ID|Translated Text
  characters:    NAME|GENDER           <- new structured section
                 (GENDER in {M, F, U}  U = unknown/ambiguous)

         |
         v
Parser:  splits response into translations + character list
         (characters parsed into ChapterCharacterList)
         |
         v
Per-chapter store:  ChapterCharacterList persisted as sidecar
                    (user-editable; user correction overwrites)
         |
         v
Flag decision (pure code, panel-grouped):
  for each block with a gendered EN pronoun:
    region = panel(containing block) if panelAssignment reliable
           else same block
    names = character names appearing in region (fuzzy match)
    if any name's listed gender contradicts the pronoun's gender -> FLAG
    if no name in region -> NOT flagged (no identity to contradict)
    if name matches pronoun gender -> NOT flagged
         |
         v
needsRevision set on flagged blocks
```

## Component: `ChapterCharacterList` (pure model)

New file: `app/src/main/java/eu/kanade/translation/model/ChapterCharacterList.kt`

```kotlin
package eu.kanade.translation.model

import androidx.compose.runtime.Immutable

enum class CharacterGender { MALE, FEMALE, UNKNOWN }

@Immutable
data class CharacterEntry(
    val name: String,           // normalized display form, e.g. "Yuki"
    val rawVariants: List<String>, // AI-supplied variants, e.g. ["Yuki", "Yuuki", "Yuki-kun"]
    val gender: CharacterGender,
)

@Immutable
data class ChapterCharacterList(
    val chapterId: Long,
    val entries: List<CharacterEntry>,
) {
    val byName: Map<String, CharacterEntry> = entries
        .flatMap { e -> e.rawVariants.plus(e.name).map { v -> normalizeName(v) to e } }
        .toMap()
}

/** Normalize for matching: lowercase, strip honorifics, collapse long-vowel variants. */
fun normalizeName(raw: String): String { /* see NameNormalizer */ }
```

Rationale: `rawVariants` preserves what the AI supplied (so the user sees them);
`byName` is the lookup keyed by normalized forms (so "Yuki"/"Yuuki"/"yuki-kun"
all resolve to one entry). Pure, immutable, JVM-testable.

## Component: `NameNormalizer` (pure)

```kotlin
object NameNormalizer {
    private val HONORIFICS = listOf("-san", "-kun", "-chan", "-sama", "-senpai",
        "-sensei", "-niisan", "-neesan", "-onii", "-nee")

    /** Lowercase, strip trailing honorifics, collapse u/uu and ou/oo variants. */
    fun normalize(raw: String): String {
        var s = raw.trim().lowercase()
        for (h in HONORIFICS) if (s.endsWith(h)) s = s.removeSuffix(h)
        // collapse long-vowel romanization variants: yuuki~yuki, ooki~oki
        s = s.replace("ou", "o").replace("uu", "u").replace("oo", "o")
        return s
    }

    /** Edit-distance "sounds similar" for romaji chaos (Kenji/Kenzi, Hana/Hannah). */
    fun similar(a: String, b: String, maxDistance: Int = 2): Boolean {
        // Levenshtein <= maxDistance, bounded
    }
}
```

## Component: `RevisionFlagHeuristic` (pure, identity-based)

New file: `app/src/main/java/eu/kanade/translation/translator/RevisionFlagHeuristic.kt`

```kotlin
object RevisionFlagHeuristic {
    /** 3rd-person gendered EN pronouns, word-boundary, case-insensitive. */
    private val PRONOUN_REGEX = Regex(
        """\b(he|him|his|she|her|hers|they|them|theirs)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MALE = setOf("he", "him", "his")
    private val FEMALE = setOf("she", "her", "hers")
    private val NEUTRAL = setOf("they", "them", "theirs")

    /**
     * Returns true when [draft] contains a gendered pronoun that contradicts
     * the gender of a character named in [nameRegion]. EN-target only:
     * caller gates on targetLanguage == ENGLISH before calling.
     *
     * [nameRegion] is the concatenated text of the panel containing the block
     * (or just the block, when panel metadata is absent).
     */
    fun contradictsKnownCharacter(
        draft: String,
        nameRegion: String,
        characters: ChapterCharacterList,
    ): Boolean {
        val pronounGenders = gendersIn(draft)
        if (pronounGenders.isEmpty()) return false
        val namedHere = characters.entries.filter { e ->
            // any variant of e appears in nameRegion (fuzzy)
            e.rawVariants.plus(e.name).any { v ->
                nameRegion.containsMatch(v) || NameNormalizer.similar(v, nameRegion)
            }
        }
        if (namedHere.isEmpty()) return false // no identity in region -> trust
        // flag iff pronoun gender contradicts a named character of known gender
        return namedHere.any { c ->
            c.gender != CharacterGender.UNKNOWN &&
                pronounGenders.any { p -> contradicts(p, c.gender) }
        }
    }

    private fun contradicts(pronoun: GenderBucket, character: CharacterGender): Boolean =
        when (character) {
            CharacterGender.MALE -> pronoun != GenderBucket.MALE
            CharacterGender.FEMALE -> pronoun != GenderBucket.FEMALE
            CharacterGender.UNKNOWN -> false // unknown -> never contradict
        }
    // ... gendersIn, GenderBucket enum, containsMatch helper
}
```

Key behavior:
- No pronoun → not flagged (trust).
- Pronoun but no named character in region → not flagged (no identity to contradict — avoids the density heuristic's false positives on unnamed referents).
- Pronoun matches named character's gender → not flagged.
- Pronoun contradicts named character's known gender → **flagged**.
- Character gender UNKNOWN → never causes a flag (AI unsure on first appearance).

## Wiring

### Edit 1 — Pass-1 prompt: add character section

`TranslationPrompts.kt`, `pass1SystemPrompt`: append a second structured output
section after the translation lines:

```
Then, on a new line "CHARACTERS:", list each named character appearing in this
chapter and their gender, one per line, as: NAME|GENDER where GENDER is M, F,
or U (unknown). Include name variants on the same line separated by commas if
the same character is referenced multiple ways. Example:
CHARACTERS:
Yuki,Yuuki|F
Kenji-kun|U
```

The Pass-1 OUTPUT FORMAT line is updated to describe both sections.

### Edit 2 — Pass-1 parser: split response

`TranslationPrompts.parseLine` / `ContextualResponseParser.parse`:
- Split the response at a `CHARACTERS:` marker.
- Lines above → translation results (as today, minus the `[OK]`/`[FLAG]` tag).
- Lines below → `CharacterEntry` list → `ChapterCharacterList`.

`ParsedLine.needsRevision`, the `[OK]`/`[FLAG]` extraction (TranslationPrompts.kt:32-39),
and `ContextualTranslationResult.QualityTag` all become dead → removed.

### Edit 3 — `applyBatchToChunk` no longer sets `needsRevision` from the tag

`ContextualResponseParser.kt:206` — remove the `shouldFlagForRevision` assignment.
Fresh Pass-1 lines get `needsRevision = false` (default). Flags materialize via
the flagging pass.

### Edit 4 — persist `ChapterCharacterList` per chapter

- New store sidecar (or extend the existing summary sidecar) keyed by chapterId.
- Written after Pass-1 parse, alongside page state.
- User edits overwrite the AI-supplied list.

### Edit 5 — flagging pass (post-Pass-1, manual trigger)

A `revisionFlaggingPass(store, characterList, sourceLanguage, targetLanguage)`:
1. Gate: if `targetLanguage != ENGLISH`, return (no-op).
2. For each translated, non-user-edited block:
   - Resolve its region: panel blocks if `panelAssignment` is populated/reliable,
     else just the block's own text.
   - Call `RevisionFlagHeuristic.contradictsKnownCharacter(block.draft, region, characterList)`.
   - Set `needsRevision` via the store's atomic patch.
3. Refresh eligibility so REVIEW reflects the new FLAGGED count.

**Panel-grouped with same-block fallback:** when `block.panelAssignment` is null
or `unassigned`, the region is the block's own text only. This is documented in
the existing design (design.md:154) as an acknowledged limitation of panel
metadata; the fallback keeps the heuristic functional when metadata is absent.

### Edit 6 — manual trigger UI

Add a user action (manga chapter menu and reader translation sheet, alongside
REVIEW): "Re-flag drafts for review." Invokes the flagging pass. Existing drafts
are untouched until the user runs it.

### Edit 7 — user-editable character list UI

A small editor surface (reachable from the chapter translation menu / the
confirm dialog) showing the per-chapter character list with name + gender
pickers. User correction overwrites the AI list; "Re-flag" then recomputes flags
against the corrected list. This is the audit/correction loop that turns AI
misgender-on-first-appearance into a fixable condition.

### Edit 8 — remove dead code

- `ContextualResponseParser.shouldFlagForRevision` (168-179).
- `ContextualTranslationResult.QualityTag` enum and `qualityTag` field (41, 50).
- The `when` deriving `tag` (145-149) and `qualityTag = tag` argument (155).
- `ParsedLine.needsRevision` (TranslationPrompts.kt:17) and its population.
- Update `ContextualResponseParserTest.kt` and `RevisionAdapterTest.kt`
  (`qualityTag`/`shouldFlagForRevision` assertions).

## Contracts (unchanged)

- `RevisionPlanner.isRevisionTarget` — keys on `needsRevision`. No change.
- `ALL_TRANSLATED` scope — unchanged.
- K/C/U revision protocol — unchanged.
- `RevisionConfirmation.flaggedTargetCount` — unchanged.

## Behavior summary

- Fresh Pass-1 → translations written; character list extracted + persisted;
  blocks get `needsRevision = false` (no auto-flagging).
- User runs "Re-flag drafts" → identity check flags blocks where a pronoun
  contradicts a co-occurring named character's known gender.
- User edits character list (corrects AI gender) → re-run "Re-flag" → flags
  reflect the corrected identity.
- Non-EN target → pass is a no-op.
- User-edited blocks → never touched.
- Pass-2 (revision) → unaffected.

## Tests (pure JVM)

New `RevisionFlagHeuristicTest`:
- "Yuki said he" + Yuki(F) in list + Yuki in region → flag.
- "Yuki said she" + Yuki(F) → not flagged.
- "He was tired" + Yuki(F) in list but Yuki NOT in region → not flagged (no identity).
- "He went" + Kenji(M) in region → not flagged.
- "He went" + Kenji(U, unknown) → not flagged.
- No-pronoun draft → not flagged.
- Word-boundary: "hero", "themselves", "shipwreck" → no pronoun detected.
- Case-insensitivity, fuzzy name match (Yuki/Yuuki/yuki-chan resolve to one entry).
- Empty/blank drafts.

New `NameNormalizerTest`:
- Honorific strip: "Yuki-kun" → "yuki".
- Long-vowel collapse: "Yuuki" → "yuki", "Ooki" → "oki".
- similar(): "Kenji"/"Kenzi" distance ≤ 2 → true; "Yuki"/"Hana" → false.

New `ChapterCharacterListTest`:
- byName lookup resolves variants to one entry.
- User-edit overwrite replaces entry.

Update `ContextualResponseParserTest` / `RevisionAdapterTest`:
- Remove `qualityTag` / `shouldFlagForRevision` assertions.
- Add: response with `CHARACTERS:` section parses into a `ChapterCharacterList`.

## Out of scope

- Cross-chapter character memory (decided: per-chapter).
- Separate extraction API call (decided: folded into Pass-1).
- Non-English target pronoun sets (v1 EN-only; extensible).
- Other deterministic signals (length ratio, OCR confidence, glossary) — identity only.
- Auto-flagging on batch completion (decided: manual trigger).
- K/C/U revision protocol changes.
- The unrelated CP10 device-gate defects (G1-G10) — separate effort.

## Risks

- **Pass-1 prompt bloat + attention split** — adding the CHARACTERS section makes
  Pass-1 do two jobs. Must validate on-device that translation quality does not
  regress and the character section is reliably produced. Mitigation: keep the
  character instruction compact; if quality regresses, fall back to a separate
  post-Pass-1 call (out of scope here).
- **Panel metadata unreliability** — `panelAssignment` is acknowledged unreliable
  (design.md:154). The same-block fallback keeps the heuristic functional but
  reduces coverage (adjacent-panel referents missed). Documented.
- **Name matching false negatives** — romaji chaos (Yuki/Yuuki, Kenji/Kenzi) can
  miss matches. `NameNormalizer` + `similar()` mitigate but are heuristic; user-
  editable list is the ultimate correction path.
- **AI misgender on first appearance** — AI lists "Yuki (M)" when Yuki is female;
  all "she" refs then false-flag. Mitigation: UNKNOWN gender never causes a flag
  (AI can hedge), and the user-editable list lets the user correct it.
- **EN-only** — non-EN target users get no flagging. Documented v1 limitation.
- **Per-chapter re-extraction** — no cross-chapter memory; a character
  re-misgendered in a later chapter is re-flagged (correct behavior) but the
  user must re-correct per chapter (acceptable per decision).

## Validation

- `:app:testStandardDebugUnitTest` green (existing 830 + new heuristic/normalizer/list tests).
- `git diff --check` clean.
- On-device: fresh Pass-1 on a JP chapter produces a character list; user runs
  "Re-flag drafts"; FLAGGED queue populates with identity-contradiction blocks;
  user corrects a character gender and re-runs; flags recompute correctly;
  revision runs over the queue as before.
- On-device prompt-quality check: Pass-1 translation quality with the added
  CHARACTERS section does not regress vs. the prior tag-based prompt.
