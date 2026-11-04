// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The whole tile pipeline, end to end, with a fake inference: geometry, padding, cropping, blending,
// band splitting, cancellation and the memory-pressure fallback. No device, no ONNX Runtime.
//
// `FakeTileInference` is an exact 2x nearest-neighbour upscale, so "what the page should look like"
// is computable from the source page alone — which is why the assertions below can compare every
// pixel instead of trusting the pipeline against itself.
// KUN <--
package mihon.core.upscale.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mihon.core.upscale.model.ModelArch
import mihon.core.upscale.model.ModelFamily
import mihon.core.upscale.model.UpscaleModel
import mihon.core.upscale.model.UpscaleModelCatalog
import mihon.core.upscale.tile.Tile
import mihon.core.upscale.tile.planGrid
import org.junit.jupiter.api.Test
import java.nio.file.Files

class UpscalePipelineTest {

    @Test
    fun `a 37x53 page becomes exactly 74x106 and every pixel is the nearest-neighbour one`() {
        val page = page(37, 53) { x, y -> grey((x * 7 + y * 13) and 0xFF) }

        val result = engine().upscale(
            page = DecodedPage(page, 37, 53),
            model = MODEL,
            tileSize = 128,
            maxOutputPixels = CAP_OFF,
        )

        result.width shouldBe 74
        result.height shouldBe 106
        result.bandCount shouldBe 1
        val pixels = result.argb!!
        for (y in 0 until 106) {
            for (x in 0 until 74) {
                pixels[y * 74 + x] shouldBe page[(y / 2) * 37 + (x / 2)]
            }
        }
    }

    @Test
    fun `a constant page stays constant across a 3x3 tile grid`() {
        // The grid this test is about: 3 columns and 3 rows of 100 px tiles, so every tile but the
        // first of the page has real-neighbour pads on the sides it shares with another tile.
        val grid = planGrid(width = 300, height = 300, tileSize = 128)
        grid.columns shouldBe 3
        grid.rows shouldBe 3
        grid.tile(1, 1) shouldBe Tile(100, 100, 100, 100, 16, 16, 16, 16)

        val page = page(300, 300) { _, _ -> GREY }
        val result = engine().upscale(
            page = DecodedPage(page, 300, 300),
            model = MODEL,
            tileSize = 128,
            maxOutputPixels = CAP_OFF,
        )

        result.width shouldBe 600
        result.height shouldBe 600
        result.argb!!.all { it == GREY } shouldBe true
    }

    @Test
    fun `a 0 to 255 ramp across a tile seam stays monotonic with no step of its own`() {
        // The page is the same in every row, so a horizontal seam bug shows up as a one-pixel dip or
        // jump along the row: the ramp's own step is 0 or 1.
        val page = page(300, 300) { x, _ -> grey(x * 255 / 299) }
        val result = engine().upscale(
            page = DecodedPage(page, 300, 300),
            model = MODEL,
            tileSize = 128,
            maxOutputPixels = CAP_OFF,
        )

        val pixels = result.argb!!
        val row = (0 until 600).map { pixels[it] }
        row[0] shouldBe page[0]
        row[599] shouldBe page[299]
        for (x in 1 until 600) {
            val previous = row[x - 1] and 0xFF
            val current = row[x] and 0xFF
            current shouldBeGreaterThanOrEqual previous
            (current - previous) shouldBeLessThanOrEqual 1
        }
        // The seams sit at input x = 100 and x = 200, i.e. output 200 and 400; the band of each is
        // 32 px either side and must not read as unwritten (transparent) memory.
        for (x in 168 until 232) {
            row[x] ushr 24 shouldBe 0xFF
        }
        for (x in 368 until 432) {
            row[x] ushr 24 shouldBe 0xFF
        }
    }

