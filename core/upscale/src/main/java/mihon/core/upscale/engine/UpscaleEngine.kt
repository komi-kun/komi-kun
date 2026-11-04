// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The tile pipeline: page in, upscaled page out.
//
// For each band of `planBands` the page is re-gridded (`planGrid` on the band), every tile's crop is
// extended by its real-neighbour pads, reflect-padded up to the page's uniform tensor shape, inferred,
// cropped back to the extended rect (the reflected ring is discarded), and handed to `TileAssembler`
// in row-major order, which blends the horizontal seams and writes finished rows to the output
// surface. Two neighbouring bands are blended the same way, using the same half-sine band, so a band
// seam is invisible for the same reason a tile seam is.
//
// **Java-heap discipline.** A 32 MP output as one `IntArray` is 128 MB, which is how an upscaler
// becomes the thing the OOM killer picks. So nothing page-sized is ever allocated:
//
//   * the *source* page is one `IntArray` (the decoder's own budget, capped by `PageDecoder`);
//   * the tile pipeline holds one tile block at a time — `TileAssembler.assembleInto` pulls each
//     block lazily as it consumes it, so only the block being blended is alive;
//   * rows are written to the output surface as soon as no later tile row can touch them
//     (`assembleInto`'s emission rule: everything above the incoming block's top edge is final);
//     the pending span it keeps is one tile row's block, at most `(tileSize + 2*overlap) * scale`
//     rows of `outputWidth`;
//   * a band boundary reads back only the `2 * overlap * scale` rows it blends against.
//
// The output itself lives in the `PageSurface`: a `Bitmap` (which is native memory + GPU-backed) in
// the app, an `IntArray` in JVM tests.
//
// **Single thread.** All inference, session creation and session teardown happen on the one
// `upscale-inference` thread this engine owns; `submit` is the only supported way to start work, and
// `UpscaleService` is the only caller. `upscale` itself is synchronous, which is what the JVM tests
// drive directly with a fake `TileInference`.
// KUN <--
package mihon.core.upscale.engine

import android.graphics.Bitmap
import android.os.Process
import mihon.core.upscale.model.ModelArch
import mihon.core.upscale.model.UpscaleModel
import mihon.core.upscale.tile.Band
import mihon.core.upscale.tile.PageRowSink
import mihon.core.upscale.tile.TensorShape
import mihon.core.upscale.tile.Tile
import mihon.core.upscale.tile.TileAssembler
import mihon.core.upscale.tile.blendVerticalBand
import mihon.core.upscale.tile.planBands
import mihon.core.upscale.tile.planGrid
import mihon.core.upscale.tile.reflectIndex
import mihon.core.upscale.tile.uniformTensorShape
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/**
 * Where a finished page is written.
 *
 * Rows are addressed in absolute page coordinates and written once each (a band boundary rewrites the
 * rows it blends), so a surface only has to be a random-access ARGB_8888 buffer.
 */
interface PageSurface : AutoCloseable {
    val width: Int

    val height: Int

    /** Reads [rowCount] rows from [y] into the start of [out]. */
    fun readRows(y: Int, rowCount: Int, out: IntArray)

    /** Writes [rowCount] rows from [pixels] (starting at [offset]) at page row [y]. */
    fun writeRows(y: Int, pixels: IntArray, offset: Int, rowCount: Int)

    /** Hands the finished page to the caller. */
    fun toResult(modelId: String, bandCount: Int, tileSize: Int): UpscaleResult

    override fun close() {}
}

/** Creates the output surface for one page. */
fun interface PageSurfaceFactory {
    fun create(width: Int, height: Int): PageSurface
}

/**
 * The finished page: either [bitmap] (the engine's default surface) or [argb] (a buffer surface),
 * never both. [tileSize] is the tile size the page actually ran at, which is below the requested one
 * after a memory-pressure fallback.
 */
class UpscaleResult(
    val width: Int,
    val height: Int,
    val modelId: String,
    val bitmap: Bitmap?,
    val argb: IntArray?,
    val bandCount: Int,
    val tileSize: Int,
)

/**
 * Runs one model over one page, band by band, blending every seam.
 *
 * Owned by the app (one instance, one inference thread, one session at a time); see the file header
 * for the memory and threading rules.
 */
