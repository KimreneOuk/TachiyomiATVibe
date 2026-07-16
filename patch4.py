with open('app/src/test/java/eu/kanade/translation/translator/RevisionMergerTest.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('result.retainedCount shouldBe 1', 'result.unresolvedCount shouldBe 1')
content = content.replace('result.retained[0].reason', 'result.unresolved[0].reason')
content = content.replace('result.appliedCount shouldBe 1', 'result.correctedCount shouldBe 1')
content = content.replace('result.appliedCount shouldBe 2', 'result.correctedCount shouldBe 2')
content = content.replace('result.appliedCount shouldBe 0', 'result.appliedCount shouldBe 0')

test_content = '''
    @Test
    fun identical correction clears flag but does not change draft() {
        val (live, targets, batch) = scenario(
            flags = listOf("p0" to 0),
            responses = { id -> translated(id, "draft0") }, // matches draft
        )
        val result = RevisionMerger.merge(live, batch, targets, 1L, "ch")

        result.keptCount shouldBe 1
        result.correctedCount shouldBe 0
        live.getValue("p0").blocks[0].needsRevision shouldBe false
        live.getValue("p0").blocks[0].translation shouldBe "draft0"
    }
'''

content = content.replace('''    @Test
    fun stale draft is rejected() {''', test_content + '''\n    @Test
    fun stale draft is rejected() {''')

with open('app/src/test/java/eu/kanade/translation/translator/RevisionMergerTest.kt', 'w', encoding='utf-8') as f:
    f.write(content)
