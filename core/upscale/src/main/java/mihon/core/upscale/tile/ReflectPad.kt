// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Reflection padding for the tile crop fed to the model.
//
// The reference pads the crop on the right/bottom only, by whole-sample symmetric reflection
// (period `2 * (len - 1)`, i.e. the edge sample is NOT duplicated: `abcde` pads to `abcde dcb a`),
// up to the page's uniform tensor shape. Left/top are never padded — the pads on that side come from
// the neighbouring tile's real pixels (see `Tile.padLeft` / `Tile.padTop`).
// KUN <--
package mihon.core.upscale.tile

/**
 * Number of elements [reflectIndex] adds on the far side to grow [length] to [target]; `0` when the
 * source already covers the target.
 */
fun reflectPadSizes(length: Int, target: Int): Int {
    require(length > 0) { "length must be positive: $length" }
    return (target - length).coerceAtLeast(0)
}

/**
 * Maps [index] into `0 until length` by whole-sample symmetric reflection of period
 * `2 * (length - 1)`, so index `-1` maps to `1` and index `length` maps to `length - 2`.
 * A one-element source has no reflection and maps everything to `0`.
 */
fun reflectIndex(index: Int, length: Int): Int {
    require(length > 0) { "length must be positive: $length" }
    if (length == 1) return 0
    val period = 2 * (length - 1)
    val mod = ((index % period) + period) % period
    return if (mod < length) mod else period - mod
}

/**
 * Copies the [width] x [height] [source] into the top-left of [destination] and fills the rest by
 * reflecting the source right/bottom (whole-sample symmetric, see [reflectIndex]).
 *
 * [source] is tightly packed (`width * height`); [destination] is the model tensor shape.
 */
fun padTile(
    source: IntArray,
    width: Int,
    height: Int,
    destination: IntArray,
    destinationWidth: Int,
    destinationHeight: Int,
) {
    require(width > 0 && height > 0) { "source must be non-empty: ${width}x$height" }
    require(source.size >= width * height) {
        "source holds ${source.size} pixel(s), need ${width * height}"
    }
    require(destinationWidth >= width && destinationHeight >= height) {
        "destination ${destinationWidth}x$destinationHeight does not cover the source ${width}x$height"
    }
    require(destination.size >= destinationWidth * destinationHeight) {
        "destination holds ${destination.size} pixel(s), need ${destinationWidth * destinationHeight}"
    }

    for (y in 0 until destinationHeight) {
        val sourceY = reflectIndex(y, height) * width
        val rowStart = y * destinationWidth
        for (x in 0 until destinationWidth) {
            destination[rowStart + x] = source[sourceY + reflectIndex(x, width)]
        }
    }
}

/**
 * Byte-plane variant of [padTile], for one channel of the NCHW tensor the model is fed.
 *
 * [source] is tightly packed (`width * height`); [destination] is the plane shape.
 */
fun padTile(
    source: ByteArray,
    width: Int,
    height: Int,
    destination: ByteArray,
    destinationWidth: Int,
    destinationHeight: Int,
) {
    require(width > 0 && height > 0) { "source must be non-empty: ${width}x$height" }
    require(source.size >= width * height) {
        "source holds ${source.size} byte(s), need ${width * height}"
    }
    require(destinationWidth >= width && destinationHeight >= height) {
        "destination ${destinationWidth}x$destinationHeight does not cover the source ${width}x$height"
    }
    require(destination.size >= destinationWidth * destinationHeight) {
        "destination holds ${destination.size} byte(s), need ${destinationWidth * destinationHeight}"
    }

    for (y in 0 until destinationHeight) {
        val sourceY = reflectIndex(y, height) * width
        val rowStart = y * destinationWidth
        for (x in 0 until destinationWidth) {
            destination[rowStart + x] = source[sourceY + reflectIndex(x, width)]
        }
    }
}
