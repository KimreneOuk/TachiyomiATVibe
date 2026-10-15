package eu.kanade.translation.architecture

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Package-boundary enforcement for the translation module: production code
 * under the `engines` and `presentation` source trees must not depend on the
 * `pipeline` tree.
 *
 * The scanner is deliberately narrow: it masks comments and literal regions
 * of each source file, then flags (a) `import eu.kanade.translation.pipeline...`
 * declarations (ordinary, `as`-aliased, or wildcard) and (b) any remaining
 * executable fully-qualified `eu.kanade.translation.pipeline` reference. It
 * enforces only this edge — legitimate persistence, model, and domain
 * dependencies are out of scope, and no total package order is imposed.
 *
 * Known limitation, stated rather than hidden: Kotlin string templates hide
 * code from the scanner. A `${ ... }` expression is masked together with its
 * surrounding string literal, so a pipeline reference that only appears
 * inside a template expression is not detected. References in plain code and
 * imports are covered. (KDoc note: block comments nest in Kotlin, so this
 * documentation deliberately avoids writing glob patterns that contain an
 * opening comment delimiter.)
 */
class TranslationPackageBoundaryTest {

    private val srcRoot: File = requireSrcRoot()

    private fun requireSrcRoot(): File {
        val path = System.getProperty(SRC_MAIN_JAVA_PROPERTY)
            ?: error(
                "Missing system property '$SRC_MAIN_JAVA_PROPERTY'; it is provided by " +
                    "app/build.gradle.kts Test.configureEach and must not be guessed from the " +
                    "working directory.",
            )
        val root = File(path)
        check(root.isDirectory) { "Source root '$path' from '$SRC_MAIN_JAVA_PROPERTY' is not a directory." }
        return root
    }

    private data class Offense(val relativePath: String, val line: Int, val text: String)

