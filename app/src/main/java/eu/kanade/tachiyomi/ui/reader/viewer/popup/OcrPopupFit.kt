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
// KUN <--
