package eu.kanade.translation.inpainting.aot

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Guards the pure-JVM port of the legacy free-text path ([PushPullGradient]).
 * These are the same behaviours pinned in the Python prototype: the free-text
 * fill must use the LOCAL surrounding color (not the global page white), and
 * the push-pull gradient must reconstruct the hole with that local color. Only
 * masked pixels may change.
 */
class PushPullGradientTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /** 40×40 page: near-full BLUE panel with a thin white border, dark ink hole. */
    private fun bluePanelPage(): Pair<IntArray, ByteArray> {
        val w = 40
        val h = 40
        val blue = argb(70, 130, 200)
        val white = argb(255, 255, 255)
        val ink = argb(15, 15, 15)
        val px = IntArray(w * h) { white }
        for (y in 2 until 38) for (x in 2 until 38) px[y * w + x] = blue
        // dark ink inside the panel
        for (y in 16 until 24) for (x in 16 until 24) px[y * w + x] = ink
        // mask = the ink hole
        val mask = ByteArray(w * h)
        for (y in 16 until 24) for (x in 16 until 24) mask[y * w + x] = 1
        return px to mask
    }

    @Test
    fun `localRingMedian returns the surrounding panel color, not white`() {
        val (px, mask) = bluePanelPage()
        val med = PushPullGradient.localRingMedian(px, 40, 40, mask, ring = 6)
        // The annulus around the hole is the blue panel → blue, not the page white.
        red(med) shouldBeLessThan 120 // ~70
        blue(med) shouldBeGreaterThan 175 // ~200
    }

    @Test
    fun `pushPullFill fills the hole with the local color, not white`() {
        val (px, mask) = bluePanelPage()
        val bg = PushPullGradient.localRingMedian(px, 40, 40, mask, ring = 6)
        PushPullGradient.pushPullFill(px, 40, 40, mask, bg)
        // Former ink hole now matches the blue panel (anti-"stuck on white").
        val center = px[20 * 40 + 20]
        red(center) shouldBeLessThan 130
        blue(center) shouldBeGreaterThan 170
    }

    @Test
    fun `pushPullFill changes masked pixels and leaves others untouched`() {
        val (px, mask) = bluePanelPage()
        val before = px.copyOf()
        val bg = PushPullGradient.localRingMedian(px, 40, 40, mask, ring = 6)
        PushPullGradient.pushPullFill(px, 40, 40, mask, bg)
        // A masked (formerly dark-ink) pixel must have changed.
        px[20 * 40 + 20] shouldNotBe before[20 * 40 + 20]
        // A far unmasked pixel (white border) must be byte-identical.
        px[0] shouldBe before[0]
    }

    @Test
    fun `pushPullFill on an empty mask is a no-op`() {
        val w = 16
        val px = IntArray(w * w) { argb(10, 20, 30) }
        val before = px.copyOf()
        PushPullGradient.pushPullFill(px, w, w, ByteArray(w * w), argb(200, 200, 200))
        px shouldBe before
    }
}
