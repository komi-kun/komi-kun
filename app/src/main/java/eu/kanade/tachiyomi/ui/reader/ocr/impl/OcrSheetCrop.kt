// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The border crop SSIV applies to a sheet it displays, for the OCR overlay's coordinate mapping.
//
// OCR runs on the raw page, so its block coordinates are normalized to the *uncropped* image. With
// crop borders on, SSIV displays a cropped sheet instead and `sWidth`/`sHeight` (and therefore
// `sourceToViewCoord`) are in that cropped space - without the rect, every block is placed as if the
// page were uncropped: a constant offset plus a constant scale, independent of zoom.
//
// SSIV keeps the rect to itself: `SubsamplingScaleImageView.Decoder.init` computes it into private
// `cropLeft`/`cropTop`/`cropWidth`/`cropHeight` and exposes no accessor, and `sourceToViewX` has no
// region subtraction, so the rect cannot be read back out of the view. It is therefore *derived* here
// with the same routine and the same recipe SSIV uses (`CropBorders.findCropBorders` on a decode of
// at most 4 MP, padded by one sampled pixel on each side and clamped to the sheet), and its *extent*
// is checked against the size SSIV reports for the sheet it cropped - see
// `ReaderPageImageView.resolveOcrCropWindow`.
//
// What that check can and cannot catch: SSIV exposes only the cropped *size* (`sWidth`/`sHeight`), so
// a drift that changed the size is caught and falls back to the pre-fix uncropped mapping, while a
// drift that only moved the rect's origin while keeping its size would pass and shift every block by
// a constant offset. Nothing else about the rect is observable, so the real guard for the origin is
// the *pinned* SSIV version plus a device check with crop and OCR both on; a SSIV bump must re-run
// that check, and these recipe tests will stay green across such a bump by design (they pin our port
// of the recipe, not the library's behaviour).
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import ca.mpreg.imagedecoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.CropBorders
import eu.kanade.tachiyomi.ui.reader.ocr.OcrCropWindow
import okio.BufferedSource
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A border crop in sheet pixels: [left]/[top] is the kept region's origin and [width]/[height] its
 * size, all in [sheetWidth]×[sheetHeight] pixels of the image the viewer was handed.
 */
internal data class OcrSheetCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val sheetWidth: Int,
    val sheetHeight: Int,
) {
    /**
     * Whether SSIV's own [sWidth]/[sHeight] are this crop's extent - the check that the derived rect
     * describes the displayed bitmap. Only the extent is observable (SSIV exposes the cropped size and
     * nothing about its origin), so this catches a recipe that drifted in size, not one that moved.
     */
    fun matches(sWidth: Int, sHeight: Int): Boolean = width == sWidth && height == sHeight

    /** The crop as fractions of the uncropped sheet; `null` when it trimmed nothing (or is invalid). */
    fun window(): OcrCropWindow? = OcrCropWindow.ofPixels(left, top, width, height, sheetWidth, sheetHeight)
}

/**
 * The window to apply for a derived crop, or `null` when it is not the crop SSIV produced.
 *
 * [sWidth]/[sHeight] are SSIV's own cropped dimensions - the only part of its rect it exposes. A
 * recipe that has drifted from the pinned SSIV *in extent* (or a decode that disagreed) therefore
 * reports a different size and nothing is applied at all: the overlay keeps the pre-fix uncropped
 * mapping instead of scaling every block by a stale ratio. A mismatch must yield `null`, never a
 * "close enough" window.
 *
 * An origin-only drift is **not** caught here (same size, different start): see the note at the top
 * of this file - the pinned SSIV version and a device check are that guard.
 */
internal fun OcrSheetCrop?.resolvedAgainst(sWidth: Int, sHeight: Int): OcrCropWindow? =
    this?.takeIf { it.matches(sWidth, sHeight) }?.window()

/** SSIV scans borders on a decode of about 4 MP; the same bound keeps its rect and ours identical. */
private const val CROP_SCAN_PIXELS = 4_000_000.0

/** The sample size SSIV's `Decoder.init` decodes at to find the borders. */
internal fun ssivCropSampleSize(width: Int, height: Int): Int =
    max(1, ceil(sqrt(width.toDouble() * height / CROP_SCAN_PIXELS)).toInt())

/**
 * SSIV's `Decoder.init` crop decision, in sheet pixels. [found] is what `CropBorders.findCropBorders`
 * returned (`[left, top, width, height]`, in pixels of the [frameWidth]×[frameHeight] decode), and
 * [sampleSize] that decode's sample size.
 *
 * Returns `null` where SSIV keeps the whole sheet: no usable rect, a rect outside the frame, or one
 * that collapses after padding. Ported term for term from the pinned SSIV fork - at a sample size
 * above 1 SSIV widens the rect by one sampled pixel on each side and snaps it to the sample grid.
 */
internal fun ssivCropRect(
    found: IntArray?,
    frameWidth: Int,
    frameHeight: Int,
    sheetWidth: Int,
    sheetHeight: Int,
    sampleSize: Int,
): IntArray? {
    if (found == null || found.size < 4) return null
    val (left, top, width, height) = found
    if (width <= 0 || height <= 0 || left < 0 || top < 0) return null
    if (left + width > frameWidth || top + height > frameHeight) return null

    val pad = if (sampleSize > 1) 1 else 0
    val cropLeft = max(left - pad, 0) * sampleSize
    val cropTop = max(top - pad, 0) * sampleSize
    val cropRight = min((left + width + pad).toLong() * sampleSize, sheetWidth.toLong()).toInt()
    val cropBottom = min((top + height + pad).toLong() * sampleSize, sheetHeight.toLong()).toInt()
    if (cropRight <= cropLeft || cropBottom <= cropTop) return null

    return intArrayOf(cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
}

/**
 * Derives the border crop SSIV will apply to [sheet], or `null` when it will keep the whole sheet
 * (no borders found, HDR - which SSIV never crops - or a decode failure).
 *
 * **Decodes the sheet, so call it off the main thread.** [sheet] is only peeked, never consumed: the
 * viewer still gets the whole stream.
 *
 * Swallows `Throwable`, not `Exception`: this runs *before* the viewer's own decode and is optional,
 * and the callers wrap their decode in `catch (e: Throwable) { setError(e) }` - so an `Error` escaping
 * from here (an `OutOfMemoryError` in the extra ≤4 MP decode) would turn a page that might still have
 * rendered into the error layout. No failure of this scan is worth failing a page for; the overlay
 * simply falls back to the uncropped mapping.
 */
internal fun ssivCropScan(sheet: BufferedSource): OcrSheetCrop? {
    return try {
        val peeked = sheet.peek()
        ImageDecoder.open(peeked.inputStream()).use { decoder ->
            // SSIV skips border cropping for HDR frames (the routine assumes 4 bytes per pixel).
            if (decoder.isHdr || decoder.width <= 0 || decoder.height <= 0) return null
            val sampleSize = ssivCropSampleSize(decoder.width, decoder.height)
            val rect = decoder.decode(null, sampleSize).use { frame ->
                ssivCropRect(
                    found = CropBorders.findCropBorders(frame.toByteArray(), frame.width, frame.height),
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    sheetWidth = decoder.width,
                    sheetHeight = decoder.height,
                    sampleSize = sampleSize,
                )
            } ?: return null
            OcrSheetCrop(
                left = rect[0],
                top = rect[1],
                width = rect[2],
                height = rect[3],
                sheetWidth = decoder.width,
                sheetHeight = decoder.height,
            ).takeIf { it.width != it.sheetWidth || it.height != it.sheetHeight }
        }
    } catch (_: Throwable) {
        null
    }
}
