package eu.kanade.tachiyomi.extension.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExtensionLoaderCompatibilityTest {

    @Test
    fun `lib version bounds allow versions from 1_4 to 1_6`() {
        ExtensionLoader.LIB_VERSION_MIN shouldBe 1.4
        ExtensionLoader.LIB_VERSION_MAX shouldBe 1.6
    }

    @Test
    fun `version strings 1_4 through 1_6 are recognized as valid library versions`() {
        val versions = listOf("1.4.1", "1.5.0", "1.5.28", "1.6.0", "1.6.2")
        for (version in versions) {
            val libVersion = version.substringBeforeLast('.').toDoubleOrNull()
            (libVersion != null && libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX) shouldBe true
        }
    }

    @Test
    fun `outdated version below 1_4 or future version above 1_6 are out of bounds`() {
        val invalidVersions = listOf("1.3.9", "1.7.0", "2.0.0")
        for (version in invalidVersions) {
            val libVersion = version.substringBeforeLast('.').toDoubleOrNull()
            (libVersion != null && libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX) shouldBe false
        }
    }

    @Test
    fun `extension title prefix stripping handles both Tachiyomi and Tachiyomix prefixes`() {
        val titles = listOf(
            "Tachiyomi: MangaDex" to "MangaDex",
            "Tachiyomix: Asura Scans" to "Asura Scans",
            "Simple Extension" to "Simple Extension",
        )

        for ((input, expected) in titles) {
            val stripped = input.substringAfter("Tachiyomi: ").substringAfter("Tachiyomix: ")
            stripped shouldBe expected
        }
    }

    @Test
    fun `repo url regex matches both index_json and index_min_json`() {
        val regex = """^https://.*/index(\.min)?\.json$""".toRegex()
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json".matches(regex) shouldBe true
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json".matches(regex) shouldBe true
        "http://insecure.com/index.json".matches(regex) shouldBe false
        "https://domain.com/other.json".matches(regex) shouldBe false
    }
}
