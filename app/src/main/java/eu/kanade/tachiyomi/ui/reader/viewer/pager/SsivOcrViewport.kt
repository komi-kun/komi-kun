// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// OCR coordinate adapters for the pager (SubsamplingScaleImageView) and the Compose overlay.
// Ported/adapted from Chimahon (GPL-3.0-or-later) reader OCR mapping; see plan 04 §1.4.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.ocr.ImagePoint
import eu.kanade.tachiyomi.ui.reader.ocr.OcrDisplayTransform
import eu.kanade.tachiyomi.ui.reader.ocr.OcrViewport
import kotlin.math.max
import kotlin.math.min

/**
 * Maps a point between a viewer child view's local coordinates and the reader's Compose overlay
 * coordinate space (plan 04 §1.3). Both are window-relative; the only difference is each view's
 * position in the window, so the offset is subtracted once.
 */
internal object OcrOverlayCoords {
    fun offsetOf(view: View, overlay: View): PointF {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val overlayLocation = IntArray(2)
        overlay.getLocationInWindow(overlayLocation)
        return PointF(
            (location[0] - overlayLocation[0]).toFloat(),
            (location[1] - overlayLocation[1]).toFloat(),
        )
    }
}

/**
 * Applies an [OcrDisplayTransform] to a page-normalized rect, producing the rect in the displayed
 * sheet's normalized space (the space `SubsamplingScaleImageView.sWidth`/`sHeight` is measured in).
 */
