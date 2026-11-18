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
// at most 4 MP, padded by one sampled pixel on each side and clamped to the sheet), and the result is
// only trusted once SSIV reports exactly the cropped size it produced - see
// `ReaderPageImageView.resolveOcrCropWindow`. A drifted recipe then falls back to the pre-fix
// uncropped mapping instead of shifting every block by a stale offset.
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
     * Whether SSIV's own [sWidth]/[sHeight] are this crop - the check that the derived rect is the
     * one the displayed bitmap was cut with.
     */
    fun matches(sWidth: Int, sHeight: Int): Boolean = width == sWidth && height == sHeight

    /** The crop as fractions of the uncropped sheet; `null` when it trimmed nothing (or is invalid). */
    fun window(): OcrCropWindow? = OcrCropWindow.ofPixels(left, top, width, height, sheetWidth, sheetHeight)
}

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
    } catch (_: Exception) {
        null
    }
}
// KUN <--
