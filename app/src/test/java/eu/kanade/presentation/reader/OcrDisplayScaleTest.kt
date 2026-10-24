// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The OCR overlay's display-scale policy: the slider range the settings screen offers and the
// percent → factor conversion `OcrOverlay` draws with. Pinned here because Android-only UI testing is
// not available and a wrong factor is a silent visual bug (a 0/negative box scale collapses the
// outline to nothing; a 1.0-vs-100 mix-up makes it vanish or swallow the page).
// KUN <--
package eu.kanade.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OcrDisplayScaleTest {

    @Test
    fun `the box-size slider offers 50 to 150 percent`() {
        OcrDisplayScale.PERCENT_RANGE shouldBe 50..150
        OcrDisplayScale.DEFAULT_PERCENT shouldBe 100
        (OcrDisplayScale.DEFAULT_PERCENT in OcrDisplayScale.PERCENT_RANGE) shouldBe true
    }

    @Test
    fun `a percent becomes a factor with 100 as the neutral value`() {
        OcrDisplayScale.boxScaleFactor(100) shouldBe 1f
        OcrDisplayScale.boxScaleFactor(50) shouldBe 0.5f
        OcrDisplayScale.boxScaleFactor(150) shouldBe 1.5f
        // The slider's endpoints are exactly the accepted factors: nothing the UI offers is clamped
        // on the drawing side.
        OcrDisplayScale.boxScaleFactor(OcrDisplayScale.PERCENT_RANGE.first) shouldBe 0.5f
        OcrDisplayScale.boxScaleFactor(OcrDisplayScale.PERCENT_RANGE.last) shouldBe 1.5f
    }

    @Test
    fun `a degenerate box percent is floored instead of collapsing the outline`() {
        // Not reachable from the slider, but a corrupt/legacy preference must not draw at 0.
        OcrDisplayScale.boxScaleFactor(0) shouldBe 0.05f
        OcrDisplayScale.boxScaleFactor(-20) shouldBe 0.05f
    }

    @Test
    fun `the text factor is clamped to the legible range`() {
        OcrDisplayScale.textScaleFactor(100) shouldBe 1f
        OcrDisplayScale.textScaleFactor(50) shouldBe 0.5f
        OcrDisplayScale.textScaleFactor(150) shouldBe 1.5f
        // Out-of-range values are clamped, never rendered unreadably.
        OcrDisplayScale.textScaleFactor(1) shouldBe 0.5f
        OcrDisplayScale.textScaleFactor(1000) shouldBe 1.5f
    }
}
// KUN <--
