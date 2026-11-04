// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.upscale.tile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TileGeometryTest {

    @Test
    fun `alignUp rounds up to the next multiple`() {
        alignUp(1157, 64) shouldBe 1216
        alignUp(64, 64) shouldBe 64
        alignUp(1, 64) shouldBe 64
        ceilDiv(5685, 4) shouldBe 1422
        ceilDiv(1125, 1) shouldBe 1125
    }

    @Test
    fun `1125x1600 single pass pads to a 1216x1664 tensor`() {
        val grid = planGrid(width = 1125, height = 1600, tileSize = 0)

        grid.columns shouldBe 1
        grid.rows shouldBe 1
        grid.tileWidth shouldBe 1125
        grid.tileHeight shouldBe 1600
        grid.tileCount shouldBe 1

        uniformTensorShape(grid) shouldBe TensorShape(width = 1216, height = 1664)
    }

    @Test
    fun `800x5685 at a 1536 bound is 1x4 of 800x1422 and an 832x1472 tensor`() {
        val grid = planGrid(width = 800, height = 5685, tileSize = 1536)

        grid.columns shouldBe 1
        grid.rows shouldBe 4
        grid.tileWidth shouldBe 800
        grid.tileHeight shouldBe 1422

        grid.tiles.map { it.y } shouldBe listOf(0, 1422, 2844, 4266)
        // The last tile is clipped by the page: 4266 + 1422 = 5688 > 5685.
        grid.tiles.map { it.h } shouldBe listOf(1422, 1422, 1422, 1419)

        uniformTensorShape(grid) shouldBe TensorShape(width = 832, height = 1472)
    }

    @Test
    fun `pads come from the neighbours and are capped at the page edges`() {
        val grid = planGrid(width = 800, height = 5685, tileSize = 1536)

        grid.tile(0, 0).padTop shouldBe 0
        grid.tile(0, 1).padTop shouldBe 16
        grid.tile(0, 0).padBottom shouldBe 16
        grid.tile(0, 3).padBottom shouldBe 0
        grid.tile(0, 0).padLeft shouldBe 0
        grid.tile(0, 0).padRight shouldBe 0
        grid.tile(0, 3).extendedHeight shouldBe 1419 + 16
    }

    @Test
    fun `a multi-column grid clips the last column and pads the middle ones`() {
        val grid = planGrid(width = 1125, height = 1600, tileSize = 384)

        grid.columns shouldBe 3
        grid.rows shouldBe 5
        grid.tileWidth shouldBe 375
        grid.tileHeight shouldBe 320

        grid.tile(0, 0).padLeft shouldBe 0
        grid.tile(1, 0).padLeft shouldBe 16
        grid.tile(1, 0).padRight shouldBe 16
        // 750 + 375 = 1125: the right edge needs no left-behind context.
        grid.tile(2, 0).x shouldBe 750
        grid.tile(2, 0).padRight shouldBe 0
        grid.tile(2, 0).extendedWidth shouldBe 375 + 16
    }
}