    @Test
    fun `a page under the cap is one band and a page over it splits into bands that stay constant`() {
        val page = page(200, 600) { _, _ -> GREY }

        val single = engine().upscale(
            page = DecodedPage(page, 200, 600),
            model = MODEL,
            tileSize = 128,
            maxOutputPixels = CAP_OFF,
        )
        single.bandCount shouldBe 1
        single.width shouldBe 400
        single.height shouldBe 1200
        single.argb!!.all { it == GREY } shouldBe true

        val banded = engine().upscale(
            page = DecodedPage(page, 200, 600),
            model = MODEL,
            tileSize = 128,
            maxOutputPixels = 100_000,
        )
        banded.bandCount shouldBe 5
        banded.width shouldBe 400
        banded.height shouldBe 1200
        // A mis-blended band seam would show up here as a band of other values.
        banded.argb!!.all { it == GREY } shouldBe true
    }

    @Test
    fun `cancelling before the first tile stops without producing a page`() {
        val recorder = Recorder()
        val engine = engine(recorder)

        shouldThrow<UpscaleCancelledException> {
            engine.upscale(
                page = DecodedPage(page(64, 64) { _, _ -> GREY }, 64, 64),
                model = MODEL,
                tileSize = 128,
                maxOutputPixels = CAP_OFF,
                isCancelled = { true },
            )
        }
        recorder.widths.shouldBeEmpty()
    }

    @Test
    fun `an allocation failure halves the tile size and succeeds at the 128 px floor`() {
        val recorder = Recorder()
        // Fails on a tensor wider than 300 (tile 512 and 256), succeeds at the 128 px floor (192).
        val engine = engine(recorder) { input ->
            if (input.width > 300) throw OutOfMemoryError("cannot allocate ${input.width}")
        }

        val result = engine.upscale(
            page = DecodedPage(page(512, 512) { _, _ -> GREY }, 512, 512),
            model = MODEL,
            tileSize = 512,
            maxOutputPixels = CAP_OFF,
        )

        // alignUp(tileWidth + 2 * 16, 64) at tile 512 / 256 / 128: the first tile of each attempt,
        // then the rest of the page at the size that worked.
        recorder.widths.take(3) shouldBe listOf(576, 320, 192)
        recorder.widths.drop(3).distinct() shouldBe listOf(192)
        // The two failures, then the page's 16 tiles at 128 px.
        recorder.widths.size shouldBe 18
        // The session that ran out of memory is closed before the retry, not kept alive next to the
        // new one.
        recorder.closes shouldBe 2
        result.tileSize shouldBe 128
        result.width shouldBe 1024
        result.height shouldBe 1024
        result.argb!!.all { it == GREY } shouldBe true
    }

    @Test
    fun `an allocation failure at the 128 px floor is rethrown instead of retried forever`() {
        val recorder = Recorder()
        val engine = engine(recorder) { throw OutOfMemoryError("no memory") }

        shouldThrow<OutOfMemoryError> {
            engine.upscale(
                page = DecodedPage(page(512, 512) { _, _ -> GREY }, 512, 512),
                model = MODEL,
                tileSize = 512,
                maxOutputPixels = CAP_OFF,
            )
        }
        // 512 -> 256 -> 128, then the floor: no fourth attempt, and the last session is freed.
        recorder.widths shouldBe listOf(576, 320, 192)
        recorder.closes shouldBe 3
    }

    @Test
    fun `a non-finite model output is surfaced as the dedicated exception naming the model`() {
        val engine = engine(Recorder(), corrupt = { it[0] = Float.NaN })

        val error = shouldThrow<UpscaleNonFiniteException> {
            engine.upscale(
                page = DecodedPage(page(64, 64) { _, _ -> GREY }, 64, 64),
                model = MODEL,
                tileSize = 128,
                maxOutputPixels = CAP_OFF,
            )
        }
        error.message.orEmpty() shouldContain MODEL.id
    }

    @Test
    fun `floatToHalfBits writes the binary16 the tensor needs`() {
        floatToHalfBits(0f) shouldBe 0x0000
        floatToHalfBits(1f) shouldBe 0x3C00.toShort()
        floatToHalfBits(0.5f) shouldBe 0x3800.toShort()
        floatToHalfBits(-1f) shouldBe 0xBC00.toShort()
        // 1/255 is the smallest non-zero byte the pipeline feeds: 2^-8 with a 4/1024 mantissa.
        floatToHalfBits(1f / 255f) shouldBe 0x1C04.toShort()
    }