    companion object {
        private const val SRC_MAIN_JAVA_PROPERTY = "translation.boundary.srcMainJava"
        private const val PIPELINE_PREFIX = "eu.kanade.translation.pipeline"
        private val IMPORT_VIOLATION =
            Regex("""(?m)^import\s+eu\.kanade\.translation\.pipeline(?:\.[A-Za-z_][\w.]*|\.\*)?(?:\s+as\s+\w+)?[ \t]*$""")
        private val QUALIFIED_REFERENCE = Regex("""\beu\.kanade\.translation\.pipeline\s*(\.|::)""")

        /**
         * Returns the lines of [content] that violate the engines/presentation
         * → pipeline ban, with comments and literal regions masked out first.
         * Shared by the production scan and the fixture assertions below so
         * the fixtures exercise exactly the code that scans production.
         */
        fun pipelineDependenciesIn(content: String): List<String> =
            maskNonCode(content).lines().withIndex().mapNotNull { (index, line) ->
                if (IMPORT_VIOLATION.containsMatchIn(line) || QUALIFIED_REFERENCE.containsMatchIn(line)) {
                    "line ${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }

        /**
         * Replaces comments, string literals, raw strings, and char literals
         * with spaces (newlines preserved) so only executable code remains.
         * Raw strings are masked before ordinary strings; escaped quotes and
         * Java-style char literals such as '\'' do not terminate a mask early.
         */
        private fun maskNonCode(content: String): String {
            val masked = StringBuilder(content.length)
            var i = 0
            val n = content.length
            var lineComment = false
            var blockComment = false
            var rawString = false
            var stringLiteral = false
            var charLiteral = false
            while (i < n) {
                val c = content[i]
                val next = if (i + 1 < n) content[i + 1] else '\u0000'
                when {
                    lineComment -> {
                        if (c == '\n') {
                            lineComment = false
                            masked.append(c)
                        } else {
                            masked.append(' ')
                        }
                        i++
                    }
                    blockComment -> {
                        if (c == '*' && next == '/') {
                            blockComment = false
                            masked.append("  ")
                            i += 2
                        } else {
                            masked.append(if (c == '\n') '\n' else ' ')
                            i++
                        }
                    }
                    rawString -> {
                        if (c == '"' && i + 2 < n && content[i + 1] == '"' && content[i + 2] == '"') {
                            rawString = false
                            masked.append("   ")
                            i += 3
                        } else {
                            masked.append(if (c == '\n') '\n' else ' ')
                            i++
                        }
                    }
                    stringLiteral -> {
                        when {
                            c == '\\' -> {
                                masked.append("  ")
                                i += 2
                            }
                            c == '"' -> {
                                stringLiteral = false
                                masked.append(c)
                                i++
                            }
                            else -> {
                                masked.append(if (c == '\n') ' ' else ' ')
                                i++
                            }
                        }
                    }
                    charLiteral -> {
                        when {
                            c == '\\' -> {
                                masked.append("  ")
                                i += 2
                            }
                            c == '\'' -> {
                                charLiteral = false
                                masked.append(c)
                                i++
                            }
                            else -> {
                                masked.append(' ')
                                i++
                            }
                        }
                    }
                    c == '/' && next == '/' -> {
                        lineComment = true
                        masked.append("  ")
                        i += 2
                    }
                    c == '/' && next == '*' -> {
                        blockComment = true
                        masked.append("  ")
                        i += 2
                    }
                    c == '"' && i + 2 < n && content[i + 1] == '"' && content[i + 2] == '"' -> {
                        rawString = true
                        masked.append("   ")
                        i += 3
                    }
                    c == '"' -> {
                        stringLiteral = true
                        masked.append(c)
                        i++
                    }
                    c == '\'' -> {
                        charLiteral = true
                        masked.append(c)
                        i++
                    }
                    else -> {
                        masked.append(c)
                        i++
                    }
                }
            }
            return masked.toString()
        }
    }

    @Test
    fun `engines and presentation sources do not depend on pipeline`() {
        val offenses = mutableListOf<Offense>()
        for (dir in listOf("eu/kanade/translation/engines", "eu/kanade/translation/presentation")) {
            val root = srcRoot.resolve(dir)
            check(root.isDirectory) { "Expected production package directory is missing: ${root.path}" }
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val relative = "${file.relativeTo(srcRoot).invariantSeparatorsPath}"
                    pipelineDependenciesIn(file.readText()).forEach { detail ->
                        offenses +=
                            Offense(
                                relative,
                                detail.removePrefix("line ").substringBefore(':').trim().toInt(),
                                detail,
                            )
                    }
                }
        }
        assertTrue(offenses.isEmpty()) {
            "engines/** and presentation/** must not depend on pipeline/**:\n" +
                offenses.joinToString("\n") { "  ${it.relativePath} ${it.text}" }
        }
    }

    @Test
    fun `scanner flags forbidden imports and qualified references in every syntax`() {
        val flagged = listOf(
            // ordinary import
            "import eu.kanade.translation.pipeline.TranslationPipeline",
            // aliased import
            "import eu.kanade.translation.pipeline.TranslationPipeline as TP",
            // wildcard import
            "import eu.kanade.translation.pipeline.execution.*",
            // executable qualified reference in Kotlin code
            "val x = eu.kanade.translation.pipeline.TranslationPipeline.SINGLE_PAGE_PARTIAL_MAX_RETRIES",
            // same in a Java file body
            "Object o = eu.kanade.translation.pipeline.TranslationPipeline.class;",
            // callable reference syntax
            "val f = eu.kanade.translation.pipeline::CleanedPublication",
        )
        flagged.forEach { snippet ->
            assertTrue(pipelineDependenciesIn(snippet).isNotEmpty()) { "Must be flagged: $snippet" }
        }
        // multi-line document where only one line violates
        val fileBody = """
            package com.example

            import eu.kanade.translation.model.Translation
            import eu.kanade.translation.pipeline.TranslationPipeline as TP

            fun use(t: Translation) = TP
        """.trimIndent()
        assertTrue(pipelineDependenciesIn(fileBody) == listOf("line 4: import eu.kanade.translation.pipeline.TranslationPipeline as TP")) {
            "Expected exactly the aliased import to be flagged, got: ${pipelineDependenciesIn(fileBody)}"
        }
    }

    @Test
    fun `scanner ignores comments strings raw strings and char literals`() {
        val tripleQuote = "\"" + "\"" + "\""
        val clean = """
            // import eu.kanade.translation.pipeline.TranslationPipeline
            /* eu.kanade.translation.pipeline.TranslationPipeline */
            /**
             * KDoc link [eu.kanade.translation.pipeline.TranslationPipeline] is documentation.
             */
            val inString = "eu.kanade.translation.pipeline.TranslationPipeline"
            val inRaw = RAWTRIPLE
                eu.kanade.translation.pipeline.TranslationPipeline
            RAWTRIPLE
            val quote = '"'
            val escaped = "not a pipeline ref: \" eu.kanade.translation.pipeline.x\""
            val legit = eu.kanade.translation.model.Translation
        """.trimIndent().replace("RAWTRIPLE", tripleQuote)
        assertTrue(pipelineDependenciesIn(clean).isEmpty()) {
            "Documentation and literal text must not be flagged, got: ${pipelineDependenciesIn(clean)}"
        }
    }
}
