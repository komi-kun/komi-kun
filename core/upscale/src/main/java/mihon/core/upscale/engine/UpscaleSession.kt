// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The engine's Android-free seam: what a tile inference is, what it is fed, and the failures the
// pipeline distinguishes.
//
// [TileInference] is the whole of what the engine needs from a model, so the tile pipeline in
// `UpscaleEngine` runs in a plain JVM test against a fake (`UpscalePipelineTest`) — no device, no
// ONNX Runtime, no native library. The real implementation is `OrtTileInference`; nothing in this
// file (or in the engine) touches Android except the output surface.
// KUN <--
package mihon.core.upscale.engine

/**
 * One decoded page: tightly packed ARGB_8888 pixels, row-major, with the alpha channel already
 * forced opaque (see `PageDecoder`).
 *
 * The pixels stay on the Java heap for the whole page on purpose: the engine crops tiles out of it in
 * input space, so a band never needs the *source* re-decoded. Only the *output* is streamed (see
 * `UpscaleEngine`'s header).
 */
class DecodedPage(
    val argb: IntArray,
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "page must be non-empty: ${width}x$height" }
        require(argb.size >= width * height) {
            "page holds ${argb.size} pixel(s), need ${width * height}"
        }
    }
}

/**
 * One model input: a padded tile in interleaved RGB, `0..1`.
 *
 * The engine builds the variant [TileInference.isFloat16] asks for, so a float16 model saves the
 * float32 copy instead of converting it again.
 */
sealed interface TileInput {
    /** Width of the input tile, in pixels, including both reflection pads. */
    val width: Int

    /** Height of the input tile, in pixels, including both reflection pads. */
    val height: Int

    /** `width * height * 3` interleaved RGB values in `0..1`. */
    class Float32(
        val rgb: FloatArray,
        override val width: Int,
        override val height: Int,
    ) : TileInput {
        init {
            require(rgb.size >= width * height * 3) {
                "tile holds ${rgb.size} value(s), need ${width * height * 3}"
            }
        }
    }

    /**
     * `width * height * 3` interleaved RGB values as IEEE-754 binary16 bit patterns (the raw `short`
     * a float16 ONNX tensor is built from), each in `0..1`.
     */
    class Float16(
        val rgb: ShortArray,
        override val width: Int,
        override val height: Int,
    ) : TileInput {
        init {
            require(rgb.size >= width * height * 3) {
                "tile holds ${rgb.size} value(s), need ${width * height * 3}"
            }
        }
    }
}

/**
 * Runs one model over one padded tile.
 *
 * [infer] is handed the tile in the variant [isFloat16] names and returns the upscaled tile as
 * interleaved RGB floats in `0..1`, `scale * width` by `scale * height`. An implementation owns
 * native memory, so it is [AutoCloseable]; the engine closes it when the model file or the tensor
 * shape changes, and on `close()`.
 */
interface TileInference : AutoCloseable {
    /** Whether [infer] wants [TileInput.Float16] rather than [TileInput.Float32]. */
    val isFloat16: Boolean

    /**
     * Upscales one padded tile.
     *
     * Every returned value must be finite and inside `0..1`-ish range (the engine clamps when it
     * converts to bytes); a NaN or an infinity is a broken model and must already have been rejected
     * by the implementation — see [UpscaleNonFiniteException].
     */
    fun infer(input: TileInput): FloatArray

    override fun close() {}
}

/**
 * The ONNX file does not honour the contract the engine relies on: input named `input`, output named
 * `output`, one float32 or float16 NCHW tensor in, one out of `scale` times the input size. The
 * message names what the model actually exposes.
 */
class UpscaleModelContractException(message: String) : Exception(message)

/**
 * The model returned a NaN or an infinity. Naming the model makes the failure actionable (re-install a
 * corrupt download) instead of an opaque blank page.
 */
class UpscaleNonFiniteException(message: String) : Exception(message)

/** The caller's `isCancelled` said stop; no result was produced and nothing may be cached. */
class UpscaleCancelledException : Exception("upscale cancelled")

/**
 * True when [throwable] is an inference allocation failure — the trigger for halving the tile size and
 * retrying the page instead of failing it.
 *
 * ONNX Runtime surfaces GPU/desktop allocation failures as `OrtException` with a provider-specific
 * message (`std::bad_alloc`, `cudaMalloc`, `VK_ERROR_*`, a lost device) and CPU ones as an
 * `OutOfMemoryError`, so both the type and the text are consulted. The match is deliberately narrow:
 * a *model* error must reach the caller as a model error, not be retried with smaller tiles forever.
 */
fun isAllocationFailure(throwable: Throwable): Boolean {
    if (throwable is OutOfMemoryError) return true
    val text = buildString {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 4) {
            append(current.javaClass.name).append(' ')
            append(current.message.orEmpty()).append(' ')
            current = current.cause
            depth++
        }
    }.lowercase()
    return ALLOCATION_MARKERS.any { it in text }
}

private val ALLOCATION_MARKERS = listOf(
    "out of memory",
    "outofmemory",
    "allocate",
    "bad_alloc",
    "std::bad_alloc",
    "cudamalloc",
    "vk_error",
    "vulkan out of",
    "device lost",
    "resource exhausted",
    "failed to allocate",
    "insufficient memory",
)
