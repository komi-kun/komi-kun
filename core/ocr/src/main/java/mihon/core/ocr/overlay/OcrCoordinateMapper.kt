// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/OcrCoordinateMapper.kt` @ f85c063.
// Adapted for komi-kun: the mapper is viewer-agnostic normalized-space math (03 §3.5), so it lives in
// `:core:ocr` next to the OCR models instead of the app viewer package. Chimahon's
// `detectCropRect` read crop borders from the old `tachiyomi.decoder` `ImageDecoder` (a `RectF` the
// reader already computes today), so it is dropped here: [mapToCropped] takes the crop rect the
// caller/viewport resolved, keeping this file free of any decoder/app dependency.
// KUN <--
package mihon.core.ocr.overlay

import android.graphics.RectF
import mihon.core.ocr.OcrLineGeometry
import mihon.core.ocr.model.OcrTextBlock
import kotlin.math.max

/**
 * Coordinate remapping for OCR blocks under reader transformations.
 *
 * OCR blocks store normalized (0..1) coordinates relative to the **original** image. When the reader
 * applies a transformation (crop borders, split, merge, webtoon split-and-merge, rotation), the
 * displayed page differs from the original, so block coordinates must be remapped to match.
 */
object OcrCoordinateMapper {

    /**
     * Remaps blocks from full-image normalized space into the kept half after a wide image is split.
     *
     * Blocks whose center falls in the kept half survive; `x` is rescaled `[0,0.5] → [0,1]` (LEFT) or
     * `[0.5,1] → [0,1]` (RIGHT); `y` is unchanged.
     */
    fun mapToSplit(blocks: List<OcrTextBlock>, keepLeft: Boolean): List<OcrTextBlock> {
        val offset = if (keepLeft) 0f else 0.5f
        return blocks.mapNotNull { block ->
            val centerX = (block.xmin + block.xmax) / 2f
            val inHalf = if (keepLeft) centerX <= 0.5f else centerX >= 0.5f
            if (!inHalf) return@mapNotNull null

            val newXmin = ((block.xmin - offset) * 2f).coerceIn(0f, 1f)
            val newXmax = ((block.xmax - offset) * 2f).coerceIn(0f, 1f)
            if (newXmax <= newXmin) return@mapNotNull null

            val newGeometries = block.lineGeometries?.mapNotNull { geo ->
                val geoCenterX = (geo.xmin + geo.xmax) / 2f
                val geoInHalf = if (keepLeft) geoCenterX <= 0.5f else geoCenterX >= 0.5f
                if (!geoInHalf) return@mapNotNull null
                OcrLineGeometry(
                    xmin = ((geo.xmin - offset) * 2f).coerceIn(0f, 1f),
                    ymin = geo.ymin,
                    xmax = ((geo.xmax - offset) * 2f).coerceIn(0f, 1f),
                    ymax = geo.ymax,
                    rotation = geo.rotation,
                )
            }

            block.copy(xmin = newXmin, xmax = newXmax, lineGeometries = newGeometries)
        }
    }

    /**
     * Merges block lists from two pages onto a unified merged-bitmap coordinate space.
     *
     * The merged bitmap is `page1W + centerMarginPx + page2W` wide and `max(page1H, page2H)` tall,
     * with each page vertically centered.
     */
    fun mapToMerged(
        blocks1: List<OcrTextBlock>,
        page1W: Int,
        page1H: Int,
        blocks2: List<OcrTextBlock>,
        page2W: Int,
        page2H: Int,
        isLTR: Boolean,
        centerMarginPx: Int,
    ): List<OcrTextBlock> {
        val totalW = (page1W + centerMarginPx + page2W).toFloat()
        val totalH = max(page1H, page2H).toFloat()
        if (totalW <= 0 || totalH <= 0) return blocks1

        val leftW: Int
        val leftH: Int
        val rightW: Int
        val rightH: Int
        val leftBlocks: List<OcrTextBlock>
        val rightBlocks: List<OcrTextBlock>

        if (isLTR) {
            leftW = page1W
            leftH = page1H
            leftBlocks = blocks1
            rightW = page2W
            rightH = page2H
            rightBlocks = blocks2
        } else {
            leftW = page2W
            leftH = page2H
            leftBlocks = blocks2
            rightW = page1W
            rightH = page1H
            rightBlocks = blocks1
        }

        val leftXOffset = 0f
        val leftYOffset = (totalH - leftH) / 2f
        val rightXOffset = leftW + centerMarginPx.toFloat()
        val rightYOffset = (totalH - rightH) / 2f

        val mapped1 = remapToCanvas(leftBlocks, leftW.toFloat(), leftH.toFloat(), leftXOffset, leftYOffset, totalW, totalH)
        val mapped2 = remapToCanvas(rightBlocks, rightW.toFloat(), rightH.toFloat(), rightXOffset, rightYOffset, totalW, totalH)
        return mapped1 + mapped2
    }

