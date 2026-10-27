// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrBitmapDecoder.kt` @ f85c063.
// Adapted to komi-kun's decoder (`ca.mpreg:imagedecoder`), which replaced the dropped
// `tachiyomi.decoder` API; the mpreg decoder returns RGBA frames instead of `Bitmap`s.
// The page is now shrunk *while* decoding (`decodeToBitmap(null, sample)`) instead of decoding the
// full native frame and copying it into a full-size `Bitmap` first: those two buffers are 4 B/px
// each and were alive together (432 MB for a 6000x9000 page) before the 4 MP budget clamp ran.
// KUN <--
package mihon.core.ocr

import android.graphics.Bitmap
import ca.mpreg.imagedecoder.ImageDecoder
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// KUN -->
/**
 * A page format the decoder can meet, and whether [ImageDecoder.decodeToBitmap] shrinks it with a
 * *filtered* kernel.
 *
 * JPEG, WebP and JPEG-2000 ask their library for the target resolution (`jpeg_set_scaled_size`,
 * libwebp's scaler, OpenJPEG's reduce) so the sampled page keeps its detail. PNG, AVIF, JXL, HEIF,
 * TIFF and GIF are decoded at full size and then *decimated*: each output pixel is the block's
 * top-left source pixel (nearest-like), so an over-sampled page loses line structure. Those formats
 * therefore only get a pre-shrink big enough to bound memory (see
 * [OcrBitmapDecoder.NON_NATIVE_MAX_PIXELS]) and leave the finishing, filtered resize to
 * [prepareForOcr].
 */
enum class OcrImageFormat(val scalesNatively: Boolean) {
    JPEG(true),
    WEBP(true),
    JP2(true),
    PNG(false),
    AVIF(false),
    HEIF(false),
    JXL(false),
    TIFF(false),
    GIF(false),
    UNKNOWN(false),
}
// KUN <--

/** Decodes page bytes to a software ARGB_8888 bitmap for OCR. */
object OcrBitmapDecoder {

    // KUN -->
    /**
     * Largest bitmap a non-native-scaling format may be pre-shrunk to: 8 MP * 4 B/px ≈ 32 MB. Above
     * this the decimation's detail loss is not worth the memory it saves, so the page is decoded
     * down to this cap and the filtered resize in [prepareForOcr] finishes to [MAX_OCR_PIXELS].
     */
    const val NON_NATIVE_MAX_PIXELS = 8_000_000
    // KUN <--

    /**
     * Decodes [data], shrinking it to the OCR pixel budget while decoding.
     *
     * Prefer this overload: the sample factor is derived from the decoder's own header, so a caller
     * cannot forget it and decode the page full-size.
     */
    fun decode(data: ByteArray): Bitmap = openAndDecode(data) { decoder ->
        sampleSizeFor(decoder.width, decoder.height, data)
    }

    /**
     * Decodes [data] at an explicit [sampleSize], for callers that already measured the page and
     * resolved the sample with [sampleSizeFor]. `sampleSize` must come from that helper — it is the
     * format-aware factor, not a raw power of two.
     */
    fun decode(data: ByteArray, sampleSize: Int): Bitmap {
        require(sampleSize >= 1) { "sampleSize must be >= 1, was $sampleSize" }
        return openAndDecode(data) { sampleSize }
    }

    private fun openAndDecode(data: ByteArray, sampleResolver: (ImageDecoder) -> Int): Bitmap {
        require(data.isNotEmpty()) { "Image data is empty" }

        return try {
            ImageDecoder.open(ByteArrayInputStream(data)).use { decoder ->
                decoder.decodeToBitmap(null, sampleResolver(decoder))
            }
        } catch (e: Exception) {
            throw IOException("Failed to decode image for OCR", e)
        }
    }

    // KUN -->
    /**
     * Sample factor for a [width] x [height] page, from the format detected in [data]'s magic bytes.
     *
     * Callers that already measured the page ([width]/[height] from a bounds-only read) use this so
     * the decode never materialises the full-size page.
     */
    fun sampleSizeFor(width: Int, height: Int, data: ByteArray): Int =
        sampleSizeFor(width, height, formatOf(data))

    /**
     * Sample factor for a [width] x [height] page of known [format].
     *
     * A tall strip — a page whose `height / width` exceeds [WEBTOON_ASPECT_RATIO], the same ratio
     * [prepareForOcr] uses to skip the budget — is never sampled: it is returned at 1 so the decode
     * keeps native resolution. The strip path tiles such a page at native resolution on purpose, and
     * a whole-page pre-shrink would halve the text size *before* those tiles are cut, which tiling
     * cannot undo. So the budget below only applies to pages that reach the resize path at all.
     *
     * Everything else is brought inside [MAX_OCR_PIXELS] by `ceil(sqrt(pixels / budget))` — a
     * 4500x6400 page samples at 3, a 6000x9000 page at 4. Formats that scale natively stop there; the
     * ones that decimate are held to [NON_NATIVE_MAX_PIXELS] instead, so 12000x18000 pre-shrinks at
     * 6 rather than 8 and the filtered resize keeps the detail that is left.
     */
    fun sampleSizeFor(width: Int, height: Int, format: OcrImageFormat): Int {
        // Same predicate as the strip bypass in `ImageChunking.prepareForOcr`, so the two cannot
        // disagree about which pages are strips. Strictly greater: a page at exactly the ratio is
        // an ordinary page and takes the budget path.
        if (width > 0 && height.toFloat() / width > WEBTOON_ASPECT_RATIO) return 1

        val budgeted = sampleToFit(width, height, MAX_OCR_PIXELS)
        if (format.scalesNatively) return budgeted
        return min(budgeted, sampleToFit(width, height, NON_NATIVE_MAX_PIXELS))
    }

