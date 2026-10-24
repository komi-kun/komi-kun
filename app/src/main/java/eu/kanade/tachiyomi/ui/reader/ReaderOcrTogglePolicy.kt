// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The reader overlay toggle decision, split out of `ReaderViewModel.toggleOcrOverlay()` so the
// "off means no write" rule is unit-tested without constructing the (heavily Android-bound) reader
// view model.
// KUN <--
package eu.kanade.tachiyomi.ui.reader

object ReaderOcrTogglePolicy {

    /**
     * The overlay value a toggle request must store, or `null` when the request is a no-op.
     *
     * While the combined dictionary/OCR opt-in is off, nothing may flip the overlay preference —
     * not the (hidden) bottom-bar button, not the two-finger gesture — so a later re-enable does not
     * silently turn the overlay on behind the user's back.
     */
    fun overlayAfterToggle(masterEnabled: Boolean, currentOverlay: Boolean): Boolean? =
        if (masterEnabled) !currentOverlay else null
}