    /**
     * Remaps blocks onto a cropped sub-region of the image. [cropRect] is in normalized 0..1 space;
     * blocks outside it are dropped, the rest are rescaled into the crop.
     */
    fun mapToCropped(blocks: List<OcrTextBlock>, cropRect: RectF): List<OcrTextBlock> =
        if (cropRect.left == 0f && cropRect.top == 0f && cropRect.right == 1f && cropRect.bottom == 1f) {
            blocks
        } else {
            remapToCrop(blocks, cropRect)
        }

    /**
     * Remaps blocks after seam-augmented OCR: coordinates are normalized to a taller augmented image,
     * so `y` is rescaled back and blocks spilling past the seam are clipped/dropped.
     */
    fun remapSeamAugmented(
        blocks: List<OcrTextBlock>,
        originalHeight: Int,
        augmentedHeight: Int,
    ): List<OcrTextBlock> {
        if (originalHeight <= 0 || augmentedHeight <= originalHeight) return blocks
        val ratio = originalHeight.toFloat() / augmentedHeight

        return blocks.mapNotNull { block ->
            val newYmin = block.ymin / ratio
            val newYmax = block.ymax / ratio
            if (newYmax <= 0f || newYmin >= 1f) return@mapNotNull null

            val clippedYmin = newYmin.coerceIn(0f, 1f)
            val clippedYmax = newYmax.coerceIn(0f, 1f)
            if (clippedYmax <= clippedYmin) return@mapNotNull null

            val newGeometries = block.lineGeometries?.mapNotNull { geo ->
                val gnYmin = (geo.ymin / ratio).coerceIn(0f, 1f)
                val gnYmax = (geo.ymax / ratio).coerceIn(0f, 1f)
                if (gnYmax <= gnYmin) return@mapNotNull null
                geo.copy(ymin = gnYmin, ymax = gnYmax)
            }

            block.copy(ymin = clippedYmin, ymax = clippedYmax, lineGeometries = newGeometries)
        }
    }

    /**
     * Remaps blocks for webtoon "splitAndMerge": a wide image's halves are stacked vertically, the
     * upper half being the right side unless [upperIsRight] is false.
     */
    fun mapToSplitAndMerge(blocks: List<OcrTextBlock>, upperIsRight: Boolean = true): List<OcrTextBlock> {
        return blocks.mapNotNull { block ->
            val centerX = (block.xmin + block.xmax) / 2f
            val isInRight = centerX >= 0.5f

            val (xOffset, yOffset) = if (upperIsRight) {
                if (isInRight) Pair(0.5f, 0f) else Pair(0f, 0.5f)
            } else {
                if (isInRight) Pair(0.5f, 0.5f) else Pair(0f, 0f)
            }

            val newXmin = ((block.xmin - xOffset) * 2f).coerceIn(0f, 1f)
            val newXmax = ((block.xmax - xOffset) * 2f).coerceIn(0f, 1f)
            val newYmin = (yOffset + block.ymin * 0.5f).coerceIn(0f, 1f)
            val newYmax = (yOffset + block.ymax * 0.5f).coerceIn(0f, 1f)
            if (newXmax <= newXmin || newYmax <= newYmin) return@mapNotNull null

            val newGeometries = block.lineGeometries?.mapNotNull { geo ->
                val geoCenterX = (geo.xmin + geo.xmax) / 2f
                if ((geoCenterX >= 0.5f) != isInRight) return@mapNotNull null
                OcrLineGeometry(
                    xmin = ((geo.xmin - xOffset) * 2f).coerceIn(0f, 1f),
                    ymin = (yOffset + geo.ymin * 0.5f).coerceIn(0f, 1f),
                    xmax = ((geo.xmax - xOffset) * 2f).coerceIn(0f, 1f),
                    ymax = (yOffset + geo.ymax * 0.5f).coerceIn(0f, 1f),
                    rotation = geo.rotation,
                )
            }

            block.copy(
                xmin = newXmin,
                ymin = newYmin,
                xmax = newXmax,
                ymax = newYmax,
                lineGeometries = newGeometries,
            )
        }
    }

