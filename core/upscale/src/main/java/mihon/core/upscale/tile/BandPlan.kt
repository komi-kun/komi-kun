// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Vertical band splitting for pages whose upscaled result would exceed the configured pixel cap.
//
// A band is a horizontal slice of the *input* page that is upscaled on its own. Consecutive bands
// overlap by `overlap` input pixels on each side of their shared seam (the same 16 px the tiles use),
// so the seam can be blended with the same vertical band blend; the band's [Band.outputOffsetY] is
// where its top-left goes in the finished page, so the caller only has to place it.
//
// The cap bounds the page's *unique* output, not the context a band borrows from its neighbours:
// a band's overlap rows are inferred twice and blended away, so charging them to the cap would make
// the limit unreachable for small caps.
// KUN <--
package mihon.core.upscale.tile

/**
 * One input band.
 *
 * [originY] is the band's first input row (already including the top context), [height] its input
 * height, and [outputOffsetY] `originY * scale` — where its output block is placed. The band's
 * unique content is `[uniqueTop, uniqueBottom)`; the parts outside it are [overlapTop] /
 * [overlapBottom] rows of context shared with the neighbouring band.
 */
data class Band(
    val originY: Int,
    val height: Int,
    val outputOffsetY: Int,
    val overlapTop: Int,
    val overlapBottom: Int,
) {
    /** First input row that only this band owns. */
    val uniqueTop: Int get() = originY + overlapTop

    /** One past the last input row that only this band owns. */
    val uniqueBottom: Int get() = originY + height - overlapBottom
}

/** The complete band plan for one page; [bands] is in top-to-bottom order. */
data class BandPlan(
    val width: Int,
    val height: Int,
    val scale: Int,
    val bands: List<Band>,
) {
    val outputWidth: Int get() = width * scale

    val outputHeight: Int get() = height * scale

    /** True when the page fits the cap in one pass and [bands] is a single, full-page band. */
    val isSinglePass: Boolean get() = bands.size == 1
}

/**
 * Splits a [width] x [height] input page into vertical bands whose upscaled output stays under
 * [maxOutputPixels].
 *
 * Returns a **single full-page band** when the whole page already fits (one code path for the
 * caller), and `null` when not even one input row's worth of output fits the cap — there is no band
 * plan for a cap that cannot hold the narrowest possible slice.
 *
 * [overlap] is in input pixels and is capped at each page edge, exactly like the tile pads.
 */
fun planBands(
    width: Int,
    height: Int,
    maxOutputPixels: Long,
    scale: Int,
    overlap: Int = DEFAULT_OVERLAP,
): BandPlan? {
    require(width > 0) { "width must be positive: $width" }
    require(height > 0) { "height must be positive: $height" }
    require(scale > 0) { "scale must be positive: $scale" }
    require(overlap >= 0) { "overlap must not be negative: $overlap" }
    require(maxOutputPixels >= 0) { "maxOutputPixels must not be negative: $maxOutputPixels" }

    val outputWidth = width.toLong() * scale
    val wholePage = outputWidth * height.toLong() * scale
    if (wholePage <= maxOutputPixels) {
        return BandPlan(width, height, scale, listOf(Band(0, height, 0, 0, 0)))
    }

    // Rows of output one band may occupy, and the input rows of unique content that buys.
    val maxOutputRows = maxOutputPixels / outputWidth
    if (maxOutputRows < scale) return null
    var advance = (maxOutputRows / scale).toInt()
    if (advance < 1) return null

    // Spread the page evenly over the bands the cap forces, so the last band is never a sliver: the
    // rebalanced advance can only shrink, so the cap still holds.
    val bandCount = ceilDiv(height, advance)
    if (bandCount > 1) advance = ceilDiv(height, bandCount)

    val bands = ArrayList<Band>(bandCount)
    var top = 0
    while (top < height) {
        val bottom = minOf(height, top + advance)
        val originY = maxOf(0, top - overlap)
        val end = minOf(height, bottom + overlap)
        bands += Band(
            originY = originY,
            height = end - originY,
            outputOffsetY = originY * scale,
            overlapTop = top - originY,
            overlapBottom = end - bottom,
        )
        top = bottom
    }

    return BandPlan(width, height, scale, bands)
}
