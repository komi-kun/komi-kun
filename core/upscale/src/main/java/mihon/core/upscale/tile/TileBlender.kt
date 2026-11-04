// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Seam blending for reader page upscaling.
//
// The model is run tile by tile, so neighbouring tiles disagree slightly along their shared edge.
// The reference hides that with a two-level blend, both levels using the same half-sine band:
//
//   level 1 (inside a tile row): horizontally blend tile `c` into the row buffer;
//   level 2 (rows into the page): vertically blend the finished row buffer into the page.
//
// All positions here are in *output* pixels; the input-space overlap is `TileGrid.overlap` and the
// band is `2 * start * scale` wide, straddling the seam (`start * scale` pixels of the previous tile,
// the same of the incoming one).
//
// `b(t) = (sin(clamp(2t - 0.5, 0, 1) * PI - PI / 2) + 1) / 2` is 0 for `t <= 0.25`, 0.5 at 0.5 and 1
// for `t >= 0.75`, so the outer quarters of the band are owned outright by one side and only the
// middle half is a real cross-fade.
//
// **What the per-tile block must contain.** A blend band straddles the seam, so both sides of it have
// to exist: the block for a tile is its rect *plus its real-neighbour pads* —
// `Tile.extendedWidth * scale` by `Tile.extendedHeight * scale`, sitting at
// `((Tile.x - Tile.padLeft) * scale, (Tile.y - Tile.padTop) * scale)`. Those pads are the neighbour's
// own pixels (they are what `planGrid` reads into the inference crop), so the band compares two
// upscales of the same page area and no tile is ever displaced; only the *reflected* ring that pads
// the crop up to the uniform tensor shape is discarded. A rect-only block would leave the band's far
// half blending against unwritten memory.
// KUN <--
package mihon.core.upscale.tile

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The reference blend weight at position `t` in `0..1` across a blend band: 0 up to `t = 0.25`, 0.5
 * at `t = 0.5`, 1 from `t = 0.75`. Values outside `0..1` are clamped.
 */
fun halfSinWeight(t: Double): Double {
    val clamped = (2.0 * t - 0.5).coerceIn(0.0, 1.0)
    return (sin(clamped * PI - PI / 2.0) + 1.0) / 2.0
}

/**
 * Blends one pixel: `back * (1 - frontWeight) + front * frontWeight`, per A/R/G/B channel, rounded
 * half-up and clamped to `0..255`.
 */
fun blendPixel(back: Int, front: Int, frontWeight: Double): Int {
    val weight = frontWeight.coerceIn(0.0, 1.0)
    if (weight <= 0.0) return back
    if (weight >= 1.0) return front
    var result = 0
    for (shift in CHANNEL_SHIFTS) {
        val backChannel = (back ushr shift) and 0xFF
        val frontChannel = (front ushr shift) and 0xFF
        val blended = floor(backChannel * (1.0 - weight) + frontChannel * weight + 0.5).toInt()
        result = result or (blended.coerceIn(0, 255) shl shift)
    }
    return result
}

/**
 * Blends a horizontal band of [count] columns x [rows] rows from [incoming] into [accumulator].
 *
 * `accumulatorIndex` / `incomingIndex` point at the band's top-left pixel; the accumulator is
 * [stride] pixels wide and the incoming block [incomingStride]. The weight varies along x (the band's
 * own direction).
 */
fun blendHorizontalBand(
    accumulator: IntArray,
    accumulatorIndex: Int,
    incoming: IntArray,
    incomingIndex: Int,
    count: Int,
    rows: Int,
    stride: Int,
    incomingStride: Int = stride,
) {
    require(count > 0 && rows > 0) { "band must be non-empty: ${count}x$rows" }
    for (column in 0 until count) {
        val weight = bandWeight(column, count)
        for (row in 0 until rows) {
            val accumulatorAt = accumulatorIndex + row * stride + column
            accumulator[accumulatorAt] = blendPixel(
                accumulator[accumulatorAt],
                incoming[incomingIndex + row * incomingStride + column],
                weight,
            )
        }
    }
}

/**
 * Blends a vertical band of [width] pixels x [count] rows from [incoming] into [accumulator].
 *
 * `accumulatorIndex` / `incomingIndex` point at the band's top-left pixel. The weight varies along y
 * (the band's own direction).
 */
