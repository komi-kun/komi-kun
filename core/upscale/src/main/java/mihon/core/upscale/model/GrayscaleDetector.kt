// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// "Is this page black-and-white line art?" — the routing decision's only image input.
//
// The check is a *sampler*, not an analyser: a page is sampled on a grid every [sampleStep] pixels
// both ways, which is what makes it cheap enough to run on every page without decoding anything
// twice. Two thresholds, from the reference:
//
//   * every sample within 2 levels of neutral (`max(|R-G|, |G-B|) <= 2`) is grayscale outright — a
//     scan or a black/white page, whatever its brightness;
//   * samples within 25 levels of neutral are tolerated as JPEG chroma noise, but then the page must
//     also be dark overall (`meanLuma <= 0.5`), because bright near-neutral pages are almost always
//     colour pages with a light palette, not manga;
//   * anything more saturated than 25 at any sample is colour, full stop.
// KUN <--
package mihon.core.upscale.model

import kotlin.math.abs

/** How many pixels apart the sampler reads, both horizontally and vertically. */
const val DEFAULT_GRAYSCALE_SAMPLE_STEP: Int = 4

/** Channel difference that is grayscale regardless of brightness. */
const val GRAYSCALE_EXACT_TOLERANCE: Int = 2

/** Channel difference tolerated as JPEG chroma noise, if the page is also dark. */
const val GRAYSCALE_NOISE_TOLERANCE: Int = 25

/** Mean relative luma a page may have and still be treated as grayscale under the noise tolerance. */
const val GRAYSCALE_MAX_MEAN_LUMA: Double = 0.5

/**
 * Whether the [width] x [height] ARGB image starting at offset 0 of [argb] looks like grayscale line
 * art. [stride] is the row length in pixels (it may exceed [width]: padding columns are ignored).
 *
 * A page whose samples all sit inside [GRAYSCALE_EXACT_TOLERANCE] is grayscale no matter how bright;
 * one inside [GRAYSCALE_NOISE_TOLERANCE] is grayscale only while its mean luma stays at or below
 * [GRAYSCALE_MAX_MEAN_LUMA]. One sample outside [GRAYSCALE_NOISE_TOLERANCE] ends the check as colour.
 */
fun isGrayscale(
    argb: IntArray,
    width: Int,
    height: Int,
    stride: Int = width,
    sampleStep: Int = DEFAULT_GRAYSCALE_SAMPLE_STEP,
): Boolean {
    require(width > 0) { "width must be positive: $width" }
    require(height > 0) { "height must be positive: $height" }
    require(stride >= width) { "stride ($stride) must cover the width ($width)" }
    require(argb.size >= stride * height) {
        "buffer holds ${argb.size} pixel(s), need ${stride * height}"
    }

    val step = maxOf(1, sampleStep)
    var samples = 0
    var lumaSum = 0
    var withinExactTolerance = true

    var y = 0
    while (y < height) {
        val rowStart = y * stride
        var x = 0
        while (x < width) {
            val pixel = argb[rowStart + x]
            val red = (pixel ushr 16) and 0xFF
            val green = (pixel ushr 8) and 0xFF
            val blue = pixel and 0xFF
            val deviation = maxOf(abs(red - green), abs(green - blue))
            if (deviation > GRAYSCALE_NOISE_TOLERANCE) return false
            if (deviation > GRAYSCALE_EXACT_TOLERANCE) withinExactTolerance = false
            lumaSum += lumaOf(red, green, blue)
            samples++
            x += step
        }
        y += step
    }

    if (samples == 0) return false
    if (withinExactTolerance) return true
    return lumaSum.toDouble() / samples.toDouble() / 255.0 <= GRAYSCALE_MAX_MEAN_LUMA
}

/**
 * [isGrayscale] with a fast path for a page whose flag is already known (page metadata, or a result
 * the reader cached): a non-null [knownGrayscale] is returned as-is and nothing is sampled.
 */
fun isGrayscale(
    knownGrayscale: Boolean?,
    argb: IntArray,
    width: Int,
    height: Int,
    stride: Int = width,
    sampleStep: Int = DEFAULT_GRAYSCALE_SAMPLE_STEP,
): Boolean = knownGrayscale
    ?: isGrayscale(argb, width, height, stride, sampleStep)

/** Rec. 601 luma, `0..255` in integer steps (only a mean is needed). */
private fun lumaOf(red: Int, green: Int, blue: Int): Int =
    (299 * red + 587 * green + 114 * blue) / 1000
