// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.upscale.tile

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TileBlenderTest {

    @Test
    fun `halfSinWeight is 0 at the start, 0_5 in the middle and 1 at the end of the band`() {
        halfSinWeight(0.0) shouldBe 0.0
        halfSinWeight(0.25) shouldBe 0.0
        halfSinWeight(0.5) shouldBe 0.5
        halfSinWeight(0.75) shouldBe 1.0
        halfSinWeight(1.0) shouldBe 1.0
        // Outside the band the reference clamps rather than extrapolates.
        halfSinWeight(-0.5) shouldBe 0.0
        halfSinWeight(2.0) shouldBe 1.0
    }

    @Test
    fun `two equal tiles assemble to the exact same constant`() {
        val grid = planGrid(width = 128, height = 64, tileSize = 64)
        val tiles = grid.tiles.map { block(it.extendedWidth * 2, it.extendedHeight * 2, GREY) }

        val page = TileAssembler(grid, scale = 2).assemble(tiles)

        page.size shouldBe 256 * 128
        page.all { it == GREY } shouldBe true
    }

    @Test
    fun `a 0 to 255 step across a seam stays monotonic and never jumps`() {
        val grid = planGrid(width = 128, height = 64, tileSize = 64)
        val tiles = grid.tiles.mapIndexed { index, tile ->
            block(
                tile.extendedWidth * 2,
                tile.extendedHeight * 2,
                if (index == 0) BLACK else WHITE,
            )
        }

        val page = TileAssembler(grid, scale = 2).assemble(tiles)

        val row = (0 until 256).map { page[it] }
        page.all { (it ushr 24) and 0xFF == 0xFF } shouldBe true
        // Tile 0's block runs to x 159; tile 1's band straddles the seam at x 128.
        row[95] shouldBe BLACK
        row[96] shouldBe BLACK
        row[159] shouldBe WHITE
        row[255] shouldBe WHITE
        // No seam: a monotonic ramp, and no step bigger than the band's own slope.
        for (x in 1 until 256) {
            val previous = row[x - 1] and 0xFF
            val current = row[x] and 0xFF
            current shouldBeGreaterThanOrEqual previous
            (current - previous) shouldBeLessThanOrEqual 20
        }
        // The middle of the band really is a blend, not one side copied over the other.
        (row[128] and 0xFF in 1..254) shouldBe true
    }

    @Test
    fun `a tile narrower than its blend band is rejected instead of over-read`() {
        // 20px wide, 8px bound -> 3 columns of 7px, so each tile is narrower than 2 * 8px.
        val grid = planGrid(width = 20, height = 10, tileSize = 8, overlap = 8)
        grid.tileWidth shouldBe 7
        val assembler = TileAssembler(grid, scale = 1, overlap = 8)
        val tiles = grid.tiles.map { IntArray(it.extendedWidth * it.extendedHeight) }

        shouldThrow<IllegalArgumentException> { assembler.assemble(tiles) }
    }

    @Test
    fun `a vertical seam blends the same way as a horizontal one`() {
        val grid = planGrid(width = 64, height = 128, tileSize = 64)
        grid.columns shouldBe 1
        grid.rows shouldBe 2
        val tiles = grid.tiles.mapIndexed { index, tile ->
            block(
                tile.extendedWidth * 2,
                tile.extendedHeight * 2,
                if (index == 0) BLACK else WHITE,
            )
        }

        val page = TileAssembler(grid, scale = 2).assemble(tiles)

        val column = (0 until 256).map { page[it * 128] }
        column[95] shouldBe BLACK
        column[159] shouldBe WHITE
        column[255] shouldBe WHITE
        for (y in 1 until 256) {
            val previous = column[y - 1] and 0xFF
            val current = column[y] and 0xFF
            current shouldBeGreaterThanOrEqual previous
            (current - previous) shouldBeLessThanOrEqual 20
        }
    }

    private companion object {
        val BLACK: Int = 0xFF000000.toInt()
        val WHITE: Int = -0x1
        val GREY: Int = 0xFF404040.toInt()

        fun block(width: Int, height: Int, argb: Int): IntArray = IntArray(width * height) { argb }
    }
}
