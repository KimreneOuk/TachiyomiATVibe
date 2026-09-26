package eu.kanade.translation.engines.translator.contextual

import eu.kanade.translation.persistence.artifact.ProfileGender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 *  Stage-6 slice B (design §7.4): the ENRICHED prompt assembly —
 * identity-before-gender rule ordering pinned textually, entity-id and
 * scene rendering, and the pronoun-marking rule for the rolling history.
 * The legacy prompt functions are NOT touched (pinned by
 * `TranslationPromptsTest` staying green + byte-identical diff).
 */
class TranslationPromptsProfileTest {

    private fun entry(
        id: String,
        kind: ProfileSubsetMatcher.EntryKind,
        source: String = "カイル",
        target: String = "Kail",
        gender: ProfileGender? = null,
    ) = ProfileSubsetMatcher.SubsetEntry(
        factId = id,
        kind = kind,
        sourceForm = source,
        targetForm = target,
        aliases = listOf("カイル様"),
        gender = gender,
    )

    @Test
    fun `identity rule precedes gender rule precedes unresolved fallback`() {
        val rules = TranslationPrompts.profileIdentityGenderRules()
        val resolve = rules.indexOf("Resolve the referent first")
        val profileGender = rules.indexOf("profile gender ONLY")
        val sourceEvidence = rules.indexOf("strong evidence in the current or previous source text")
        val fallback = rules.indexOf("natural singular")

        (resolve >= 0) shouldBe true
        (profileGender > resolve) shouldBe true
        (sourceEvidence > profileGender) shouldBe true
        (fallback > sourceEvidence) shouldBe true
    }

    @Test
    fun `glossary prefix carries entity ids, gender, scene context, and the no-global-replacement fence`() {
        val subset = ProfileSubsetMatcher.ProfileSubset(
            entries = listOf(
                entry("e001", ProfileSubsetMatcher.EntryKind.ENTITY),
                entry("g001", ProfileSubsetMatcher.EntryKind.GENDER, gender = ProfileGender.FEMALE),
            ),
            scenes = listOf(
                ProfileSubsetMatcher.SceneContext(
                    sceneId = "s001",
                    firstNaturalPageIndex = 0,
                    lastNaturalPageIndex = 2,
                    register = null,
                    toneFlags = listOf(),
                    narrativeContext = "opening fight",
                    participantIds = listOf("e001"),
                ),
            ),
            truncated = false,
        )
        val text = TranslationPrompts.characterAndTermSheetPrefix(subset)

        text shouldContain "CHARACTER & TERM SHEET"
        text shouldContain "[e001]"
        text shouldContain "カイル"
        text shouldContain "FEMALE"
        text shouldContain "never a global replacement rule"
        text shouldContain "Scene [s001] pages 0-2"
        text shouldContain "opening fight"

        // Scene toggle: lexical guidance is droppable, never a replacement rule.
        val withoutScenes = TranslationPrompts.characterAndTermSheetPrefix(subset, includeScenes = false)
        (withoutScenes.contains("Scene [s001]")) shouldBe false
        withoutScenes shouldContain "[e001]"
    }

    @Test
    fun `rolling prefix marks prior pronouns as translations, never gender evidence`() {
        val text = TranslationPrompts.profileAwareRollingPrefix(
            rollingPairs = "カイルは走った => she ran",
            resolvedEntityLines = listOf("[e001] カイル -> Kail"),
            unresolvedLines = listOf("[u001] 謎の男: identity unresolved"),
        )
        text shouldContain "NOT canonical gender evidence"
        text shouldContain "Already resolved characters"
        text shouldContain "[e001] カイル -> Kail"
        text shouldContain "Unresolved references"
        text shouldContain "Recent pairs"
        text shouldContain "カイルは走った => she ran"
    }

    @Test
    fun `rolling prefix is empty when there is nothing to carry`() {
        TranslationPrompts.profileAwareRollingPrefix("", emptyList(), emptyList()) shouldBe ""
    }
}
