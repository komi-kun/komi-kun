// SPDX-License-Identifier: Apache-2.0
// KUN -->
// `ReaderViewModel.toggleOcrOverlay()` is Android-bound (it is a Metro-assisted ViewModel with ~30
// dependencies), so the decision it makes is tested here instead: off means "no write", on means
// "flip".
// KUN <--
package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderOcrTogglePolicyTest {

    @Test
    fun `no toggle is stored while the dictionary and OCR switch is off`() {
        // Both the hidden bottom-bar button and the two-finger gesture reach this path: off must not
        // silently enable the overlay preference for a later re-enable.
        ReaderOcrTogglePolicy.overlayAfterToggle(masterEnabled = false, currentOverlay = false) shouldBe null
        ReaderOcrTogglePolicy.overlayAfterToggle(masterEnabled = false, currentOverlay = true) shouldBe null
    }

    @Test
    fun `the overlay flips in both directions while the feature is on`() {
        ReaderOcrTogglePolicy.overlayAfterToggle(masterEnabled = true, currentOverlay = false) shouldBe true
        ReaderOcrTogglePolicy.overlayAfterToggle(masterEnabled = true, currentOverlay = true) shouldBe false
    }
}
