// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// `ReaderActivity` (an Activity that binds the reader ViewModels and a Compose tree) cannot be
// constructed in a JVM unit test, so the visibility rule it applies is tested through the pure
// [ReaderOcrOverlayPolicy] instead. The **sibling order** itself — the overlay composed after
// `ContentOverlay` and before `AppBars` — is a Compose-tree fact and is pinned separately by
// `ReaderOcrOverlayWiringTest`, which reads the call site; this file covers the rule only.
// KUN <--
package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderOcrOverlayPolicyTest {

    @Test
    fun `the overlay paints nothing while the reader chrome is up`() {
        // The bars are translucent (alpha 0.9/0.95), so boxes left painting under them would still
        // show through the top bar, bottom bar and side navigator.
        ReaderOcrOverlayPolicy.visible(
            masterEnabled = true,
            overlayEnabled = true,
            menuVisible = true,
        ) shouldBe false
    }

    @Test
    fun `the overlay paints on the page layer with both switches on and the chrome hidden`() {
        ReaderOcrOverlayPolicy.visible(
            masterEnabled = true,
            overlayEnabled = true,
            menuVisible = false,
        ) shouldBe true
    }

    @Test
    fun `either switch hides the overlay in both chrome states`() {
        ReaderOcrOverlayPolicy.visible(masterEnabled = false, overlayEnabled = true, menuVisible = false) shouldBe false
        ReaderOcrOverlayPolicy.visible(masterEnabled = false, overlayEnabled = true, menuVisible = true) shouldBe false
        ReaderOcrOverlayPolicy.visible(masterEnabled = true, overlayEnabled = false, menuVisible = false) shouldBe false
        ReaderOcrOverlayPolicy.visible(masterEnabled = true, overlayEnabled = false, menuVisible = true) shouldBe false
    }

    @Test
    fun `a tap is only hit-tested while the overlay is painted`() {
        // Overlay off (the OCR button pressed a second time, or the two-finger toggle): the boxes
        // are not drawn, so a tap must not be consumed by a hidden block — it has to reach the
        // viewer and turn the page / toggle the menu as usual.
        ReaderOcrOverlayPolicy.acceptsLookup(
            masterEnabled = true,
            overlayEnabled = false,
            menuVisible = false,
        ) shouldBe false
        // Both switches on, chrome hidden: the normal lookup path.
        ReaderOcrOverlayPolicy.acceptsLookup(
            masterEnabled = true,
            overlayEnabled = true,
            menuVisible = false,
        ) shouldBe true
        // Feature off: nothing is hit-tested, whatever the (hidden) overlay preference says.
        ReaderOcrOverlayPolicy.acceptsLookup(
            masterEnabled = false,
            overlayEnabled = true,
            menuVisible = false,
        ) shouldBe false
        ReaderOcrOverlayPolicy.acceptsLookup(
            masterEnabled = false,
            overlayEnabled = false,
            menuVisible = false,
        ) shouldBe false
    }

    /**
     * The bug this pins: the hit test used to ignore the chrome, so with the bars up a tap on a
     * block was consumed by an overlay painted at `alpha = 0f` — no lookup the user could see, and
     * no page turn or menu toggle either, i.e. a dead reader while the menu was open.
     */
    @Test
    fun `a tap falls through to the page controls while the reader chrome is up`() {
        ReaderOcrOverlayPolicy.acceptsLookup(
            masterEnabled = true,
            overlayEnabled = true,
            menuVisible = true,
        ) shouldBe false
        // The gate is the paint rule, so the two can never disagree on any input.
        for (master in listOf(true, false)) {
            for (overlay in listOf(true, false)) {
                for (menu in listOf(true, false)) {
                    ReaderOcrOverlayPolicy.acceptsLookup(master, overlay, menu) shouldBe
                        ReaderOcrOverlayPolicy.visible(master, overlay, menu)
                }
            }
        }
    }
}
