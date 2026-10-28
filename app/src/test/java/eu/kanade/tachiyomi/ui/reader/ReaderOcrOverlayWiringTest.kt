// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level sibling-order guard — not a behavioural test.**
//
// The overlay's *visibility* rule is a pure function, tested behaviourally in
// `ReaderOcrOverlayPolicyTest`. What no JVM test in this module can prove is *where* `ReaderActivity`
// composes the overlay: that class is an Android `Activity` binding ViewModels and a Compose tree, and
// there is no Compose UI test harness here. So this reads the call site, the way
// `OcrPolicySourceWiringTest` guards the other OCR call sites.
//
// The defect it pins was real: the overlay used to be the **last** sibling of the root `Box`, i.e.
// drawn over the whole reader UI instead of under the chrome. In a `Box` the draw order is the
// composition order of the non-composed children, so the ordering facts below *are* the layering:
// `ContentOverlay` (brightness/colour) first, the OCR overlay next, `AppBars` last.
//
// Residual gap: source text proves the call order in the file, not the rendered z-order — a nesting
// or modifier change elsewhere could still reorder what is drawn.
// KUN <--
package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class ReaderOcrOverlayWiringTest {

    private fun readerActivity(): String =
        File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt").readText()

    /** [needle]'s index in [source], failing loudly when it is absent (a rename must not pass). */
    private fun indexOfCall(source: String, needle: String): Int {
        val at = source.indexOf(needle)
        if (at < 0) fail("missing call site in ReaderActivity.kt: $needle")
        return at
    }

    @Test
    fun `the OCR overlay is composed once, inside the root box, under the reader chrome`() {
        val source = readerActivity()

        // Exactly one overlay call site: a second one — the shape this replaced was the same call as
        // the Box's last sibling — would paint over the chrome again, and no ordering assert below
        // could see which of the two is which.
        Regex.fromLiteral("readerOcrHost.Overlay(").findAll(source).count() shouldBe 1

        val rootBox = indexOfCall(source, "Box(modifier = Modifier.fillMaxSize())")
        val contentOverlay = indexOfCall(source, "ContentOverlay(state = state)")
        val overlay = indexOfCall(source, "readerOcrHost.Overlay(")
        val appBars = indexOfCall(source, "AppBars(state = state)")

        // Root Box first: the overlay must be a child of the page-layer Box, not a sibling of it.
        assertTrue(
            rootBox < overlay,
            "the OCR overlay must be inside the root Box (box=$rootBox, overlay=$overlay)",
        )
        // Inside the Box, after the brightness/colour overlay, so it is drawn on top of the page...
        assertTrue(
            contentOverlay < overlay,
            "the OCR overlay must be composed after ContentOverlay " +
                "(contentOverlay=$contentOverlay, overlay=$overlay)",
        )
        // ...and before the chrome, so the top/bottom bars and the side navigator paint over it. This
        // is the assertion that fails if `AppBars` is ever moved back above the OCR block.
        assertTrue(
            overlay < appBars,
            "the OCR overlay must be composed before AppBars, or the chrome stops covering it " +
                "(overlay=$overlay, appBars=$appBars)",
        )
    }
}