    /**
     * Remaps blocks after a 90° display rotation:
     * `90° CW: new_x = 1 - old_y, new_y = old_x`; `90° CCW: new_x = old_y, new_y = 1 - old_x`.
     */
    fun mapToRotated(blocks: List<OcrTextBlock>, clockwise: Boolean): List<OcrTextBlock> {
        return blocks.mapNotNull { block ->
            val newXmin = if (clockwise) 1f - block.ymax else block.ymin
            val newYmin = if (clockwise) block.xmin else 1f - block.xmax
            val newXmax = if (clockwise) 1f - block.ymin else block.ymax
            val newYmax = if (clockwise) block.xmax else 1f - block.xmin
            if (newXmax <= newXmin || newYmax <= newYmin) return@mapNotNull null

            val newGeometries = block.lineGeometries?.mapNotNull { geo ->
                val gnXmin = if (clockwise) 1f - geo.ymax else geo.ymin
                val gnYmin = if (clockwise) geo.xmin else 1f - geo.xmax
                val gnXmax = if (clockwise) 1f - geo.ymin else geo.ymax
                val gnYmax = if (clockwise) geo.xmax else 1f - geo.xmin
                if (gnXmax <= gnXmin || gnYmax <= gnYmin) return@mapNotNull null
                OcrLineGeometry(gnXmin, gnYmin, gnXmax, gnYmax, geo.rotation)
            }

            block.copy(
                xmin = newXmin,
                ymin = newYmin,
                xmax = newXmax,
                ymax = newYmax,
                lineGeometries = newGeometries,
            )
        }
    }

    /**
     * Remaps blocks when a horizontal center margin of [centerPadding] px is inserted into a
     * [origWidth] px image (the right half shifts right, opening a gap at the midline).
     */
    fun mapWithCenterMargin(
        blocks: List<OcrTextBlock>,
        origWidth: Int,
        centerPadding: Int,
    ): List<OcrTextBlock> {
        if (origWidth <= 0 || centerPadding <= 0) return blocks
        val ratio = origWidth.toFloat() / (origWidth + centerPadding)
        val gap = centerPadding.toFloat() / (origWidth + centerPadding)

        return blocks.mapNotNull { block ->
            val newXmin = if (block.xmin < 0.5f) block.xmin * ratio else block.xmin * ratio + gap
            val newXmax = if (block.xmax < 0.5f) block.xmax * ratio else block.xmax * ratio + gap
            val cx = (newXmin + newXmax) / 2f
            if (cx in (ratio * 0.5f)..(ratio * 0.5f + gap)) return@mapNotNull null

            val newGeometries = block.lineGeometries?.mapNotNull { geo ->
                val gnXmin = if (geo.xmin < 0.5f) geo.xmin * ratio else geo.xmin * ratio + gap
                val gnXmax = if (geo.xmax < 0.5f) geo.xmax * ratio else geo.xmax * ratio + gap
                if (gnXmax <= gnXmin) return@mapNotNull null
                OcrLineGeometry(gnXmin, geo.ymin, gnXmax, geo.ymax, geo.rotation)
            }

            block.copy(
                xmin = newXmin.coerceIn(0f, 1f),
                ymin = block.ymin,
                xmax = newXmax.coerceIn(0f, 1f),
                ymax = block.ymax,
                lineGeometries = newGeometries,
            )
        }
    }