fun blendVerticalBand(
    accumulator: IntArray,
    accumulatorIndex: Int,
    incoming: IntArray,
    incomingIndex: Int,
    width: Int,
    count: Int,
    stride: Int,
    incomingStride: Int = stride,
) {
    require(width > 0 && count > 0) { "band must be non-empty: ${width}x$count" }
    for (row in 0 until count) {
        val weight = bandWeight(row, count)
        val accumulatorRow = accumulatorIndex + row * stride
        val incomingRow = incomingIndex + row * incomingStride
        for (column in 0 until width) {
            accumulator[accumulatorRow + column] = blendPixel(
                accumulator[accumulatorRow + column],
                incoming[incomingRow + column],
                weight,
            )
        }
    }
}

/**
 * Receives finished rows of an assembled page, once each and in ascending order.
 *
 * This is the streaming seam of [TileAssembler.assembleInto]: a page is never held as one
 * `IntArray`, so a 32 MP result does not need a 128 MB Java-heap scratch of its own.
 */
fun interface PageRowSink {
    /**
     * [pixels] holds [rowCount] tightly packed ARGB_8888 rows, the first of them page row [y].
     * Once written a row is final: the assembler never revisits it.
     */
    fun writeRows(y: Int, pixels: IntArray, rowCount: Int)
}

/**
 * Stitches a page's per-tile ARGB outputs into one ARGB page, blending every seam.
 *
 * [assemble] takes the tiles in the grid's row-major order. Each block is the tile's rect plus its
 * real-neighbour pads — [Tile.extendedWidth] * [scale] by [Tile.extendedHeight] * [scale] — i.e. the
 * model's output for the tile's inference crop with only the reflected ring discarded (see the file
 * header).
 *
 * A tile narrower than its own start-overlap's band (`Tile.w * scale < 2 * start`) cannot be blended
 * without over-reading, so it is rejected rather than silently producing a seam.
 */
