// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.ocr

import android.graphics.PointF
import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Apache-2.0 reader→OCR seam (contract §1.2/§2.3). GPL-free: this file imports no GPL package.
 *
 * A viewer implements this so the GPL `ReaderOcrHostImpl` can map overlay taps to image-normalized
 * coordinates and know when the visible geometry changed. The GPL implementations (`SsivOcrViewport`,
 * `WebtoonOcrViewport`, `WebGpuOcrViewport`) live under the viewer sub-packages.
 */
interface OcrViewport {
    fun visiblePages(): List<ReaderPage>

    fun imageNormalizedToScreen(page: ReaderPage, rect: RectF): RectF?

    fun screenToImageNormalized(x: Float, y: Float): ImagePoint?

    fun transformVersion(): Long

    // KUN -->
    /**
     * Move the view so the looked-up block [rect] (overlay coordinates) is comfortably visible above
     * a popup that will cover the bottom [lowerFraction] of the viewport.
     *
     * Paged, zoomable backends zoom and pan so the block comes to rest just above the popup;
     * continuous backends scroll the **minimum** needed (nothing, when the block is already clear).
     * Returns true when the view was moved (so the caller can later [restoreFit]); false when the
     * block was already comfortably visible and nothing changed.
     *
     * Must be called on the main thread. Implementations must keep the caller's position so
     * [restoreFit] can put it back.
     */
    fun fitBlockAbove(rect: RectF, lowerFraction: Float): Boolean = false

    /**
     * Undo the last [fitBlockAbove]. If the user adjusted the view themselves while the popup was
     * open, their position wins and nothing moves.
     */
    fun restoreFit() = Unit
    // KUN <--
}

/** [image] is normalized to `0..1` within [page]. */
data class ImagePoint(val page: ReaderPage, val image: PointF)

/** How the current viewer lays [page] out on screen; drives inverse coordinate mapping. */
sealed interface OcrDisplayTransform {
    data object None : OcrDisplayTransform
    data class Crop(val cropRect: RectF) : OcrDisplayTransform
    data class Split(val keepLeft: Boolean) : OcrDisplayTransform
    data class SplitAndMerge(val upperIsRight: Boolean) : OcrDisplayTransform
    data class Merge(
        val partner: ReaderPage,
        val partnerW: Int,
        val partnerH: Int,
        val isLTR: Boolean,
        val centerMarginPx: Int,
    ) : OcrDisplayTransform
}