    @Test
    fun `a byte survives the float16 round trip the tensor and an fp16 output use`() {
        for (byte in 1..255) {
            val value = byte / 255f
            // What an fp16 output hands back must still quantise to the same byte the model wrote.
            (halfBitsToFloat(floatToHalfBits(value)) * 255f + 0.5f).toInt() shouldBe byte
        }
        halfBitsToFloat(floatToHalfBits(0.5f)) shouldBe 0.5f
    }

    @Test
    fun `each model starts on the provider the on-device benchmark measured for it`() {
        // The defect this pins: the fp16 FDAT_M exports open a WebGPU session and then return NaN
        // for every pixel at a 512 or 384 px tile (phase 3b bench), so starting them there turns
        // every colour page into an `UpscaleNonFiniteException` at the default tile size.
        fun model(id: String): UpscaleModel = checkNotNull(UpscaleModelCatalog.find(id)) { "no $id" }

        UpscaleEngine.providerFor(model("illustrationjanai_2x_v3detail_fdat_m")) shouldBe
            ExecutionProvider.CPU
        UpscaleEngine.providerFor(model("mangajanai_2x_1600p")) shouldBe ExecutionProvider.WEBGPU
        UpscaleEngine.providerFor(model("illustrationjanai_2x_v3detail_span_s")) shouldBe
            ExecutionProvider.WEBGPU
    }

    // ------------------------------------------------------------------ helpers

    private fun engine(
        recorder: Recorder = Recorder(),
        corrupt: (FloatArray) -> Unit = {},
        fail: (TileInput) -> Unit = {},
    ): UpscaleEngine = UpscaleEngine(
        modelsDir = Files.createTempDirectory("upscale-models").toFile(),
        surfaces = ArgbSurfaces,
        inferenceFactory = { _, scale, _ -> FakeTileInference(scale, recorder, corrupt, fail) },
        // A JVM test never reaches `android.os.Process`, and must not.
        threadPriority = 0,
    )

    private fun page(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): IntArray =
        IntArray(width * height) { index -> pixel(index % width, index / width) }

    private fun grey(value: Int): Int = OPAQUE or (value shl 16) or (value shl 8) or value

    private companion object {
        const val CAP_OFF = 1_000_000_000L
        const val OPAQUE = 0xFF shl 24
        const val GREY = OPAQUE or 0x00808080

        val MODEL = UpscaleModel(
            id = "fake_2x",
            fileName = "fake_2x.onnx",
            family = ModelFamily.ILLUSTRATION_JANAI,
            scale = 2,
            bucketHeight = null,
            arch = ModelArch.ESRGAN,
            displayName = "Fake 2x",
            approxBytes = 1_000L,
        )
    }
}

/** What the fake inference saw, shared across the instances a retry creates. */
private class Recorder {
    /** Width of every tensor the fake was asked for, so a smaller-tile retry is visible. */
    val widths = mutableListOf<Int>()

    /** How many fake sessions the engine closed. */
    var closes = 0
}

/**
 * An exact `scale`-times nearest-neighbour upscale, plus the hooks a failure test needs.
 *
 * [recorder] is shared across instances, so a retry that rebuilds the inference is still visible as
 * one sequence; [corrupt] can break the output and [fail] can throw before producing one.
 */
private class FakeTileInference(
    private val scale: Int,
    private val recorder: Recorder,
    private val corrupt: (FloatArray) -> Unit,
    private val fail: (TileInput) -> Unit,
) : TileInference {

    override val isFloat16: Boolean = false

    override fun infer(input: TileInput): FloatArray {
        recorder.widths += input.width
        fail(input)
        val rgb = (input as TileInput.Float32).rgb
        val width = input.width
        val height = input.height
        val outputWidth = width * scale
        val outputHeight = height * scale
        val output = FloatArray(outputWidth * outputHeight * 3)
        for (y in 0 until outputHeight) {
            val sourceRow = (y / scale) * width
            for (x in 0 until outputWidth) {
                val from = (sourceRow + x / scale) * 3
                val to = (y * outputWidth + x) * 3
                output[to] = rgb[from]
                output[to + 1] = rgb[from + 1]
                output[to + 2] = rgb[from + 2]
            }
        }
        corrupt(output)
        return output
    }

    override fun close() {
        recorder.closes++
    }
}
