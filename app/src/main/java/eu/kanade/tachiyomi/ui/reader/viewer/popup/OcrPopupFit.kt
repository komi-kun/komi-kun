// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Shared math for the "keep the looked-up block visible above the dictionary popup" behaviour
// (OcrViewport.fitBlockAbove). Kept here, beside the other OCR coordinate helpers, so the pager,
// webtoon and WebGPU adapters agree on what "comfortably visible" and "the upper half" mean.
//
// Deliberately free of Android types: the adapters pass the block's centre/height and top/bottom
// from their own `RectF`s, which keeps this rule unit-testable on the JVM.
package eu.kanade.tachiyomi.ui.reader.viewer.popup

/** Where the looked-up block should come to rest so the popup can occupy the lower region. */
internal data class PopupFitTarget(
    /** Top of the region a block may occupy (a small margin off the screen top). */
    val safeTop: Float,
    /** Bottom of that region: just above where the popup will be drawn. */
    val safeBottom: Float,
    /** The block's centre should end up here; equals its current centre when nothing is needed. */
    val targetCenterY: Float,
)

/**
 * The vertical band a block may occupy above a popup covering the bottom [lowerFraction] of an
 * [overlayHeight]-tall viewport, and the centre the block should move to.
 *
 * The popup anchors to the block's bottom edge, so putting the block's bottom at [safeBottom] is
 * what makes the popup start just below it - text in the upper region, popup in the lower one.
 */
internal fun popupFitTarget(
    blockCenterY: Float,
    blockHeight: Float,
    overlayHeight: Float,
    lowerFraction: Float,
): PopupFitTarget {
    val popupTop = overlayHeight * (1f - lowerFraction.coerceIn(0f, 1f))
    val margin = 0.02f * overlayHeight
    val safeTop = margin
    val safeBottom = (popupTop - margin).coerceAtLeast(safeTop)
    val half = blockHeight / 2f
    val minCenter = safeTop + half
    val maxCenter = (safeBottom - half).coerceAtLeast(minCenter)
    return PopupFitTarget(
        safeTop = safeTop,
        safeBottom = safeBottom,
        targetCenterY = blockCenterY.coerceIn(minCenter, maxCenter),
    )
}

/** True when `[blockTop]..[blockBottom]` already sits entirely inside the safe band. */
internal fun isComfortablyVisible(blockTop: Float, blockBottom: Float, target: PopupFitTarget): Boolean =
    blockTop >= target.safeTop && blockBottom <= target.safeBottom

/**
 * Which side of the looked-up block the popup should occupy.
 *
 * The popup is anchored to the block, so its natural position is **below** the block (the block
 * stays where the reader was already looking). That is only usable when the popup's height fits
 * between the block's bottom and the viewport bottom; a block low on the screen leaves no room
 * there, and forcing it below would either cover the block or run off the screen. In that case the
 * popup flips **above** the block, which needs no page move at all - the alternative used to be a
 * large zoom towards the block, which for a block at the very bottom has no valid target and pushed
 * the block off-screen (the popup was then auto-dismissed). [Partial] is the last resort for a block
 * so tall that neither side holds the whole popup: the popup is put on the roomier side and clamped
 * into the viewport, partially overlapping the block.
 */
internal enum class PopupSide { Below, Above, Partial }

/**
 * The [PopupSide] for a block, from the block's on-screen edges, the viewport height and the
 * popup's own height. A small margin keeps the popup off the exact edge.
 */
internal fun popupSide(
    blockTop: Float,
    blockBottom: Float,
    overlayHeight: Float,
    popupHeight: Float,
): PopupSide {
    if (overlayHeight <= 0f) return PopupSide.Below
    val margin = 0.02f * overlayHeight
    return when {
        overlayHeight - blockBottom >= popupHeight + margin -> PopupSide.Below
        blockTop >= popupHeight + margin -> PopupSide.Above
        else -> PopupSide.Partial
    }
}

/**
 * The popup's top edge for a block, given the popup's own height, so the caller can offset it
 * without re-deriving the rule. Mirrors the fit: [PopupSide.Below] starts at the block's bottom,
 * [PopupSide.Above] ends at the block's top, and [PopupSide.Partial] takes whichever side has more
 * room (the caller still clamps into the viewport).
 */
internal fun popupTopFor(
    blockTop: Float,
    blockBottom: Float,
    overlayHeight: Float,
    popupHeight: Float,
): Float = when (popupSide(blockTop, blockBottom, overlayHeight, popupHeight)) {
    PopupSide.Below -> blockBottom
    PopupSide.Above -> blockTop - popupHeight
    PopupSide.Partial ->
        if (blockTop > overlayHeight - blockBottom) blockTop - popupHeight else blockBottom
}
// KUN <--
