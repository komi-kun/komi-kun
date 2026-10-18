// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// OCR coordinate adapter for the webtoon (RecyclerView) backend. Handles both the strip view
// (`WebtoonStripImageView`) and the SSIV escape hatch (`alwaysDecodeLongStripWithSSIV`); see
// plan 04 §1.5.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

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
    private val overlayView: View get() = activity.binding.composeOverlay

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
            return RectF(
                min(p1.x, p2.x) + off.x,
                min(p1.y, p2.y) + off.y,
                max(p1.x, p2.x) + off.x,
                max(p1.y, p2.y) + off.y,
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
        return RectF(
            off.x + display.left * strip.width,
            off.y + display.top * strip.height,
            off.x + display.right * strip.width,
            off.y + display.bottom * strip.height,
        )
    }

    override fun screenToImageNormalized(x: Float, y: Float): ImagePoint? {
        val recycler = viewer.recycler
        val recyclerOffset = OcrOverlayCoords.offsetOf(recycler, overlayView)
        val child = recycler.findChildViewUnder(x - recyclerOffset.x, y - recyclerOffset.y) ?: return null
        val position = recycler.getChildAdapterPosition(child)
        val holder = viewer.ocrHolderAt(position) ?: return null
        val page = holder.ocrPage() ?: return null
        val frame = holder.ocrFrame()

        frame.ocrSubsamplingImageView()?.let { ssiv ->
            if (!ssiv.isReady || ssiv.sWidth <= 0 || ssiv.sHeight <= 0) return null
            val off = OcrOverlayCoords.offsetOf(ssiv, overlayView)
            val source = ssiv.viewToSourceCoord(x - off.x, y - off.y) ?: return null
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
        val u = (x - off.x) / strip.width
        val v = (y - off.y) / strip.height
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null
        return holder.ocrDisplayTransform().mapInverse(
            u = u,
            v = v,
            owner = page,
            partner = null,
            ownerW = 0,
            ownerH = 0,
        )
    }

    override fun transformVersion(): Long {
        // Scroll position drives the mapping; key the token on the recycler's scroll offset plus
        // the visible holders' identities so a page turn or reload invalidates it.
        val scroll = viewer.recycler.computeVerticalScrollOffset().toLong()
        val holders = viewer.ocrVisibleHolders()
        return scroll * 31L + holders.sumOf { it.hashCode().toLong() }
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