internal fun OcrDisplayTransform.mapForward(
    page: ReaderPage,
    rect: RectF,
    owner: ReaderPage,
    partner: ReaderPage?,
    ownerW: Int,
    ownerH: Int,
): RectF {
    return when (this) {
        OcrDisplayTransform.None -> RectF(rect)

        is OcrDisplayTransform.Crop -> {
            val w = cropRect.width()
            val h = cropRect.height()
            if (w <= 0f || h <= 0f) {
                RectF(rect)
            } else {
                RectF(
                    (rect.left - cropRect.left) / w,
                    (rect.top - cropRect.top) / h,
                    (rect.right - cropRect.left) / w,
                    (rect.bottom - cropRect.top) / h,
                )
            }
        }

        is OcrDisplayTransform.Split -> {
            val offset = if (keepLeft) 0f else 0.5f
            RectF((rect.left - offset) * 2f, rect.top, (rect.right - offset) * 2f, rect.bottom)
        }

        is OcrDisplayTransform.SplitAndMerge -> {
            val centerX = (rect.left + rect.right) / 2f
            val isInRight = centerX >= 0.5f
            val (xOffset, yOffset) = splitAndMergeOffsets(isInRight, upperIsRight)
            RectF(
                ((rect.left - xOffset) * 2f).coerceIn(0f, 1f),
                (yOffset + rect.top * 0.5f).coerceIn(0f, 1f),
                ((rect.right - xOffset) * 2f).coerceIn(0f, 1f),
                (yOffset + rect.bottom * 0.5f).coerceIn(0f, 1f),
            )
        }

        is OcrDisplayTransform.Merge -> {
            // Both the owner and the partner map into one merged sheet. The merged canvas is
            // owner + centerMargin + partner wide and max(ownerH, partnerH) tall, each vertically
            // centred - see `OcrCoordinateMapper.mapToMerged`.
            if (page != owner && page != partner) return RectF(rect)
            val isOwner = page == owner
            val pageW = if (isOwner) ownerW else partnerW
            val pageH = if (isOwner) ownerH else partnerH
            val totalW = (ownerW + centerMarginPx + partnerW).toFloat()
            val totalH = max(ownerH, partnerH).toFloat()
            if (totalW <= 0f || totalH <= 0f || pageW <= 0) return RectF(rect)
            val leftW = if (isLTR) ownerW else partnerW
            val isLeft = if (isLTR) isOwner else !isOwner
            val xOffset = if (isLeft) 0f else leftW + centerMarginPx.toFloat()
            val yOffset = (totalH - pageH) / 2f
            RectF(
                ((rect.left * pageW + xOffset) / totalW).coerceIn(0f, 1f),
                ((rect.top * pageH + yOffset) / totalH).coerceIn(0f, 1f),
                ((rect.right * pageW + xOffset) / totalW).coerceIn(0f, 1f),
                ((rect.bottom * pageH + yOffset) / totalH).coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * Inverts an [OcrDisplayTransform], mapping a displayed-sheet-normalized point back to a page and a
 * page-normalized image point.
 */
internal fun OcrDisplayTransform.mapInverse(
    u: Float,
    v: Float,
    owner: ReaderPage,
    partner: ReaderPage?,
    ownerW: Int,
    ownerH: Int,
): ImagePoint? {
    return when (this) {
        OcrDisplayTransform.None -> ImagePoint(owner, PointF(u, v))

        is OcrDisplayTransform.Crop -> {
            val x = u * cropRect.width() + cropRect.left
            val y = v * cropRect.height() + cropRect.top
            ImagePoint(owner, PointF(x, y))
        }

        is OcrDisplayTransform.Split -> {
            val offset = if (keepLeft) 0f else 0.5f
            ImagePoint(owner, PointF(u / 2f + offset, v))
        }

        is OcrDisplayTransform.SplitAndMerge -> {
            val isInRight = u >= 0.5f
            val (xOffset, yOffset) = splitAndMergeOffsets(isInRight, upperIsRight)
            ImagePoint(owner, PointF(u / 2f + xOffset, (v - yOffset) * 2f))
        }

        is OcrDisplayTransform.Merge -> {
            val totalW = (ownerW + centerMarginPx + partnerW).toFloat()
            val totalH = max(ownerH, partnerH).toFloat()
            if (totalW <= 0f || totalH <= 0f) return null
            val leftW = if (isLTR) ownerW else partnerW
            val ownerIsLeft = isLTR
            val ownerXOffset = if (ownerIsLeft) 0f else leftW + centerMarginPx.toFloat()
            val partnerXOffset = if (ownerIsLeft) leftW + centerMarginPx.toFloat() else 0f
            val ownerRange = ownerXOffset / totalW..(ownerXOffset + ownerW) / totalW
            val inOwner = u in ownerRange
            if (inOwner) {
                val pageW = ownerW
                val pageH = ownerH
                val yOffset = (totalH - pageH) / 2f
                ImagePoint(owner, PointF((u * totalW - ownerXOffset) / pageW, (v * totalH - yOffset) / pageH))
            } else {
                val pageW = partnerW
                val pageH = partnerH
                if (partner == null || pageW <= 0) return null
                val yOffset = (totalH - pageH) / 2f
                ImagePoint(partner, PointF((u * totalW - partnerXOffset) / pageW, (v * totalH - yOffset) / pageH))
            }
        }
    }
}

private fun splitAndMergeOffsets(isInRight: Boolean, upperIsRight: Boolean): Pair<Float, Float> = if (upperIsRight) {
    if (isInRight) 0.5f to 0f else 0f to 0.5f
} else {
    if (isInRight) 0.5f to 0.5f else 0f to 0f
}

/**
 * GPL [OcrViewport] adapter for the pager (SSIV) backend (plan 04 §1.4).
 *
 * Owned by [PagerViewer] and rebuilt implicitly: [holdersProvider] returns every holder currently
 * laid out on screen (the current page plus any neighbour a page turn currently has visible), so
 * the mapping follows the page turn without re-creating the adapter. [transformVersion] folds in
 * each holder's live window position, so a page **slide** invalidates the overlay every frame, not
 * only when the SSIV's own scale/centre changes. Main-thread only.
 */
class SsivOcrViewport(
    private val activity: ReaderActivity,
    private val holdersProvider: () -> List<PagerPageHolder>,
) : OcrViewport {

    private val overlayView: View get() = activity.binding.composeOverlay

    override fun visiblePages(): List<ReaderPage> =
        holdersProvider().flatMap { listOfNotNull(it.page, it.extraOcrPage()) }

    override fun imageNormalizedToScreen(page: ReaderPage, rect: RectF): RectF? {
        val holder = holdersProvider().firstOrNull { it.page === page || it.extraOcrPage() === page } ?: return null
        val ssiv = holder.ocrSubsamplingImageView() ?: return null
        if (!ssiv.isReady || ssiv.sWidth <= 0 || ssiv.sHeight <= 0) return null

        val display = holder.ocrDisplayTransform().mapForward(
            page = page,
            rect = rect,
            owner = holder.page,
            partner = holder.extraOcrPage(),
            ownerW = holder.ocrPageWidth(),
            ownerH = holder.ocrPageHeight(),
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

    override fun screenToImageNormalized(x: Float, y: Float): ImagePoint? {
        // During a slide two holders are on screen; the point is inside exactly one, so try the
        // current holder first and fall through to a neighbour.
        for (holder in holdersProvider()) {
            val ssiv = holder.ocrSubsamplingImageView() ?: continue
            if (!ssiv.isReady || ssiv.sWidth <= 0 || ssiv.sHeight <= 0) continue

            val off = OcrOverlayCoords.offsetOf(ssiv, overlayView)
            val source = ssiv.viewToSourceCoord(x - off.x, y - off.y) ?: continue
            val u = source.x / ssiv.sWidth
            val v = source.y / ssiv.sHeight
            if (u < 0f || u > 1f || v < 0f || v > 1f) continue
            return holder.ocrDisplayTransform().mapInverse(
                u = u,
                v = v,
                owner = holder.page,
                partner = holder.extraOcrPage(),
                ownerW = holder.ocrPageWidth(),
                ownerH = holder.ocrPageHeight(),
            )
        }
        return null
    }

    override fun transformVersion(): Long {
        // Includes each holder's window location, which changes continuously through a ViewPager
        // slide; `ocrTransformVersion` alone only ticks on SSIV scale/centre changes, so the boxes
        // would otherwise stay put until the page settled.
        var hash = 0L
        val location = IntArray(2)
        for (holder in holdersProvider()) {
            holder.getLocationInWindow(location)
            hash = hash * 31L + holder.hashCode() + location[0] * 7L + location[1] * 13L +
                holder.ocrTransformVersion()
        }
        return hash
    }
}
