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
