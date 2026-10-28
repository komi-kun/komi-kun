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

    /**
     * Every entry point that consumes a tap through the OCR host — the outside-tap detector's
     * `hitsBlock`, the long press and the single tap — must ask the **policy** whether a lookup may
     * run at all, and none of them may re-derive "is the overlay on" locally. A hidden overlay that
     * still hit-tests eats the gesture: the page does not turn and the menu does not toggle.
     *
     * Source-level, like the rest of this file: the three call sites live in an Android `Activity`
     * and the rule itself is covered behaviourally in [ReaderOcrOverlayPolicyTest].
     */
    @Test
    fun `every OCR tap entry point asks the overlay policy before hit-testing`() {
        val source = readerActivity()

        val gates = Regex.fromLiteral("ReaderOcrOverlayPolicy.acceptsLookup(").findAll(source).count()
        val hitTests = listOf(
            "readerOcrHost.hitsBlock(",
            "readerOcrHost.onPageLongPress(",
            "readerOcrHost.onPageSingleTap(",
        ).sumOf { Regex.fromLiteral(it).findAll(source).count() }

        hitTests shouldBe 3
        // Every call that can consume a gesture is preceded by the policy decision: a site that
        // re-derives "is the overlay on" locally would leave the counts unequal.
        gates shouldBe hitTests
    }

    /**
     * The viewport the hit test maps taps through is recorded by `Overlay`, so it must be dropped
     * when the overlay leaves composition — `detach()` only runs when the reader goes away, and is
     * skipped across a configuration change.
     */
    @Test
    fun `the host drops the recorded viewport when the overlay leaves composition`() {
        val source = File(
            "src/main/java/eu/kanade/tachiyomi/ui/reader/ocr/impl/ReaderOcrHostImpl.kt",
        ).readText()

        assertTrue(source.contains("DisposableEffect"), "the overlay must dispose its recorded viewport")
        assertTrue(
            source.contains("this@ReaderOcrHostImpl.viewport = null"),
            "the disposal must clear the recorded viewport",
        )
    }
}