class TileAssembler(
    private val grid: TileGrid,
    private val scale: Int,
    private val overlap: Int = grid.overlap,
) {
    init {
        require(scale > 0) { "scale must be positive: $scale" }
        require(overlap >= 0) { "overlap must not be negative: $overlap" }
    }

    val outputWidth: Int = grid.width * scale
    val outputHeight: Int = grid.height * scale

    /** The whole page as one ARGB buffer. Kept for tests and for small pages; see [assembleInto]. */
    fun assemble(tiles: List<IntArray>): IntArray {
        val page = IntArray(outputWidth * outputHeight)
        assembleInto(tiles) { y, pixels, rowCount ->
            pixels.copyInto(page, y * outputWidth, 0, rowCount * outputWidth)
        }
        return page
    }

    /**
     * Streaming variant of [assemble]: the same blending, but rows are handed to [sink] as soon as no
     * later tile row can touch them, so only one tile row's block plus the rows still in play are
     * ever on the Java heap.
     *
     * Row `r`'s block is placed at page row `(tile.y - tile.padTop) * scale`. Every block of a row
     * starts below the previous block's top edge, so everything above the incoming block's top edge
     * is final and can be emitted; the buffer then keeps the rows from that edge down, which is
     * exactly the span the next vertical blend reads.
     */
    fun assembleInto(tiles: List<IntArray>, sink: PageRowSink) {
        require(tiles.size == grid.tileCount) {
            "expected ${grid.tileCount} tile(s), got ${tiles.size}"
        }
        assembleInto(tiles.iterator(), sink)
    }

    /**
     * [assembleInto] over blocks that are produced as they are consumed, in the grid's row-major
     * order — the engine infers a tile only when the assembler reaches it, so a band never has to
     * hold all of its tiles at once.
     */
    fun assembleInto(tiles: Iterator<IntArray>, sink: PageRowSink) {
        // The assembled rows that are still being worked on: page rows
        // [bufferTop, bufferTop + bufferRows), tightly packed at [outputWidth] per row.
        var buffer = IntArray(0)
        var bufferTop = 0
        var bufferRows = 0
        // One scratch block, reused by every row (sized for the tallest, i.e. the first row's).
        var rowBuffer = IntArray(0)

        var previousRow: Tile? = null
        for (row in 0 until grid.rows) {
            val first = grid.tile(0, row)
            // Every tile of a row shares its rect height and pads, so the row block is uniform.
            val blockHeight = first.extendedHeight * scale
            val blockTop = (first.y - first.padTop) * scale

            // No block below starts above this one, so the rows above it can never change again.
            val flush = (blockTop - bufferTop).coerceIn(0, bufferRows)
            if (flush > 0) {
                sink.writeRows(bufferTop, buffer, flush)
                buffer.copyInto(buffer, 0, flush * outputWidth, bufferRows * outputWidth)
                bufferTop += flush
                bufferRows -= flush
            }
            val needed = blockHeight * outputWidth
            if (buffer.size < needed) buffer = buffer.copyOf(needed)
            if (rowBuffer.size < needed) rowBuffer = IntArray(needed)
            fillRowBuffer(row, tiles, rowBuffer, blockHeight)

            val previous = previousRow
            if (previous == null) {
                rowBuffer.copyInto(buffer, 0, 0, blockHeight * outputWidth)
            } else {
                val seam = first.y * scale
                val start = minOf(overlap, first.y) * scale
                val previousEnd = minOf(overlap, grid.height - (previous.y + previous.h)) * scale
                val half = minOf(start, previousEnd)
                if (2 * half > 0) {
                    blendVerticalBand(
                        accumulator = buffer,
                        accumulatorIndex = (seam - half - blockTop) * outputWidth,
                        incoming = rowBuffer,
                        incomingIndex = (start - half) * outputWidth,
                        width = outputWidth,
                        count = 2 * half,
                        stride = outputWidth,
                        incomingStride = outputWidth,
                    )
                }
                copyBlock(
                    source = rowBuffer,
                    sourceIndex = (start + half) * outputWidth,
                    destination = buffer,
                    destinationIndex = (seam + half - blockTop) * outputWidth,
                    width = outputWidth,
                    height = blockHeight - (start + half),
                    sourceStride = outputWidth,
                    destinationStride = outputWidth,
                )
            }
            bufferRows = blockHeight
            previousRow = first
        }

        if (bufferRows > 0) {
            sink.writeRows(bufferTop, buffer, bufferRows)
        }
    }

    private fun fillRowBuffer(
        row: Int,
        tiles: Iterator<IntArray>,
        buffer: IntArray,
        blockHeight: Int,
    ) {
        var previous: Tile? = null
        for (column in 0 until grid.columns) {
            val tile = grid.tile(column, row)
            require(tiles.hasNext()) { "ran out of tile blocks at ($column, $row)" }
            val source = tiles.next()
            val blockWidth = tile.extendedWidth * scale
            require(source.size >= blockWidth * blockHeight) {
                "tile ($column, $row) holds ${source.size} pixel(s), " +
                    "need ${blockWidth * blockHeight}"
            }
            val seam = tile.x * scale
            val previousTile = previous
            if (previousTile == null) {
                copyBlock(
                    source = source,
                    sourceIndex = 0,
                    destination = buffer,
                    destinationIndex = (tile.x - tile.padLeft) * scale,
                    width = blockWidth,
                    height = blockHeight,
                    sourceStride = blockWidth,
                    destinationStride = outputWidth,
                )
            } else {
                val start = minOf(overlap, tile.x) * scale
                val previousEnd =
                    minOf(overlap, grid.width - (previousTile.x + previousTile.w)) * scale
                require(tile.w * scale >= 2 * start) {
                    "tile at (${tile.x}, ${tile.y}) is too narrow to blend: " +
                        "${tile.w * scale} < ${2 * start}"
                }
                val half = minOf(start, previousEnd)
                if (2 * half > 0) {
                    blendHorizontalBand(
                        accumulator = buffer,
                        accumulatorIndex = seam - half,
                        incoming = source,
                        incomingIndex = start - half,
                        count = 2 * half,
                        rows = blockHeight,
                        stride = outputWidth,
                        incomingStride = blockWidth,
                    )
                }
                copyBlock(
                    source = source,
                    sourceIndex = start + half,
                    destination = buffer,
                    destinationIndex = seam + half,
                    width = blockWidth - (start + half),
                    height = blockHeight,
                    sourceStride = blockWidth,
                    destinationStride = outputWidth,
                )
            }
            previous = tile
        }
    }
}

private val CHANNEL_SHIFTS = intArrayOf(24, 16, 8, 0)

private fun bandWeight(index: Int, count: Int): Double =
    if (count <= 1) 1.0 else index.toDouble() / (count - 1).toDouble()

/**
 * Copies a [width] x [height] block between two [sourceStride] / [destinationStride] wide buffers.
 */
private fun copyBlock(
    source: IntArray,
    sourceIndex: Int,
    destination: IntArray,
    destinationIndex: Int,
    width: Int,
    height: Int,
    sourceStride: Int,
    destinationStride: Int = sourceStride,
) {
    if (width <= 0 || height <= 0) return
    for (row in 0 until height) {
        val from = sourceIndex + row * sourceStride
        val to = destinationIndex + row * destinationStride
        source.copyInto(destination, to, from, from + width)
    }
}
