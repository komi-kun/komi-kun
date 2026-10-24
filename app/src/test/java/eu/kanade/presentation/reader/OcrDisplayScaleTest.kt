// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The OCR overlay's display-scale policy: the slider range both OCR display sliders share
// (`SettingsOcrScreen`: text size and box size) and the percent → factor conversion `OcrOverlay`
// draws with. Pinned here because Android-only UI testing is not available and a wrong factor is a
// silent visual bug (a 0/negative box scale collapses the outline to nothing; a 1.0-vs-100 mix-up
// makes it vanish or swallow the page; a slider range wider than the drawing accepts is silently
// clamped).
// KUN <--
package eu.kanade.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

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

    @Test
    fun `source guard - both settings sliders offer exactly the shared range`() {
        // **Source guard, not a behavioural test** (no Compose harness here): the settings screen is
        // read as text, which is why it asserts a token count rather than rendering the sliders. Both
        // display sliders (text size and box size) must use `PERCENT_RANGE`; a literal range on one of
        // them is how the text slider ended up offering values its own conversion clamps away.
        val settings = File(
            "src/main/java/eu/kanade/presentation/more/settings/screen/SettingsOcrScreen.kt",
        ).readText()

        Regex("""valueRange\s*=\s*OcrDisplayScale\.PERCENT_RANGE""").findAll(settings).count() shouldBe 2
        Regex("""50\s*\.\.\s*150""").containsMatchIn(settings) shouldBe false
    }
}
// KUN <--
