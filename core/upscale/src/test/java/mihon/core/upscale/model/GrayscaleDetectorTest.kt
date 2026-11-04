// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.upscale.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GrayscaleDetectorTest {

    @Test
    fun `a black page and a white page are grayscale`() {
        isGrayscale(page(32, 32, 0xFF000000.toInt()), 32, 32) shouldBe true
        isGrayscale(page(32, 32, -0x1), 32, 32) shouldBe true
    }

    @Test
    fun `a colour page is not grayscale`() {
        // Saturated red-orange: |R-G| = 128.
        isGrayscale(page(32, 32, 0xFFC04030.toInt()), 32, 32) shouldBe false
    }

    @Test
    fun `a dark page with JPEG chroma noise is still grayscale`() {
        val argb = IntArray(32 * 32) { index ->
            // Neutral-ish grey around 60 with up to 10 levels of channel noise.
            when (index % 3) {
                0 -> 0xFF3C3A3E.toInt()
                1 -> 0xFF404040.toInt()
                else -> 0xFF3B4241.toInt()
            }
        }

        isGrayscale(argb, 32, 32) shouldBe true
    }

    @Test
    fun `a bright page with light chroma is not grayscale even under the noise tolerance`() {
        // |R-G| = 15 and |G-B| = 5, so only the mean-luma rule can reject it - and it must.
        isGrayscale(page(32, 32, 0xFFF0E1DC.toInt()), 32, 32) shouldBe false
    }

    @Test
    fun `padding columns outside the stride are ignored`() {
        val width = 8
        val height = 8
        val stride = 12
        val argb = IntArray(stride * height) { 0xFFFF0000.toInt() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                argb[y * stride + x] = 0xFF808080.toInt()
            }
        }

        isGrayscale(argb, width, height, stride) shouldBe true
    }

    @Test
    fun `a known flag short-circuits the sampler`() {
        val colour = page(32, 32, 0xFFC04030.toInt())

        isGrayscale(knownGrayscale = true, argb = colour, width = 32, height = 32) shouldBe true
        isGrayscale(knownGrayscale = false, argb = page(32, 32, -0x1), width = 32, height = 32) shouldBe false
        isGrayscale(knownGrayscale = null, argb = colour, width = 32, height = 32) shouldBe false
    }

    private companion object {
        fun page(width: Int, height: Int, argb: Int): IntArray = IntArray(width * height) { argb }
    }
}
