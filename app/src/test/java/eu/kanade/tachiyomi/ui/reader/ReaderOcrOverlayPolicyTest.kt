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
}
