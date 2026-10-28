// SPDX-License-Identifier: Apache-2.0
// KUN -->
// Where the OCR reader overlay paints, split out of `ReaderActivity`'s Compose tree so the rule is
// unit-tested without the (heavily Android-bound) reader activity: the overlay is a **page-layer**
// draw, so it must not paint while the reader chrome (top/bottom bars, side navigator) covers the
// page. The page indicator is *not* in that group: it is a page-layer HUD composed before the
// overlay, so the overlay is drawn above it and it never covers the overlay.
//
// It lives in this (Apache, GPL-free) package rather than `…reader.ocr` on purpose:
// `scripts/verify-gpl-free.sh` denies the whole `eu.kanade.tachiyomi.ui.reader.ocr` package to its
// §1.3 files and `ReaderActivity` is one of them — a same-package use needs no import, so the
// boundary stays green without loosening the script's allow-list.
// KUN <--
package eu.kanade.tachiyomi.ui.reader

object ReaderOcrOverlayPolicy {

    /**
     * true when the OCR overlay may **paint** this frame.
     *
     * [masterEnabled] is the combined dictionary/OCR opt-in and [overlayEnabled] the reader's own
     * overlay preference — both must be on, exactly as before. [menuVisible] is the reader chrome
     * state (`ReaderViewModel.State.menuVisible`, the flag that shows the bars and hides the page
     * indicator); it suppresses the overlay because the chrome's surfaces are only
     * **translucent**: `surfaceColorAtElevation(3.dp).copy(alpha = 0.9f /* dark */ / 0.95f
     * /* light */)` in `ReaderAppBars`. Box outlines and text drawn underneath would still ghost
     * through the bars. The page indicator is deliberately not part of this rule: it paints below the
     * overlay, so it can never ghost through it.
     */
    fun visible(masterEnabled: Boolean, overlayEnabled: Boolean, menuVisible: Boolean): Boolean =
        masterEnabled && overlayEnabled && !menuVisible
}
