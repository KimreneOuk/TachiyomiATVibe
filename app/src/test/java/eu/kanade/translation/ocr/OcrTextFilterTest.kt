package eu.kanade.translation.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrTextFilterTest {

    @Test
    fun `isUsable returns true for text with at least one letter`() {
        OcrTextFilter.isUsable("abc") shouldBe true
    }

    @Test
    fun `isUsable returns false for pure numbers`() {
        OcrTextFilter.isUsable("123") shouldBe false
    }

    @Test
    fun `isUsable returns false for fullwidth digits`() {
        OcrTextFilter.isUsable("０１２") shouldBe false
    }

    @Test
    fun `isUsable returns true for CJK character`() {
        OcrTextFilter.isUsable("え") shouldBe true
    }

    @Test
    fun `isUsable returns true for alphanumeric mix`() {
        OcrTextFilter.isUsable("A1") shouldBe true
    }

    @Test
    fun `pickUsable prefers the usable text over unusable`() {
        OcrTextFilter.pickUsable("hello", "123") shouldBe "hello"
    }

    @Test
    fun `pickUsable picks the usable one regardless of order`() {
        OcrTextFilter.pickUsable("123", "hello") shouldBe "hello"
    }

    @Test
    fun `pickUsable picks the longer of two usable texts`() {
        OcrTextFilter.pickUsable("hi", "hello") shouldBe "hello"
    }

    @Test
    fun `pickUsable returns empty when neither is usable`() {
        OcrTextFilter.pickUsable("123", "456") shouldBe ""
    }
}
