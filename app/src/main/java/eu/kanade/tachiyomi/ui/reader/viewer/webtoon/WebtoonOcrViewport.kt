// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// OCR coordinate adapter for the webtoon (RecyclerView) backend. Handles both the strip view
// (`WebtoonStripImageView`) and the SSIV escape hatch (`alwaysDecodeLongStripWithSSIV`); see
// plan 04 §1.5.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.RectF
import android.view.View
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.ImagePoint
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport
import eu.kanade.tachiyomi.ui.reader.viewer.pager.OcrOverlayCoords
import eu.kanade.tachiyomi.ui.reader.viewer.pager.mapForward
import eu.kanade.tachiyomi.ui.reader.viewer.pager.mapInverse
import kotlin.math.max
import kotlin.math.min

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
}
