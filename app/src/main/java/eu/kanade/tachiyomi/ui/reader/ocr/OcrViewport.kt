// SPDX-License-Identifier: Apache-2.0
package eu.kanade.tachiyomi.ui.reader.ocr

import android.graphics.PointF
import android.graphics.RectF
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Apache-2.0 reader→OCR seam (contract §1.2/§2.3). GPL-free: this file imports no GPL package.
 *
 * A viewer implements this so the GPL `ReaderOcrSessionImpl` can map overlay taps to
 * image-normalized coordinates and know when the visible geometry changed. The GPL implementations
 * (`SsivOcrViewport`, `WebtoonOcrViewport`, `WebGpuOcrViewport`) live under the viewer sub-packages.
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

    // KUN -->
    /**
     * The sheet was **border-cropped** before display, so the displayed sheet is [window] of the
     * page's own image and [inner] says how the pre-crop sheet differs from the page.
     *
     * Crop is the viewer's last step - SSIV and the webtoon strip decoder both crop whatever sheet
     * they are handed - so it wraps [inner] rather than being one of several choices: a merged or
     * split sheet is cropped too, and the window is measured against *that* sheet.
     *
     * The combinations the viewers actually produce, i.e. every pairing of crop with the layouts
     * above: `Crop(None)` for a plain paged or webtoon page, `Crop(Merge(...))` for a merged double
     * page, `Crop(Split(...))` for a split half and `Crop(SplitAndMerge(...))` for a wide webtoon
     * page. `Merge` and `Split` never co-occur (one viewer is one or the other), so a flat choice of
     * transform would not have been enough only because crop applies *on top of* whichever one it is.
     */
    data class Crop(
        val window: OcrCropWindow,
        val inner: OcrDisplayTransform = None,
    ) : OcrDisplayTransform {
        /** Already cropped: a second window cannot apply to the sheet the first one produced. */
        override fun croppedBy(window: OcrCropWindow?): OcrDisplayTransform = this
    }
    // KUN <--

    data class Split(val keepLeft: Boolean) : OcrDisplayTransform
    data class SplitAndMerge(val upperIsRight: Boolean) : OcrDisplayTransform
    data class Merge(
        val partner: ReaderPage,
        val partnerW: Int,
        val partnerH: Int,
        val isLTR: Boolean,
        val centerMarginPx: Int,
    ) : OcrDisplayTransform

    // KUN -->
    /**
     * This transform wrapped by the border crop [window] the image pipeline applied to its sheet
     * (`null` when it applied none). The result maps page-normalized block coordinates into the
     * displayed *cropped* sheet, which is the space `SubsamplingScaleImageView.sWidth`/`sHeight` is
     * measured in - so the un-cropped mapping the overlay used before is exactly `croppedBy(null)`.
     */
    fun croppedBy(window: OcrCropWindow?): OcrDisplayTransform =
        if (window == null || !window.isUsable) this else Crop(window, this)
    // KUN <--
}

// KUN -->
/**
 * The part of a page's **uncropped** image a viewer actually displays, as fractions of that image
 * (`0..1`): a border crop removes the margins, leaving the displayed sheet scaled up.
 *
 * Block coordinates are normalized to the full image (OCR runs on the raw page), so mapping them
 * onto the displayed sheet is `(x - left) / width` per axis - the crop is an offset *and* a scale,
 * and the two axes have their own factor.
 */
data class OcrCropWindow(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left

    val height: Float get() = bottom - top

    /** False for a window with no area, or one that keeps the whole image (a crop that trimmed nothing). */
    val isUsable: Boolean
        get() = width > 0f && height > 0f &&
            !(left <= 0f && top <= 0f && right >= 1f && bottom >= 1f)

    /** Uncropped-image normalized → displayed-sheet normalized. */
    fun mapX(x: Float): Float = (x - left) / width

    fun mapY(y: Float): Float = (y - top) / height

    /** Displayed-sheet normalized → uncropped-image normalized, i.e. the inverse of [mapX]/[mapY]. */
    fun unmapX(u: Float): Float = u * width + left

    fun unmapY(v: Float): Float = v * height + top

    companion object {
        /**
         * [left]/[top]/[width]/[height] in pixels of a [sheetWidth]×[sheetHeight] sheet - the shape
         * every crop routine in the decode path reports. `null` when nothing was cropped (or the
         * rect is degenerate), so an uncropped page keeps the plain mapping.
         */
        fun ofPixels(
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            sheetWidth: Int,
            sheetHeight: Int,
        ): OcrCropWindow? {
            if (width <= 0 || height <= 0 || sheetWidth <= 0 || sheetHeight <= 0) return null
            if (left <= 0 && top <= 0 && width >= sheetWidth && height >= sheetHeight) return null
            return OcrCropWindow(
                left = left.toFloat() / sheetWidth,
                top = top.toFloat() / sheetHeight,
                right = (left + width).toFloat() / sheetWidth,
                bottom = (top + height).toFloat() / sheetHeight,
            )
        }
    }
}
// KUN --