class UpscaleEngine(
    /** Directory the model `.onnx` files live in. */
    private val modelsDir: File,
    private val surfaces: PageSurfaceFactory = BitmapSurfaces,
    /** Builds the model session; the engine decides when it is (re)created and closed. */
    private val inferenceFactory: (File, Int, ExecutionProvider) -> TileInference =
        { file, scale, provider -> OrtTileInference.open(file, scale, preferred = provider) },
    /**
     * Priority of the inference thread (`android.os.Process.THREAD_PRIORITY_BACKGROUND` by default).
     * `0` leaves the thread's own priority alone, which is what a JVM test needs — it never reaches
     * `android.os.Process`.
     */
    private val threadPriority: Int = Process.THREAD_PRIORITY_BACKGROUND,
) : AutoCloseable {

    private class InferenceKey(val path: String, val shape: TensorShape, val scale: Int)

    private val closed = AtomicBoolean(false)

    private var inference: TileInference? = null
    private var inferenceKey: InferenceKey? = null

    /** The one inference thread; created lazily on the first [submit]. */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                if (threadPriority != 0) Process.setThreadPriority(threadPriority)
                runnable.run()
            },
            THREAD_NAME,
        ).apply { isDaemon = true }
    }

    /** Queues [block] on the inference thread. */
    fun submit(block: () -> Unit) {
        check(!closed.get()) { "UpscaleEngine is closed" }
        executor.execute(block)
    }

    /**
     * Upscales [page] with [model].
     *
     * Must run on the inference thread (i.e. inside a [submit] block): the session it builds is not
     * safe to share, and its lifetime is that thread's.
     *
     * @param tileSize the requested tile size in input pixels; halved (never below [MIN_TILE_SIZE])
     *   and the page retried when the engine reports an allocation failure.
     * @param maxOutputPixels the band-splitting cap, from `UpscalePreferences.maxOutputMegapixels`.
     * @throws UpscaleCancelledException when [isCancelled] says stop.
     */
    fun upscale(
        page: DecodedPage,
        model: UpscaleModel,
        tileSize: Int,
        maxOutputPixels: Long,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): UpscaleResult {
        require(tileSize != 0) { "tileSize must not be 0" }
        val plan = planBands(page.width, page.height, maxOutputPixels, model.scale)
            ?: throw IllegalArgumentException(
                "maxOutputPixels=$maxOutputPixels cannot hold even one row of a " +
                    "${page.width}x${page.height} page at ${model.scale}x",
            )

        var size = tileSize
        while (true) {
            if (isCancelled()) throw UpscaleCancelledException()
            try {
                return render(page, model, plan.bands, size, onProgress, isCancelled)
            } catch (error: Throwable) {
                if (!isAllocationFailure(error)) throw error
                // Free the weights before deciding anything: halving the tiles only helps if the
                // native session that ran out of memory is gone first, and at the floor it must not
                // survive the failure either.
                closeInference()
                if (size <= MIN_TILE_SIZE) throw error
                size = maxOf(MIN_TILE_SIZE, size / 2)
            }
        }
    }

    private fun render(
        page: DecodedPage,
        model: UpscaleModel,
        bands: List<Band>,
        tileSize: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): UpscaleResult {
        val scale = model.scale
        val surface = surfaces.create(page.width * scale, page.height * scale)
        try {
            var previous: Band? = null
            for ((index, band) in bands.withIndex()) {
                if (isCancelled()) throw UpscaleCancelledException()
                renderBand(page, model, band, previous, tileSize, surface, isCancelled)
                previous = band
                onProgress((index + 1).toFloat() / bands.size)
            }
            // A cancel that arrived during the last band must not hand back a page either.
            if (isCancelled()) throw UpscaleCancelledException()
            return surface.toResult(model.id, bands.size, tileSize)
        } catch (error: Throwable) {
            runCatching { surface.close() }
            throw error
        }
    }

    private fun renderBand(
        page: DecodedPage,
        model: UpscaleModel,
        band: Band,
        previous: Band?,
        tileSize: Int,
        surface: PageSurface,
        isCancelled: () -> Boolean,
    ) {
        val scale = model.scale
        val grid = planGrid(page.width, band.height, tileSize, overlap = DEFAULT_BAND_OVERLAP)
        val shape = uniformTensorShape(grid)
        val inference = inferenceFor(model, shape)
        val assembler = TileAssembler(grid, scale)
        val sink = BandRowSink(
            surface = surface,
            width = grid.width * scale,
            pageTop = band.outputOffsetY,
            bandRows = band.height * scale,
            overlapTop = band.overlapTop,
            previousOverlapBottom = previous?.overlapBottom ?: 0,
            scale = scale,
        )

        assembler.assembleInto(
            grid.tiles.asSequence()
                .map { tile ->
                    if (isCancelled()) throw UpscaleCancelledException()
                    inferTile(page, band, tile, shape, inference, scale, model)
                }
                .iterator(),
            sink,
        )
        sink.finish()
    }

    /**
     * Crops one tile's extended rect out of the page, reflect-pads it to [shape] and infers it,
     * returning the extended block (`extendedWidth * scale` by `extendedHeight * scale`) the
     * assembler expects.
     */
    private fun inferTile(
        page: DecodedPage,
        band: Band,
        tile: Tile,
        shape: TensorShape,
        inference: TileInference,
        scale: Int,
        model: UpscaleModel,
    ): IntArray {
        val sourceX = tile.x - tile.padLeft
        val sourceY = band.originY + tile.y - tile.padTop
        val cropWidth = tile.extendedWidth
        val cropHeight = tile.extendedHeight

        val input = if (inference.isFloat16) {
            val rgb = ShortArray(shape.width * shape.height * 3)
            fillPadded(page, sourceX, sourceY, cropWidth, cropHeight, shape) { index, value ->
                rgb[index] = BYTE_TO_HALF[value]
            }
            TileInput.Float16(rgb, shape.width, shape.height)
        } else {
            val rgb = FloatArray(shape.width * shape.height * 3)
            fillPadded(page, sourceX, sourceY, cropWidth, cropHeight, shape) { index, value ->
                rgb[index] = BYTE_TO_FLOAT[value]
            }
            TileInput.Float32(rgb, shape.width, shape.height)
        }

        val values = inference.infer(input)
        return cropExtended(values, shape.width * scale, cropWidth * scale, cropHeight * scale, model)
    }

    /** Copies the crop, reflected up to [shape], into an interleaved RGB buffer. */
    private inline fun fillPadded(
        page: DecodedPage,
        sourceX: Int,
        sourceY: Int,
        cropWidth: Int,
        cropHeight: Int,
        shape: TensorShape,
        write: (index: Int, value: Int) -> Unit,
    ) {
        var index = 0
        for (y in 0 until shape.height) {
            val row = (sourceY + reflectIndex(y, cropHeight)) * page.width
            for (x in 0 until shape.width) {
                val pixel = page.argb[row + sourceX + reflectIndex(x, cropWidth)]
                write(index, (pixel ushr 16) and 0xFF)
                write(index + 1, (pixel ushr 8) and 0xFF)
                write(index + 2, pixel and 0xFF)
                index += 3
            }
        }
    }

    /** Cuts the tile's own corner out of the model output, discarding the reflected ring. */
    private fun cropExtended(
        values: FloatArray,
        outputWidth: Int,
        blockWidth: Int,
        blockHeight: Int,
        model: UpscaleModel,
    ): IntArray {
        require(values.size >= 3 * outputWidth * blockHeight) {
            "the model returned ${values.size} value(s) for a ${outputWidth}x$blockHeight tile"
        }
        val block = IntArray(blockWidth * blockHeight)
        for (row in 0 until blockHeight) {
            val source = row * outputWidth * 3
            val target = row * blockWidth
            for (column in 0 until blockWidth) {
                val from = source + column * 3
                val red = values[from]
                val green = values[from + 1]
                val blue = values[from + 2]
                // `OrtTileInference` rejects a non-finite model output at the source; this is the
                // same check for any other `TileInference`, so a NaN can never reach the assembler
                // as a clipped byte and turn into a blank patch instead of a reported failure.
                if (!red.isFinite() || !green.isFinite() || !blue.isFinite()) {
                    throw UpscaleNonFiniteException(
                        "${model.id} produced a non-finite value at $from of ${values.size} " +
                            "($red, $green, $blue)",
                    )
                }
                block[target + column] =
                    OPAQUE or (toByte(red) shl 16) or (toByte(green) shl 8) or toByte(blue)
            }
        }
        return block
    }

    /** The session for [model] at [shape], reused until either changes. */
    private fun inferenceFor(model: UpscaleModel, shape: TensorShape): TileInference {
        val file = File(modelsDir, model.fileName)
        val key = InferenceKey(file.absolutePath, shape, model.scale)
        val current = inference
        if (current != null && inferenceKey == key) return current
        closeInference()
        val created = inferenceFactory(file, model.scale, providerFor(model))
        inference = created
        inferenceKey = key
        return created
    }

    /** Drops the native session; the next tile builds a fresh one. */
    fun reset() {
        runCatching { submit { closeInference() } }
    }

    private fun closeInference() {
        val current = inference ?: return
        inference = null
        inferenceKey = null
        runCatching { current.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { executor.execute { closeInference() } }
        executor.shutdown()
        runCatching { executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }

    companion object {
        /** Inference floor: below this a tile is all padding, and the model stops being useful. */
        const val MIN_TILE_SIZE: Int = 128

        /**
         * The execution provider each model starts its session with; the rest of
         * `OrtTileInference`'s chain is the fallback when that one cannot open a session.
         *
         * Measured on the OnePlus Pad 2 (phase 3b, `EngineBenchmarkTest`), one 1200x1600 page in a
         * single band, tile 512, the second (session-reusing) run:
         *
         * | model                               | WebGPU | XNNPACK | CPU   |
         * |-------------------------------------|--------|---------|-------|
         * | MangaJaNai 2x 1600p (ESRGAN)        | 56 s   | 117 s   | 120 s |
         * | IllustrationJaNai 2x V3 detail SPAN | 14 s   | 21 s    | 21 s  |
         * | IllustrationJaNai 2x V3 detail FDAT | NaN    | 132 s   | 103 s |
         *
         * WebGPU is the fastest provider for two of the three and needs the least native memory
         * (2.0 GB vs 4.0 GB for the CPU kernels), so it starts the chain — except for the fp16
         * FDAT_M exports, whose WebGPU session opens and then returns a NaN for every pixel at a 512
         * or 384 px tile (256 px works: 73 s). The engine reports that as
         * [UpscaleNonFiniteException] and the reader answers by keeping the page as it is, so the
         * FDAT_M models start on the CPU kernels, which are also 1.3x faster than XNNPACK for them.
         * A device whose build has no WebGPU fails session creation instead and falls back through
         * the chain, so this only chooses where the chain starts.
         */
        fun providerFor(model: UpscaleModel): ExecutionProvider = when (model.arch) {
            ModelArch.FDAT_M -> ExecutionProvider.CPU
            ModelArch.ESRGAN, ModelArch.SPAN_S -> ExecutionProvider.WEBGPU
        }

        private const val THREAD_NAME = "upscale-inference"
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private const val OPAQUE = 0xFF shl 24

        /** Same 16 px the tiles use, so a band seam blends exactly like a tile seam. */
        private const val DEFAULT_BAND_OVERLAP = 16
    }
}

/** `0..255` → `0f..1f`, computed once. */
private val BYTE_TO_FLOAT = FloatArray(256) { it / 255f }

/** `0..255` → binary16, computed once. */
private val BYTE_TO_HALF = ShortArray(256) { floatToHalfBits(it / 255f) }

private fun toByte(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

/**
 * IEEE-754 binary16 bit pattern of [value].
 *
 * `Float.floatToFloat16` only exists from Java 20 and Android's `Float` does not have it, so the bits
 * are assembled here: sign, exponent re-biased from 127 to 15, the top 10 mantissa bits. The only
 * inputs this module passes are `0..255 / 255`, all normal in binary16, so the rounded/flushed
 * denormal branches are not on any path — they exist so an unexpected input cannot produce garbage.
 */
internal fun floatToHalfBits(value: Float): Short {
    if (value == 0f || !value.isFinite()) {
        return when {
            value != value -> 0x7E00 // NaN: quiet NaN, loses the payload
            value == 0f -> 0
            value > 0f -> 0x7C00 // +inf
            else -> 0xFC00.toShort() // -inf
        }
    }
    val bits = value.toRawBits()
    val sign = (bits ushr 16) and 0x8000
    val exponent = ((bits ushr 23) and 0xFF) - 127 + 15
    if (exponent <= 0) return sign.toShort() // underflows binary16: flush to (signed) zero
    if (exponent >= 0x1F) return (sign or 0x7C00).toShort() // saturates to infinity
    return (sign or (exponent shl 10) or ((bits ushr 13) and 0x03FF)).toShort()
}

/**
 * The float a binary16 bit pattern stands for — the inverse of [floatToHalfBits], used when a model
 * declares a float16 *output* and ONNX Runtime hands back the raw 16-bit halves.
 */
internal fun halfBitsToFloat(bits: Short): Float {
    val value = bits.toInt() and 0xFFFF
    val sign = value and 0x8000
    val exponent = (value ushr 10) and 0x1F
    val mantissa = value and 0x03FF
    val magnitude = when (exponent) {
        0 -> mantissa.toFloat() * 2f.pow(-24)
        0x1F -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
        else -> (1f + mantissa / 1024f) * 2f.pow(exponent - 15)
    }
    return if (sign == 0) magnitude else -magnitude
}

/**
 * Writes one band's rows into the finished page, blending the seam with the band above it.
 *
 * The band's first `overlapTop * scale` rows repeat what the previous band already wrote; the middle
 * `2 * half` rows of the two bands' overlap are cross-faded with the same half-sine band
 * `TileAssembler` uses vertically, and everything below is this band's own.
 */
private class BandRowSink(
    private val surface: PageSurface,
    private val width: Int,
    private val pageTop: Int,
    private val bandRows: Int,
    overlapTop: Int,
    previousOverlapBottom: Int,
    scale: Int,
) : PageRowSink {

    private val half = min(overlapTop, previousOverlapBottom) * scale
    private val blendStart = overlapTop * scale - half
    private val blendEnd = overlapTop * scale + half
    private val headRows = min(bandRows, blendEnd)

    private val head = IntArray(headRows * width)
    private var headCount = 0
    private var flushed = false

    override fun writeRows(y: Int, pixels: IntArray, rowCount: Int) {
        var row = y
        var index = 0
        while (index < rowCount) {
            if (flushed) {
                surface.writeRows(pageTop + row, pixels, index * width, rowCount - index)
                return
            }
            val take = min(headRows - headCount, rowCount - index)
            if (take > 0) {
                pixels.copyInto(head, headCount * width, index * width, (index + take) * width)
                headCount += take
                index += take
                row += take
            }
            if (headCount >= headRows) flushHead()
        }
    }

    /** Emits everything buffered once the first row past the head arrives (or the band ends). */
    fun finish() {
        if (!flushed) flushHead()
    }

    private fun flushHead() {
        if (flushed) return
        flushed = true
        if (half > 0 && headCount > blendStart) {
            val previous = IntArray(2 * half * width)
            surface.readRows(pageTop + blendStart, 2 * half, previous)
            blendVerticalBand(
                accumulator = previous,
                accumulatorIndex = 0,
                incoming = head,
                incomingIndex = blendStart * width,
                width = width,
                count = min(2 * half, headCount - blendStart),
                stride = width,
                incomingStride = width,
            )
            surface.writeRows(pageTop + blendStart, previous, 0, min(2 * half, headCount - blendStart))
        }
        val rest = headCount - blendEnd
        if (rest > 0) {
            surface.writeRows(pageTop + blendEnd, head, blendEnd * width, rest)
        }
    }
}

/** The app's output surface: one mutable ARGB_8888 bitmap, written with `setPixels`. */
object BitmapSurfaces : PageSurfaceFactory {
    override fun create(width: Int, height: Int): PageSurface = BitmapSurface(width, height)
}

private class BitmapSurface(override val width: Int, override val height: Int) : PageSurface {
    private val bitmap: Bitmap = checkNotNull(
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
    ) { "could not allocate a ${width}x$height bitmap" }

    override fun readRows(y: Int, rowCount: Int, out: IntArray) {
        bitmap.getPixels(out, 0, width, 0, y, width, rowCount)
    }

    override fun writeRows(y: Int, pixels: IntArray, offset: Int, rowCount: Int) {
        bitmap.setPixels(pixels, offset, width, 0, y, width, rowCount)
    }

    override fun toResult(modelId: String, bandCount: Int, tileSize: Int): UpscaleResult =
        UpscaleResult(width, height, modelId, bitmap, null, bandCount, tileSize)

    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

/**
 * The JVM-test / small-page surface: the page as one ARGB buffer.
 *
 * Not the engine's default — a full-size `IntArray` is exactly what the band splitting exists to
 * avoid — but it is the surface the tile pipeline is verified against.
 */
class ArgbPageSurface(override val width: Int, override val height: Int) : PageSurface {
    val pixels: IntArray = IntArray(width * height)

    override fun readRows(y: Int, rowCount: Int, out: IntArray) {
        pixels.copyInto(out, 0, y * width, (y + rowCount) * width)
    }

    override fun writeRows(y: Int, pixels: IntArray, offset: Int, rowCount: Int) {
        pixels.copyInto(this.pixels, y * width, offset, offset + rowCount * width)
    }

    override fun toResult(modelId: String, bandCount: Int, tileSize: Int): UpscaleResult =
        UpscaleResult(width, height, modelId, null, pixels, bandCount, tileSize)
}

/** A surface factory for [ArgbPageSurface], i.e. "assemble into a buffer". */
object ArgbSurfaces : PageSurfaceFactory {
    override fun create(width: Int, height: Int): PageSurface = ArgbPageSurface(width, height)
}
