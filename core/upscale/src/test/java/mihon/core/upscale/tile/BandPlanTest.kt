// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.upscale.tile

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class BandPlanTest {

    @Test
    fun `a page under the cap is a single band`() {
        val plan = planBands(width = 100, height = 100, maxOutputPixels = 10_000_000, scale = 2)

        plan shouldNotBe null
        plan!!.isSinglePass shouldBe true
        plan.bands shouldHaveSize 1
        plan.bands.single().originY shouldBe 0
        plan.bands.single().height shouldBe 100
        plan.bands.single().outputOffsetY shouldBe 0
        plan.outputWidth shouldBe 200
        plan.outputHeight shouldBe 200
    }

    @Test
    fun `an 800x5685 page over a 4MP cap becomes 5 bands that cover the height exactly once`() {
        val plan = planBands(
            width = 800,
            height = 5685,
            maxOutputPixels = 4_000_000,
            scale = 2,
            overlap = 16,
        )

        plan shouldNotBe null
        val bands = plan!!.bands
        bands shouldHaveSize 5
        plan.outputWidth shouldBe 1600

        // Exact planned ranges: 5685 rows over 5 even bands of 1137.
        bands.map { it.uniqueTop to it.uniqueBottom } shouldBe listOf(
            0 to 1137,
            1137 to 2274,
            2274 to 3411,
            3411 to 4548,
            4548 to 5685,
        )
        bands.first().originY shouldBe 0
        bands.first().overlapTop shouldBe 0
        bands.last().overlapBottom shouldBe 0

        for (band in bands) {
            band.outputOffsetY shouldBe band.originY * 2
            band.overlapTop shouldBeLessThanOrEqual 16
            band.overlapBottom shouldBeLessThanOrEqual 16
            // The cap applies to the band's own (unique) output, not the context it borrows.
            val uniquePixels = (band.uniqueBottom - band.uniqueTop).toLong() * 2 * plan.outputWidth
            uniquePixels shouldBeLessThanOrEqual 4_000_000L
        }
    }

    @Test
    fun `a cap that cannot hold one input row yields no plan`() {
        // Output is 1600 px wide, so even one input row needs 3200 output pixels.
        planBands(width = 800, height = 5685, maxOutputPixels = 1599, scale = 2) shouldBe null
        planBands(width = 800, height = 5685, maxOutputPixels = 0, scale = 2) shouldBe null
    }

    @Test
    fun `bands overlap by the requested context at the shared seams`() {
        val plan = planBands(
            width = 800,
            height = 5685,
            maxOutputPixels = 4_000_000,
            scale = 2,
            overlap = 16,
        )!!

        for (index in 1 until plan.bands.size) {
            val previous = plan.bands[index - 1]
            val band = plan.bands[index]
            // This band reads 16 rows above its own first row...
            band.overlapTop shouldBe 16
            band.originY shouldBe band.uniqueTop - 16
            // ...and the previous one read 16 rows below its last, so both hold the seam.
            previous.overlapBottom shouldBe 16
            previous.originY + previous.height shouldBe previous.uniqueBottom + 16
            band.originY shouldBeLessThanOrEqual previous.uniqueBottom
        }
    }
}