    private fun sampleToFit(width: Int, height: Int, maxPixels: Int): Int {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return 1

        var sample = max(1, ceil(sqrt(pixels.toDouble() / maxPixels)).toInt())
        // `ceil(sqrt(...))` is exact for every real page; this only repairs float noise so the
        // decoded bitmap can never come out a pixel-count over its budget.
        while (sampledDimensions(width, height, sample).let { it.first.toLong() * it.second > maxPixels }) {
            sample++
        }
        return sample
    }

    /**
     * Size [ImageDecoder.decodeToBitmap] returns for a [width] x [height] page decoded at
     * [sampleSize]. The library allocates `ceil(w / sample) x ceil(h / sample)` and fills it from
     * the native decode, so this is also the bitmap's memory cost.
     */
    fun sampledDimensions(width: Int, height: Int, sampleSize: Int): Pair<Int, Int> {
        val sample = sampleSize.coerceAtLeast(1)
        return ceilDiv(width, sample) to ceilDiv(height, sample)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1) / divisor).toInt().coerceAtLeast(1)

    /**
     * Format of [data] from its magic bytes. Inconclusive input is [OcrImageFormat.UNKNOWN], which
     * is treated as a format that does not scale natively — the conservative choice, since guessing
     * "native" would over-sample a decimating decoder.
     */
    fun formatOf(data: ByteArray): OcrImageFormat = when {
        data.matches(0, 0xFF, 0xD8, 0xFF) -> OcrImageFormat.JPEG
        data.matches(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> OcrImageFormat.PNG
        data.asciiAt(0, 4) == "RIFF" && data.asciiAt(8, 4) == "WEBP" -> OcrImageFormat.WEBP
        data.asciiAt(0, 6).let { it == "GIF87a" || it == "GIF89a" } -> OcrImageFormat.GIF
        data.matches(0, 0x49, 0x49, 0x2A, 0x00) || data.matches(0, 0x4D, 0x4D, 0x00, 0x2A) ->
            OcrImageFormat.TIFF
        // JPEG-2000: the JP2 signature box, or a bare codestream (SOC + SIZ markers).
        data.matches(0, 0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A) ||
            data.matches(0, 0xFF, 0x4F, 0xFF, 0x51) -> OcrImageFormat.JP2
        // JPEG XL: the container's signature box, or a bare codestream.
        data.matches(0, 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A) ||
            data.matches(0, 0xFF, 0x0A) -> OcrImageFormat.JXL
        else -> data.isoBmffBrand()
    }

    /** AVIF/HEIF are ISO-BMFF: `ftyp` box at offset 4, major brand at offset 8. */
    private fun ByteArray.isoBmffBrand(): OcrImageFormat {
        if (asciiAt(4, 4) != "ftyp") return OcrImageFormat.UNKNOWN
        return when (asciiAt(8, 4)) {
            "avif", "avis" -> OcrImageFormat.AVIF
            "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "mif2", "msf1" ->
                OcrImageFormat.HEIF
            else -> OcrImageFormat.UNKNOWN
        }
    }

    private fun ByteArray.matches(offset: Int, vararg signature: Int): Boolean {
        if (offset + signature.size > size) return false
        for (i in signature.indices) {
            if (this[offset + i].toInt() and 0xFF != signature[i]) return false
        }
        return true
    }

    private fun ByteArray.asciiAt(offset: Int, length: Int): String? {
        if (offset + length > size) return null
        return String(this, offset, length, Charsets.US_ASCII)
    }
    // KUN <--
}

// KUN -->
/**
 * The page size a sampling decision must use: the decoder header ([header]) when it is usable, else
 * the stored-orientation bounds ([stored]), else [fallback] on both axes.
 *
 * [ImageDecoder] applies the EXIF orientation while decoding, so its `width`/`height` are the
 * dimensions the page is *decoded* at. `BitmapFactory`'s bounds report the stored orientation, so a
 * 12000x2000 landscape strip tagged "rotate 90" measures 12000x2000 there but 2000x12000 here —
 * and [OcrBitmapDecoder.sampleSizeFor]'s tall-strip bypass keys on that aspect ratio. Precedence is
 * a pure function so it can be tested without a device decoder.
 */
internal fun samplingDimensions(
    header: Pair<Int, Int>?,
    stored: Pair<Int, Int>?,
    fallback: Int,
): Pair<Int, Int> {
    val usable = { dims: Pair<Int, Int>? -> dims?.takeIf { (width, height) -> width > 0 && height > 0 } }
    return usable(header) ?: usable(stored) ?: (fallback to fallback)
}
// KUN <--
