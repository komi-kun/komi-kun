// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// OCR coordinate adapter for the webtoon (RecyclerView) backend. Handles both the strip view
// (`WebtoonStripImageView`) and the SSIV escape hatch (`alwaysDecodeLongStripWithSSIV`); see
// plan 04 §1.5.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.ImagePoint
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport
import eu.kanade.tachiyomi.ui.reader.viewer.pager.OcrOverlayCoords
import eu.kanade.tachiyomi.ui.reader.viewer.pager.mapForward
import eu.kanade.tachiyomi.ui.reader.viewer.pager.mapInverse
import eu.kanade.tachiyomi.ui.reader.viewer.popup.isComfortablyVisible
import eu.kanade.tachiyomi.ui.reader.viewer.popup.popupFitTarget
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * GPL [OcrViewport] adapter for the webtoon backend (plan 04 §1.5).
 *
 * Owned by [WebtoonViewer]. `visiblePages()` returns the pages whose holders are laid out in the
 * recycler viewport; the mapping is rebuilt per frame from the live holder geometry, so a recycled
 * or reloaded strip is picked up automatically. Main-thread only.
 */
class WebtoonOcrViewport(
    private val viewer: WebtoonViewer,
) : OcrViewport {

    private val activity: ReaderActivity get() = viewer.activity
    private val overlayView: View get() = activity.composeOverlayRoot

    override fun visiblePages(): List<ReaderPage> =
        viewer.ocrVisibleHolders().mapNotNull { it.ocrPage() }

    override fun imageNormalizedToScreen(page: ReaderPage, rect: RectF): RectF? {
        val holder = viewer.ocrHolderFor(page) ?: return null
        val frame = holder.ocrFrame()

        holder.ocrFrame().ocrSubsamplingImageView()?.let { ssiv ->
            if (!ssiv.isReady || ssiv.sWidth <= 0 || ssiv.sHeight <= 0) return null
            val display = holder.ocrDisplayTransform().mapForward(
                page = page,
                rect = rect,
                owner = holder.ocrPage() ?: return null,
                partner = null,
                ownerW = 0,
                ownerH = 0,
            )
            val p1 = ssiv.sourceToViewCoord(display.left * ssiv.sWidth, display.top * ssiv.sHeight) ?: return null
            val p2 = ssiv.sourceToViewCoord(display.right * ssiv.sWidth, display.bottom * ssiv.sHeight) ?: return null
            val off = OcrOverlayCoords.offsetOf(ssiv, overlayView)
            // SSIV view coordinates are a layout space: the ancestor zoom has to be multiplied in.
            val scale = ancestorScaleOf(ssiv)
            return RectF(
                off.x + min(p1.x, p2.x) * scale.x,
                off.y + min(p1.y, p2.y) * scale.y,
                off.x + max(p1.x, p2.x) * scale.x,
                off.y + max(p1.y, p2.y) * scale.y,
            )
        }

        val strip = frame.ocrStripImageView() ?: return null
        if (strip.width <= 0 || strip.height <= 0) return null
        val display = holder.ocrDisplayTransform().mapForward(
            page = page,
            rect = rect,
            owner = holder.ocrPage() ?: return null,
            partner = null,
            ownerW = 0,
            ownerH = 0,
        )
        val off = OcrOverlayCoords.offsetOf(strip, overlayView)
        // `off` tracks the zoomed ancestors' translation but not their scale (a matrix-mapped point
        // carries one, an extent re-derived from a layout size the other), so the extent needs it.
        val scale = ancestorScaleOf(strip)
        val screen = projectImageNormalizedToScreen(
            block = OcrRect(display.left, display.top, display.right, display.bottom),
            sheetWidth = strip.width.toFloat(),
            sheetHeight = strip.height.toFloat(),
            originX = off.x,
            originY = off.y,
            scaleX = scale.x,
            scaleY = scale.y,
        )
        return RectF(screen.left, screen.top, screen.right, screen.bottom)
    }

    override fun screenToImageNormalized(x: Float, y: Float): ImagePoint? {
        val recycler = viewer.recycler
        val recyclerOffset = OcrOverlayCoords.offsetOf(recycler, overlayView)
        val recyclerScale = ancestorScaleOf(recycler)
        // `findChildViewUnder` takes the recycler's **own** (layout) coordinates, i.e. before the
        // zoom its ancestors apply, so the scale is undone as well as the offset - the same divisor
        // the draw path multiplies by.
        val recyclerLocal = overlayToViewLocal(
            x = x,
            y = y,
            originX = recyclerOffset.x,
            originY = recyclerOffset.y,
            scaleX = recyclerScale.x,
            scaleY = recyclerScale.y,
        )
        val child = recycler.findChildViewUnder(recyclerLocal.x, recyclerLocal.y) ?: return null
        val position = recycler.getChildAdapterPosition(child)
        val holder = viewer.ocrHolderAt(position) ?: return null
        val page = holder.ocrPage() ?: return null
        val frame = holder.ocrFrame()

        frame.ocrSubsamplingImageView()?.let { ssiv ->
            if (!ssiv.isReady || ssiv.sWidth <= 0 || ssiv.sHeight <= 0) return null
            val off = OcrOverlayCoords.offsetOf(ssiv, overlayView)
            // `viewToSourceCoord` is in SSIV view space, so undo the ancestor zoom as well.
            val ssivScale = ancestorScaleOf(ssiv)
            val local = overlayToViewLocal(x, y, off.x, off.y, ssivScale.x, ssivScale.y)
            val source = ssiv.viewToSourceCoord(local.x, local.y) ?: return null
            return holder.ocrDisplayTransform().mapInverse(
                u = source.x / ssiv.sWidth,
                v = source.y / ssiv.sHeight,
                owner = page,
                partner = null,
                ownerW = 0,
                ownerH = 0,
            )
        }

        val strip = frame.ocrStripImageView() ?: return null
        if (strip.width <= 0 || strip.height <= 0) return null
        val off = OcrOverlayCoords.offsetOf(strip, overlayView)
        val scale = ancestorScaleOf(strip)
        val normalized = projectScreenToImageNormalized(
            x = x,
            y = y,
            sheetWidth = strip.width.toFloat(),
            sheetHeight = strip.height.toFloat(),
            originX = off.x,
            originY = off.y,
            scaleX = scale.x,
            scaleY = scale.y,
        ) ?: return null
        return holder.ocrDisplayTransform().mapInverse(
            u = normalized.x,
            v = normalized.y,
            owner = page,
            partner = null,
            ownerW = 0,
            ownerH = 0,
        )
    }

    override fun transformVersion(): Long {
        // Scroll position drives the mapping; key the token on the recycler's scroll offset plus
        // the visible holders' identities so a page turn or reload invalidates it.
        val recycler = viewer.recycler
        val scroll = recycler.computeVerticalScrollOffset().toLong()
        val holders = viewer.ocrVisibleHolders()
        var token = scroll * 31L + holders.sumOf { it.hashCode().toLong() }
        // A pinch moves neither the scroll offset nor the holder set: the zoom is a layer transform
        // (`WebtoonRecyclerView.setScaleRate`), and the pan it comes with is the view's `x`/`y`
        // translation, not its scroll position. Both change what the overlay must project, so both
        // belong in the token - otherwise the boxes keep the pre-zoom projection for the whole
        // gesture.
        token = token * 31L + recycler.scaleX.toRawBits().toLong()
        token = token * 31L + recycler.scaleY.toRawBits().toLong()
        token = token * 31L + recycler.x.toRawBits().toLong()
        token = token * 31L + recycler.y.toRawBits().toLong()
        return token
    }

    /**
     * The zoom [view] inherits from its ancestors, as seen from the overlay's coordinates.
     *
     * In the webtoon reader the zoom is a layer transform on the [RecyclerView]
     * (`WebtoonRecyclerView.setScaleRate`: `scaleX`/`scaleY` plus an `x`/`y` pan), so the strip's own
     * `scaleX` stays 1 and the factor has to be accumulated up the parent chain. The walk stops at the
     * first ancestor the [overlayView] shares: that one scales the overlay too, so it cancels out
     * between the two coordinate spaces.
     */
    private fun ancestorScaleOf(view: View): PointF {
        val shared = generateSequence<View>(overlayView) { it.parent as? View }.toHashSet()
        var scaleX = 1f
        var scaleY = 1f
        var current: View? = view
        while (current != null && current !in shared) {
            scaleX *= current.scaleX
            scaleY *= current.scaleY
            current = current.parent as? View
        }
        return PointF(scaleX, scaleY)
    }

    // KUN -->
    /**
     * Nudges the strip so the looked-up block clears the popup. The webtoon reader is already a
     * continuous scroll, so a block that is comfortably visible is left alone; otherwise it scrolls
     * the **minimum** distance needed to bring the block above the popup - never a zoom.
     */
    override fun fitBlockAbove(rect: RectF, lowerFraction: Float): Boolean {
        val overlayHeight = overlayView.height.toFloat()
        if (overlayHeight <= 0f) return false
        val target = popupFitTarget(rect.centerY(), rect.height(), overlayHeight, lowerFraction)
        if (isComfortablyVisible(rect.top, rect.bottom, target)) return false

        val recycler = viewer.recycler
        val delta = (rect.centerY() - target.targetCenterY).roundToInt()
        if (delta == 0) return false
        val preOffset = recycler.computeVerticalScrollOffset()
        val fit = session ?: WebtoonFitSession(recycler, preOffset).also { session = it }
        recycler.scrollBy(0, delta)
        // Record where we actually landed so a later manual scroll can be told apart from ours.
        fit.settledOffset = recycler.computeVerticalScrollOffset()
        return true
    }

    override fun restoreFit() {
        val fit = session ?: return
        session = null
        val recycler = fit.recycler
        if (recycler.parent == null) return
        val settled = fit.settledOffset ?: return
        // The user scrolled since our move: keep their position.
        if (recycler.computeVerticalScrollOffset() != settled) return
        recycler.scrollBy(0, fit.preOffset - settled)
    }

    private class WebtoonFitSession(
        val recycler: RecyclerView,
        val preOffset: Int,
        var settledOffset: Int? = null,
    )

    private var session: WebtoonFitSession? = null
    // KUN <--
}
