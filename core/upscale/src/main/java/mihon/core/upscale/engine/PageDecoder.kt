// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Compressed page bytes → `DecodedPage`, using the decoder the app already ships
// (`ca.mpreg.imagedecoder.ImageDecoder`, the same one Coil and OCR decode with).
//
// The raw frame is preferred over `decodeToBitmap`: the frame is a 4 B/px buffer the decoder already
// owns, so the page becomes one ARGB `IntArray` instead of an `IntArray` *and* a `Bitmap`.
//
// **Channel order.** `ImageDecoder.Frame.image` is the buffer the app copies straight into an
// ARGB_8888 bitmap (`Bitmap.copyPixelsFromBuffer`, see `ReaderViewModel.saveImages` and
// `PagerPageHolder.decodeImage`), i.e. the bytes are one native-order `int` per pixel. Reading them
// that way — rather than assuming "RGBA" from the field's name — is what makes the upscaler see the
// same colours the reader draws.
//
// **Alpha is dropped, never composited.** A page is opaque art; the alpha byte is forced to `0xFF`
// so a source with an unused/garbage alpha channel cannot darken every pixel (compositing onto black
// is exactly the bug this avoids).
//
// **Rejected pages.** HDR / 16-bit-per-channel frames and anything over [MAX_INPUT_PIXELS] return
// `null`: the caller keeps the original page instead of running a model over something it was not
// trained on, or allocating an unbounded buffer for a pathological page.
// KUN <--
package mihon.core.upscale.engine

import ca.mpreg.imagedecoder.ImageDecoder
import java.io.ByteArrayInputStream
import java.nio.ByteOrder

/** Decodes page bytes into the ARGB page the engine crops tiles from. */
object PageDecoder {

    /**
     * Largest page the decoder will produce: 40 MP is 160 MB as an ARGB `IntArray`, and beyond it the
     * page is not worth the memory (a 2x upscale of it would be 160 MP). Returns `null` above it.
     */
    const val MAX_INPUT_PIXELS: Long = 40_000_000L

    private const val OPAQUE = 0xFF shl 24

    /**
     * Decodes [bytes], or returns `null` when the page cannot be upscaled (HDR, too large, or an
     * unreadable/corrupt image).
     *
     * Blocking and CPU/IO heavy: call it on the engine's inference thread, never on the main one.
     */
    fun decode(bytes: ByteArray): DecodedPage? {
        if (bytes.isEmpty()) return null
        return try {
            ImageDecoder.open(ByteArrayInputStream(bytes)).use { decoder ->
                // HDR frames are 8 B/px (`RGBA_F16`): their values are not `byte / 255`, so the
                // model's normalisation would be wrong.
                if (decoder.isHdr) return null
                val width = decoder.width
                val height = decoder.height
                if (width <= 0 || height <= 0) return null
                if (width.toLong() * height > MAX_INPUT_PIXELS) return null
                decoder.decodeNext().use { frame ->
                    val frameWidth = frame.width
                    val frameHeight = frame.height
                    if (frameWidth <= 0 || frameHeight <= 0) return null
                    if (frameWidth.toLong() * frameHeight > MAX_INPUT_PIXELS) return null
                    DecodedPage(
                        argb = toArgb(frame.image, frameWidth, frameHeight),
                        width = frameWidth,
                        height = frameHeight,
                    )
                }
            }
        } catch (_: Exception) {
            // An unreadable page is not an error of the reader: it just stays at its own resolution.
            null
        }
    }

    /** One native-order `int` per pixel, alpha forced opaque (see the file header). */
    private fun toArgb(image: java.nio.ByteBuffer, width: Int, height: Int): IntArray {
        val argb = IntArray(width * height)
        val buffer = image.duplicate().order(ByteOrder.nativeOrder())
        buffer.rewind()
        buffer.asIntBuffer().get(argb)
        for (index in argb.indices) {
            argb[index] = argb[index] or OPAQUE
        }
        return argb
    }
}
