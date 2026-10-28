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

/**
 * How a forced OCR re-run (`ReaderOcrHost.redoOcr`) ended, so the reader can report it honestly
 * instead of always claiming success. It sits beside [ReaderOcrOverlayPolicy], in this package, for
 * the same reason the policy does: `ReaderActivity` is a GPL-free seam file and may not name a type
 * from the denied `…ui.reader.ocr` package.
 */
enum class RedoOcrResult {
    /** At least one page was re-recognised and its blocks cached. */
    UPDATED,

    /** There was at least one page, but not one of them could be re-OCR'd. */
    FAILED,

    /**
     * There was nothing to re-run at all: no page in the chapter, the manga row is gone, or the
     * combined dictionary/OCR opt-in is off so the re-run never starts.
     */
    NOTHING_TO_REDO,
}

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

    /**
     * KUN -->
     * true when a tap/long press may be **hit-tested** against the OCR blocks — i.e. when the OCR
     * host may consume it for a dictionary lookup.
     *
     * The rule mirrors exactly the two preferences the hit test is allowed to depend on — the
     * combined dictionary/OCR opt-in and the reader's own overlay toggle — and nothing else: an
     * overlay that is off (the feature switched off, or the toggle pressed a second time) must not
     * swallow the gesture, or the page does not turn and the menu does not toggle — which reads as a
     * dead reader.
     *
     * [menuVisible] is deliberately **not** part of this rule, even though [visible] paints nothing
     * while the chrome is up (`ReaderActivity` composes the overlay with `alpha = 0f`): with the
     * chrome visible the blocks are still hit-tested **by design**, so a tap while the bars are up
     * still starts a lookup on the block it lands on instead of being swallowed. Only the painting is
     * suppressed; the hit test is not.
     */
    fun acceptsLookup(masterEnabled: Boolean, overlayEnabled: Boolean): Boolean =
        masterEnabled && overlayEnabled
    // KUN <--
}
