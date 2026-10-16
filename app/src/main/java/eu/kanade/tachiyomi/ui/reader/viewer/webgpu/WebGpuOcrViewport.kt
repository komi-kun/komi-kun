// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// OCR coordinate adapter for the WebGPU viewer (paged and continuous). Uses the fork accessors
// `ImageViewerState.visiblePages`/`rectOf`/`transformVersion` and, for continuous,
// `ImageViewerContinuousState.scrollOffsetY`/`getPageSlotHeight`; see plan 04 §1.6–§1.7 and
// contract §2.6.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.ImagePoint
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport
import eu.kanade.tachiyomi.ui.reader.viewer.pager.OcrOverlayCoords
import kotlin.math.max

/**
 * GPL [OcrViewport] adapter for the WebGPU backend (plan 04 §1.6 paged, §1.7 continuous).
 *
 * The fork accessors make this a thin wrapper: a page maps to its viewport-normalized rect and the
 * inverse finds the page whose rect contains the point. The continuous viewer lays pages out in
 * document space instead, so its branch rebuilds the slot offsets from [ImageViewerContinuousState].
 * `OcrDisplayTransform` is always identity here - the fork's placement already folds trim/crop and
 * `WebGpuRenderer.offsetX/Y`. Main-thread only.
 */
class WebGpuOcrViewport(
    private val viewer: WebGpuViewer,
) : OcrViewport {

    private val state get() = viewer.pager.state
    private val overlayView: View get() = viewer.activity.binding.composeOverlay

    private val continuousState: ImageViewerContinuousState?
        get() = state as? ImageViewerContinuousState

    override fun visiblePages(): List<ReaderPage> =
        state.visiblePages.flatMap { viewer.ocrReaderPagesFor(it) }

    override fun imageNormalizedToScreen(page: ReaderPage, rect: RectF): RectF? =
        if (continuousState != null) continuousToScreen(page, rect) else pagedToScreen(page, rect)

    override fun screenToImageNormalized(x: Float, y: Float): ImagePoint? =
        continuousState?.let { continuousFromScreen(it, x, y) } ?: pagedFromScreen(x, y)

    override fun transformVersion(): Long = state.transformVersion

    // region paged

    private fun imagePageFor(page: ReaderPage): ImagePage? {
        for (imagePage in state.visiblePages) {
            if (imagePage is ImagePage.ImageSpread) {
                if (viewer.ocrReaderPagesFor(imagePage.left).any { it === page }) return imagePage.left
                if (viewer.ocrReaderPagesFor(imagePage.right).any { it === page }) return imagePage.right
            } else if (viewer.ocrReaderPagesFor(imagePage).any { it === page }) {
                return imagePage
            }
        }
        return null
    }

    private fun pagedToScreen(page: ReaderPage, rect: RectF): RectF? {
        val imagePage = imagePageFor(page) ?: return null
        val r = state.rectOf(imagePage) ?: return null
        val surfaceW = state.width.toFloat()
        val surfaceH = state.height.toFloat()
        if (surfaceW <= 0f || surfaceH <= 0f) return null
        val off = OcrOverlayCoords.offsetOf(viewer.pager, overlayView)
        val x0 = r[0]
        val y0 = r[1]
        val x1 = r[2]
        val y1 = r[3]
        return RectF(
            off.x + (x0 + rect.left * (x1 - x0)) * surfaceW,
            off.y + (y0 + rect.top * (y1 - y0)) * surfaceH,
            off.x + (x0 + rect.right * (x1 - x0)) * surfaceW,
            off.y + (y0 + rect.bottom * (y1 - y0)) * surfaceH,
        )
    }

    private fun pagedFromScreen(x: Float, y: Float): ImagePoint? {
        val surfaceW = state.width.toFloat()
        val surfaceH = state.height.toFloat()
        if (surfaceW <= 0f || surfaceH <= 0f) return null
        val off = OcrOverlayCoords.offsetOf(viewer.pager, overlayView)
        val nx = (x - off.x) / surfaceW
        val ny = (y - off.y) / surfaceH
        for (imagePage in state.visiblePages) {
            val r = state.rectOf(imagePage) ?: continue
            val w = r[2] - r[0]
            val h = r[3] - r[1]
            if (w <= 0f || h <= 0f) continue
            val u = (nx - r[0]) / w
            val v = (ny - r[1]) / h
            if (u in 0f..1f && v in 0f..1f) {
                val page = pickPage(imagePage, u) ?: continue
                return ImagePoint(page, PointF(u, v))
            }
        }
        return null
    }

    /** Resolves which [ReaderPage] of a spread the tap landed on (left half vs right half). */
    private fun pickPage(imagePage: ImagePage, u: Float): ReaderPage? {
        if (imagePage !is ImagePage.ImageSpread) return viewer.ocrReaderPagesFor(imagePage).firstOrNull()
        val pages = if (u < 0.5f) {
            viewer.ocrReaderPagesFor(imagePage.left)
        } else {
            viewer.ocrReaderPagesFor(imagePage.right)
        }
        return pages.firstOrNull()
            ?: viewer.ocrReaderPagesFor(imagePage).firstOrNull()
    }

    // endregion

    // region continuous

    private fun continuousToScreen(page: ReaderPage, rect: RectF): RectF? {
        val cs = continuousState ?: return null
        val imagePage = state.visiblePages.firstOrNull {
            viewer.ocrReaderPagesFor(it).any { p -> p === page }
        } ?: return null
        val index = indexFor(page, cs) ?: return null
        val scale = cs.scale
        val widthPx = scale * cs.width
        val heightPx = scale * cs.getPageHeight(imagePage)
        if (widthPx <= 0f || heightPx <= 0f) return null
        val off = OcrOverlayCoords.offsetOf(viewer.pager, overlayView)
        val left = off.x + cs.width * (0.5f * (1f - scale) + scale * cs.offsetX)
        val top = off.y + scale * (slotOffset(cs, index) - cs.scrollOffsetY)
        return RectF(
            left + rect.left * widthPx,
            top + rect.top * heightPx,
            left + rect.right * widthPx,
            top + rect.bottom * heightPx,
        )
    }

    private fun continuousFromScreen(cs: ImageViewerContinuousState, x: Float, y: Float): ImagePoint? {
        val scale = cs.scale
        val widthPx = scale * cs.width
        if (widthPx <= 0f) return null
        val off = OcrOverlayCoords.offsetOf(viewer.pager, overlayView)
        val left = off.x + cs.width * (0.5f * (1f - scale) + scale * cs.offsetX)
        val u = (x - left) / widthPx
        if (u < 0f || u > 1f) return null
        for (imagePage in state.visiblePages) {
            val page = viewer.ocrReaderPagesFor(imagePage).firstOrNull() ?: continue
            val index = indexFor(page, cs) ?: continue
            val heightPx = scale * cs.getPageHeight(imagePage)
            if (heightPx <= 0f) continue
            val top = off.y + scale * (slotOffset(cs, index) - cs.scrollOffsetY)
            val v = (y - top) / heightPx
            if (v in 0f..1f) return ImagePoint(page, PointF(u, v))
        }
        return null
    }

    /** Document-space top of the page [index] steps from page 0, in zoom-1 slot pixels. */
    private fun slotOffset(cs: ImageViewerContinuousState, index: Int): Float {
        var sum = 0f
        if (index >= 0) {
            for (i in 0 until index) {
                val page = state.getPage(i) ?: break
                sum += cs.getPageSlotHeight(page)
            }
        } else {
            for (i in -1 downTo index) {
                val page = state.getPage(i) ?: break
                sum -= cs.getPageSlotHeight(page)
            }
        }
        return sum
    }

    private fun indexFor(page: ReaderPage, cs: ImageViewerContinuousState): Int? {
        fun matches(index: Int): Boolean =
            state.getPage(index)?.let { viewer.ocrReaderPagesFor(it).any { p -> p === page } } == true

        if (matches(0)) return 0
        val limit = max(12, max(cs.pagesBelow + 2, cs.pagesAbove + 2))
        for (d in 1..limit) {
            if (matches(d)) return d
            if (matches(-d)) return -d
        }
        return null
    }

    // endregion
}
