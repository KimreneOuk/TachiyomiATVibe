with open('app/src/test/java/eu/kanade/translation/translator/RevisionPlannerTest.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
'''import org.junit.jupiter.api.Test''',
'''import org.junit.jupiter.api.Test
import eu.kanade.translation.model.RevisionScope''')

test_content = '''
    @Test
    fun scope ALL_TRANSLATED includes non-flagged blocks() {
        val pages = linkedMapOf(
            "p0" to page(
                flagged("flagged", "draft1"),
                block("not-flagged", "draft2", needsRevision = false),
                userEdited("user-edited"),
                block("  ", "draft3", needsRevision = false) // blank text, not a target
            ),
        )
        val plan = RevisionPlanner.plan(pages, emptyMap(), 4096, scope = RevisionScope.ALL_TRANSLATED)

        plan.allTargets shouldHaveSize 2
        plan.allTargets.map { it.block.text } shouldBe listOf("flagged", "not-flagged")
    }
'''

content = content.replace('''    @Test
    fun t most 20 targets per request group() {''', test_content + '''\n    @Test
    fun t most 20 targets per request group() {''')

with open('app/src/test/java/eu/kanade/translation/translator/RevisionPlannerTest.kt', 'w', encoding='utf-8') as f:
    f.write(content)
