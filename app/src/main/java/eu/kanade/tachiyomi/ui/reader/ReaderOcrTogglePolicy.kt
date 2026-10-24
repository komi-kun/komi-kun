// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The reader overlay toggle decision, split out of `ReaderViewModel.toggleOcrOverlay()` so the
// "off means no write" rule is unit-tested without constructing the (heavily Android-bound) reader
// view model.
//
// This file is a **registered** GPL-free seam file (contract §1.3 and `scripts/verify-gpl-free.sh`):
// `ReaderViewModel` (Apache, GPL-free) calls it from the same package, so an import scan cannot see
// the dependency — keep the Apache header and never import a GPL package here.
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
