// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Tile geometry for reader page upscaling — the grid, the per-tile rect with its reflection pads,
// and the uniform (per-page, 64-aligned) tensor shape every tile of the page is fed to the model in.
//
// Ported from the reference implementation, so the arithmetic below is deliberately the reference's
// arithmetic, not a tidy re-derivation: `columns = ceil(width / bound)`, `tileWidth =
// ceil(width / columns)`, origin `(x * tileWidth, y * tileHeight)`. The last column/row can therefore
// be *narrower* than `tileWidth`/`tileHeight` (it is clipped to the page), while the tensor shape is
// derived from the nominal tile size so that every tile of the page has the same input shape — one
// model session, one set of allocations, no per-tile re-shape.
// KUN <--
package mihon.core.upscale.tile

/** Overlap, in *input* pixels, taken from the neighbouring tile on each side (reference constant). */
const val DEFAULT_OVERLAP: Int = 16

/** Alignment of the uniform tensor shape (reference constant). */
const val TENSOR_ALIGNMENT: Int = 64

/** `value` rounded up to the next multiple of `alignment` (`alignment` must be positive). */
fun alignUp(value: Int, alignment: Int): Int {
    require(alignment > 0) { "alignment must be positive: $alignment" }
    return ((value + alignment - 1) / alignment) * alignment
}

/** `numerator / denominator` rounded up; both must be non-negative. */
fun ceilDiv(numerator: Int, denominator: Int): Int {
    require(denominator > 0) { "denominator must be positive: $denominator" }
    return (numerator + denominator - 1) / denominator
}

/**
 * One tile of the grid.
 *
 * [x]/[y] are the tile's origin inside the page and [w]/[h] its size there — clipped to the page, so
 * the last tile of a row/column is the one that can be narrower than [TileGrid.tileWidth] /
 * [TileGrid.tileHeight]. The pads are the context read *around* the tile for inference: they are the
 * overlap the neighbours' blend uses, and they can be smaller than [DEFAULT_OVERLAP] at the page
 * edges (never negative).
 */
data class Tile(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val padLeft: Int,
    val padTop: Int,
    val padRight: Int,
    val padBottom: Int,
) {
    /** Crop width actually fed to the model (tile + both horizontal pads). */
    val extendedWidth: Int get() = padLeft + w + padRight

    /** Crop height actually fed to the model (tile + both vertical pads). */
    val extendedHeight: Int get() = padTop + h + padBottom
}

/**
 * A page's tile grid. [tiles] is row-major (`index = y * columns + x`), which is the order the
 * engine and [TileAssembler] consume and emit.
 */
data class TileGrid(
    val width: Int,
    val height: Int,
    val tileSize: Int,
    val overlap: Int,
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val tiles: List<Tile>,
) {
    init {
        require(width > 0 && height > 0) { "page must be non-empty: ${width}x$height" }
        require(columns > 0 && rows > 0) { "grid must be non-empty: ${columns}x$rows" }
        require(tiles.size == columns * rows) {
            "grid is ${columns}x$rows but has ${tiles.size} tile(s)"
        }
    }

    val tileCount: Int get() = tiles.size

    /** The tile at grid position ([column], [row]). */
    fun tile(column: Int, row: Int): Tile {
        require(column in 0 until columns && row in 0 until rows) {
            "no tile at ($column, $row) in a ${columns}x$rows grid"
        }
        return tiles[row * columns + column]
    }
}

/** The model input shape, `[1, 3, height, width]`, every tile of one page is fed in. */
data class TensorShape(val width: Int, val height: Int)

/**
 * Plans the tile grid for a page.
 *
 * `tileSize <= 0` means "one tile across" (the reference's `bound = max(width, height)`).
 */
fun planGrid(
    width: Int,
    height: Int,
    tileSize: Int,
    overlap: Int = DEFAULT_OVERLAP,
): TileGrid {
    require(width > 0) { "width must be positive: $width" }
    require(height > 0) { "height must be positive: $height" }
    require(overlap >= 0) { "overlap must not be negative: $overlap" }

    val bound = if (tileSize <= 0) maxOf(width, height) else tileSize
    val columns = ceilDiv(width, bound)
    val rows = ceilDiv(height, bound)
    val tileWidth = ceilDiv(width, columns)
    val tileHeight = ceilDiv(height, rows)

    val tiles = ArrayList<Tile>(columns * rows)
    for (row in 0 until rows) {
        val tileY = row * tileHeight
        val tileH = minOf(tileHeight, height - tileY)
        for (column in 0 until columns) {
            val tileX = column * tileWidth
            val tileW = minOf(tileWidth, width - tileX)
            tiles += Tile(
                x = tileX,
                y = tileY,
                w = tileW,
                h = tileH,
                padLeft = minOf(tileX, overlap),
                padTop = minOf(tileY, overlap),
                padRight = minOf(width - (tileX + tileW), overlap).coerceAtLeast(0),
                padBottom = minOf(height - (tileY + tileH), overlap).coerceAtLeast(0),
            )
        }
    }

    return TileGrid(
        width = width,
        height = height,
        tileSize = tileSize,
        overlap = overlap,
        columns = columns,
        rows = rows,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        tiles = tiles,
    )
}

/**
 * The single, uniform tensor shape every tile of [grid] is inferred in:
 * `max(64, alignUp(tileWidth + 2 * overlap, 64))` by the same for the height.
 *
 * It is derived from the *nominal* tile size, not from each tile's clip, and it is at least as large
 * as the reference's intermediate 16-aligned pad of any tile's crop (`extendedWidth <= tileWidth +
 * 2 * overlap`), so padding straight to this shape is the same operation the reference performs in
 * two steps.
 */
fun uniformTensorShape(
    grid: TileGrid,
    overlap: Int = grid.overlap,
    alignment: Int = TENSOR_ALIGNMENT,
): TensorShape {
    require(overlap >= 0) { "overlap must not be negative: $overlap" }
    return TensorShape(
        width = maxOf(alignment, alignUp(grid.tileWidth + 2 * overlap, alignment)),
        height = maxOf(alignment, alignUp(grid.tileHeight + 2 * overlap, alignment)),
    )
}