    /** Remaps blocks so they align with a cropped sub-region of the original image. */
    fun remapToCrop(blocks: List<OcrTextBlock>, cropRect: RectF): List<OcrTextBlock> {
        val cropW = cropRect.width()
        val cropH = cropRect.height()
        if (cropW <= 0f || cropH <= 0f) return blocks

        return blocks.mapNotNull { block ->
            val newXmin = ((block.xmin - cropRect.left) / cropW).coerceIn(0f, 1f)
            val newYmin = ((block.ymin - cropRect.top) / cropH).coerceIn(0f, 1f)
            val newXmax = ((block.xmax - cropRect.left) / cropW).coerceIn(0f, 1f)
            val newYmax = ((block.ymax - cropRect.top) / cropH).coerceIn(0f, 1f)

            if (newXmax <= 0f || newYmax <= 0f || newXmin >= 1f || newYmin >= 1f) return@mapNotNull null
            if (newXmax <= newXmin || newYmax <= newYmin) return@mapNotNull null

            val newGeometries = block.lineGeometries?.mapNotNull { geo ->
                val gnXmin = ((geo.xmin - cropRect.left) / cropW).coerceIn(0f, 1f)
                val gnYmin = ((geo.ymin - cropRect.top) / cropH).coerceIn(0f, 1f)
                val gnXmax = ((geo.xmax - cropRect.left) / cropW).coerceIn(0f, 1f)
                val gnYmax = ((geo.ymax - cropRect.top) / cropH).coerceIn(0f, 1f)
                if (gnXmax <= gnXmin || gnYmax <= gnYmin) return@mapNotNull null
                OcrLineGeometry(gnXmin, gnYmin, gnXmax, gnYmax, geo.rotation)
            }

            block.copy(
                xmin = newXmin,
                ymin = newYmin,
                xmax = newXmax,
                ymax = newYmax,
                lineGeometries = newGeometries,
            )
        }
    }

    /** Remaps block coords from one page's pixel space into a merged canvas normalized space. */
    private fun remapToCanvas(
        blocks: List<OcrTextBlock>,
        pageW: Float,
        pageH: Float,
        xOffsetPx: Float,
        yOffsetPx: Float,
        totalW: Float,
        totalH: Float,
    ): List<OcrTextBlock> {
        return blocks.mapNotNull { block ->
            val cxMin = block.xmin * pageW + xOffsetPx
            val cyMin = block.ymin * pageH + yOffsetPx
            val cxMax = block.xmax * pageW + xOffsetPx
            val cyMax = block.ymax * pageH + yOffsetPx

            val newXmin = (cxMin / totalW).coerceIn(0f, 1f)
            val newYmin = (cyMin / totalH).coerceIn(0f, 1f)
            val newXmax = (cxMax / totalW).coerceIn(0f, 1f)
            val newYmax = (cyMax / totalH).coerceIn(0f, 1f)
            if (newXmax <= newXmin || newYmax <= newYmin) return@mapNotNull null

            val newGeometries = block.lineGeometries?.mapNotNull { geo ->
                val gnXmin = ((geo.xmin * pageW + xOffsetPx) / totalW).coerceIn(0f, 1f)
                val gnYmin = ((geo.ymin * pageH + yOffsetPx) / totalH).coerceIn(0f, 1f)
                val gnXmax = ((geo.xmax * pageW + xOffsetPx) / totalW).coerceIn(0f, 1f)
                val gnYmax = ((geo.ymax * pageH + yOffsetPx) / totalH).coerceIn(0f, 1f)
                if (gnXmax <= gnXmin || gnYmax <= gnYmin) return@mapNotNull null
                OcrLineGeometry(gnXmin, gnYmin, gnXmax, gnYmax, geo.rotation)
            }

            block.copy(
                xmin = newXmin,
                ymin = newYmin,
                xmax = newXmax,
                ymax = newYmax,
                lineGeometries = newGeometries,
            )
        }
    }
}
