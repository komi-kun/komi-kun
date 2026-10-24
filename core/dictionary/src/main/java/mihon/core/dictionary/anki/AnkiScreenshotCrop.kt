// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Which part of a reader page becomes a card's `{screenshot}` (plan 05 §B4).
//
// The app layer owns the page pixels and does the decode/crop/encode; this file owns *the decision*
// — mode + preset aspect + page geometry in, one page-normalized rectangle out — so the part that is
// easy to get wrong stays pure Kotlin and unit-testable (Android `Bitmap`/`RectF` are unavailable in
// JVM unit tests).
package mihon.core.dictionary.anki

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A rectangle in page-normalized coordinates: `0..1` of the page width and height. */
data class NormalizedCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left

    val height: Float get() = bottom - top
}

/**
 * The same crop in whole pixels: `left..right × top..bottom`, at least 1×1 and inside the page
 * (the invariant the image decoder's region API enforces).
 */
data class PixelCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left

    val height: Int get() = bottom - top
}

/** A **pixel** aspect ratio (`x:y`), e.g. `4:3` from `mihon.core.ocr.CropPresets`. */
data class AnkiCropAspect(val x: Int, val y: Int) {
    init {
        require(x > 0 && y > 0) { "aspect components must be positive: $x:$y" }
    }

    val ratio: Float get() = x.toFloat() / y.toFloat()
}

object AnkiScreenshotCrop {

    /**
     * Extra context framing the tapped block, as a fraction of the block's own size per side, so a
     * bubble is not cropped flush against its text.
     */
    const val DEFAULT_MARGIN: Float = 0.08f

    /** The whole page, `0,0 → 1,1`. */
    val FULL_PAGE: NormalizedCropRect = NormalizedCropRect(0f, 0f, 1f, 1f)

    /**
     * The crop a card's `{screenshot}` should use for [block] on a [pageWidth] × [pageHeight] page.
     *
     * - [AnkiScreenshotMode.NONE] → `null` (no image).
     * - [AnkiScreenshotMode.FULL] → the whole page.
     * - [AnkiScreenshotMode.ANIMATED_SCENE] → the whole page: manga has no scene animation, and the
     *   KUN enum value degrades to the page rather than silently producing no image at all.
     * - [AnkiScreenshotMode.CROP] → the block, grown by [margin], expanded to [aspect] when one is
     *   configured, centred on the block and slid fully inside the page.
     *
     * A crop larger than the page is scaled down (aspect preserved) instead of being clipped, so a
     * preset can never move the tapped block out of frame. A block with no area yields `null`.
     */
    @Suppress("ReturnCount")
    fun rectFor(
        mode: AnkiScreenshotMode,
        block: NormalizedCropRect,
        pageWidth: Int,
        pageHeight: Int,
        aspect: AnkiCropAspect? = null,
        margin: Float = DEFAULT_MARGIN,
    ): NormalizedCropRect? {
        if (mode == AnkiScreenshotMode.NONE) return null
        if (mode != AnkiScreenshotMode.CROP) return FULL_PAGE
        if (pageWidth <= 0 || pageHeight <= 0) return null

        val pageW = pageWidth.toFloat()
        val pageH = pageHeight.toFloat()
        val blockRect = block.normalized()
        val blockW = blockRect.width * pageW
        val blockH = blockRect.height * pageH
        if (blockW <= 0f || blockH <= 0f) return null

        val grow = 1f + 2f * margin.coerceIn(0f, 1f)
        var cropW = blockW * grow
        var cropH = blockH * grow

        if (aspect != null) {
            val target = aspect.ratio
            if (cropW / cropH < target) {
                cropW = cropH * target
            } else {
                cropH = cropW / target
            }
        }

        // Never larger than the page: scale down with the aspect intact rather than clip the block.
        val scale = min(1f, min(pageW / cropW, pageH / cropH))
        cropW *= scale
        cropH *= scale

        // Centre on the block, then slide the crop fully onto the page.
        val left = (blockRect.left * pageW + (blockW - cropW) / 2f).coerceIn(0f, pageW - cropW)
        val top = (blockRect.top * pageH + (blockH - cropH) / 2f).coerceIn(0f, pageH - cropH)

        return NormalizedCropRect(
            left = left / pageW,
            top = top / pageH,
            right = (left + cropW) / pageW,
            bottom = (top + cropH) / pageH,
        )
    }

    /** Clamps a possibly out-of-range rect into the page and orders its edges. */
    private fun NormalizedCropRect.normalized(): NormalizedCropRect = NormalizedCropRect(
        left = min(left, right).coerceIn(0f, 1f),
        top = min(top, bottom).coerceIn(0f, 1f),
        right = maxOf(left, right).coerceIn(0f, 1f),
        bottom = maxOf(top, bottom).coerceIn(0f, 1f),
    )

    /**
     * The normalized crop as whole pixels of a [pageWidth] × [pageHeight] page, or null when the
     * page has no measurable size.
     *
     * The result always satisfies the decode contract (`!isEmpty && left >= 0 && top >= 0 &&
     * right <= pageWidth && bottom <= pageHeight`, `imagedecoder`'s `checkRegion`): edges are floored,
     * a rounding-collapsed crop keeps at least one pixel, and everything is clamped inside the page.
     */
    fun pixelRectFor(rect: NormalizedCropRect, pageWidth: Int, pageHeight: Int): PixelCropRect? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        val normalized = rect.normalized()
        val left = floor(normalized.left * pageWidth).toInt().coerceIn(0, pageWidth - 1)
        val top = floor(normalized.top * pageHeight).toInt().coerceIn(0, pageHeight - 1)
        val right = floor(normalized.right * pageWidth).toInt().coerceIn(left + 1, pageWidth)
        val bottom = floor(normalized.bottom * pageHeight).toInt().coerceIn(top + 1, pageHeight)
        return PixelCropRect(left = left, top = top, right = right, bottom = bottom)
    }

    /**
     * The decode sample size that keeps the decoded crop's longest side at or below [maxSidePx].
     *
     * `imagedecoder` shrinks a region by `ceil(size / sampleSize)` while decoding, so the ceiling
     * here is what guarantees "never larger than [maxSidePx]" (a decoded side may come out one pixel
     * over with a floor, and would then be re-encoded oversized). Never below 1 — the decoder
     * rejects `sampleSize < 1`.
     */
    fun sampleSizeFor(rect: PixelCropRect, maxSidePx: Int): Int {
        require(maxSidePx >= 1) { "maxSidePx must be positive: $maxSidePx" }
        val longest = max(rect.width, rect.height)
        return ceil(longest.toFloat() / maxSidePx).toInt().coerceAtLeast(1)
    }
}
// KUN <--
