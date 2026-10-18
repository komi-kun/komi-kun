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
import eu.kanade.tachiyomi.ui.reader.viewer.popup.isComfortablyVisible
import eu.kanade.tachiyomi.ui.reader.viewer.popup.popupFitTarget
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    // KUN -->
    /**
     * Paged: zoom + pan so the block clears the popup; continuous: scroll the minimum needed.
     * Which one applies follows the viewer's own state (see [continuousState]).
     */
    override fun fitBlockAbove(rect: RectF, lowerFraction: Float): Boolean {
        val overlayHeight = overlayView.height.toFloat()
        if (overlayHeight <= 0f) return false
        val cs = continuousState
        return if (cs != null) fitContinuous(cs, rect, overlayHeight, lowerFraction) else fitPaged(rect, overlayHeight, lowerFraction)
    }

    override fun restoreFit() {
        when (val fit = session) {
            is ContinuousFit -> {
                session = null
                restoreContinuous(fit)
            }

            is PagedFit -> {
                session = null
                restorePaged(fit)
            }

            null -> Unit
        }
    }

    private fun fitContinuous(
        cs: ImageViewerContinuousState,
        rect: RectF,
        overlayHeight: Float,
        lowerFraction: Float,
    ): Boolean {
        val target = popupFitTarget(rect.centerY(), rect.height(), overlayHeight, lowerFraction)
        if (isComfortablyVisible(rect.top, rect.bottom, target)) return false
        val existing = session as? ContinuousFit
        val fit = if (existing != null && existing.state === cs) {
            existing
        } else {
            ContinuousFit(cs, cs.savePosition()).also { session = it }
        }
        val delta = rect.centerY() - target.targetCenterY
        if (delta == 0f) return false
        cs.scrollBy(delta)
        fit.settledDocumentY = cs.documentY
        fit.settledScale = cs.scale
        fit.settledOffsetX = cs.offsetX
        return true
    }

    private fun restoreContinuous(fit: ContinuousFit) {
        val cs = fit.state
        if (cs.scope == null) return
        // The user scrolled/zoomed since our move: keep their position.
        val settled = fit.settledDocumentY ?: return
        if (abs(cs.documentY - settled) > 0.5f) return
        if (abs(cs.scale - (fit.settledScale ?: cs.scale)) > 1e-3f) return
        if (abs(cs.offsetX - (fit.settledOffsetX ?: cs.offsetX)) > 1e-4f) return
        cs.restorePosition(fit.pre)
    }

    private fun fitPaged(rect: RectF, overlayHeight: Float, lowerFraction: Float): Boolean {
        val target = popupFitTarget(rect.centerY(), rect.height(), overlayHeight, lowerFraction)
        if (isComfortablyVisible(rect.top, rect.bottom, target)) return false
        val surfaceH = state.height.toFloat()
        if (surfaceH <= 0f) return false
        val page = imagePageAt(rect.centerX(), rect.centerY()) ?: return false
        val existing = session as? PagedFit
        val fit = if (existing != null && existing.page === page) {
            existing
        } else {
            PagedFit(page, page.scale, page.x, page.y).also { session = it }
        }

        val off = OcrOverlayCoords.offsetOf(viewer.pager, overlayView)
        val scaleNow = page.scale
        if (scaleNow <= 0f) return false
        // The block's row, in surface-normalised units, moves as `0.5 + scale * (y + k)`; `k` folds
        // every scale-independent term, so it can be recovered from the current state.
        val blockNormNow = (rect.centerY() - off.y) / surfaceH
        val k = (blockNormNow - 0.5f) / scaleNow - page.y
        val targetNorm = (target.targetCenterY - off.y) / surfaceH

        // Grow the scale until the page can be panned far enough for the block to reach the target
        // row (a page at its fit scale has no slack to pan).
        val maxScale = page.maxScale
        var scale = scaleNow
        var y = (targetNorm - 0.5f) / scale - k
        for (i in 0 until MAX_PAN_SEARCH_STEPS) {
            val lo = page.minY(scale)
            val hi = page.maxY(scale)
            if (y in lo..hi) break
            if (scale >= maxScale) break
            scale = min(maxScale, scale * 1.25f)
            y = (targetNorm - 0.5f) / scale - k
        }
        y = y.coerceIn(page.minY(scale), page.maxY(scale))
        if (scale <= 0f) return false

        val targetX = page.x
        page.animateTo(targetX = targetX, targetY = y, targetScale = scale)
        // animateTo settles exactly at the values passed (it clamps only its own intermediates).
        fit.settledScale = scale
        fit.settledX = targetX
        fit.settledY = y
        return true
    }

    private fun restorePaged(fit: PagedFit) {
        val page = fit.page
        if (page.destroyed || page.parent == null) return
        val settledScale = fit.settledScale ?: return
        val settledX = fit.settledX ?: return
        val settledY = fit.settledY ?: return
        if (abs(page.scale - settledScale) > 0.01f * settledScale + 1e-3f) return
        if (abs(page.x - settledX) > 0.01f || abs(page.y - settledY) > 0.01f) return
        page.animateTo(targetX = fit.preX, targetY = fit.preY, targetScale = fit.preScale)
    }

    /** The leaf [ImagePage] the point at overlay ([x], [y]) falls on, if any. */
    private fun imagePageAt(x: Float, y: Float): ImagePage? {
        val surfaceW = state.width.toFloat()
        val surfaceH = state.height.toFloat()
        if (surfaceW <= 0f || surfaceH <= 0f) return null
        val off = OcrOverlayCoords.offsetOf(viewer.pager, overlayView)
        val nx = (x - off.x) / surfaceW
        val ny = (y - off.y) / surfaceH
        for (imagePage in state.visiblePages) {
            val leaves = if (imagePage is ImagePage.ImageSpread) {
                listOfNotNull(imagePage.left, imagePage.right)
            } else {
                listOf(imagePage)
            }
            for (leaf in leaves) {
                val r = state.rectOf(leaf) ?: continue
                if (nx in r[0]..r[2] && ny in r[1]..r[3]) return leaf
            }
        }
        return null
    }

    private class PagedFit(
        val page: ImagePage,
        val preScale: Float,
        val preX: Float,
        val preY: Float,
        var settledScale: Float? = null,
        var settledX: Float? = null,
        var settledY: Float? = null,
    )

    private class ContinuousFit(
        val state: ImageViewerContinuousState,
        val pre: ImageViewerContinuousState.ContinuousPosition,
        var settledDocumentY: Float? = null,
        var settledScale: Float? = null,
        var settledOffsetX: Float? = null,
    )

    private var session: Any? = null

    private companion object {
        const val MAX_PAN_SEARCH_STEPS = 16
    }
    // KUN <--

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
