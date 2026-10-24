// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Reader → Anki `{screenshot}` capture (plan 05 §B4).
//
// The card image is produced *before* the card is created, from the page the lookup came from, so
// `AnkiCardCreator` writes `{screenshot}` inline and no second note update is needed.
// `mihon.core.dictionary.anki.AnkiScreenshotCrop` decides the region (pure, unit-tested); this file
// turns that region into bytes: decode only the region at a bounded size, re-encode as WebP, so the
// media filename (`komikun_<hash>.webp`) always matches the bytes.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import ca.mpreg.imagedecoder.ImageDecoder
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.dictionary.anki.AnkiCropAspect
import mihon.core.dictionary.anki.AnkiScreenshotCrop
import mihon.core.dictionary.anki.AnkiScreenshotMode
import mihon.core.dictionary.anki.AnkiScreenshotProvider
import mihon.core.dictionary.anki.NormalizedCropRect
import mihon.core.ocr.model.OcrTextBlock
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream

/**
 * Captures the `{screenshot}` bytes for one OCR block of a reader page.
 *
 * Everything here is best-effort: a page that cannot be read or decoded yields `null` (the card is
 * still created, just without the image) rather than failing the mining action.
 */
object ReaderOcrScreenshot {

    /** Longest side of the stored image: legible on a card, small enough to store per note. */
    private const val MAX_SIDE_PX = 1024

    private const val QUALITY = 90

    /**
     * The provider for [block] on [page], honouring the screenshot [mode] and crop [aspect] the
     * caller read from the live preferences. Returns null when the mode takes no screenshot at all.
     */
    fun provider(
        page: ReaderPage,
        block: OcrTextBlock,
        mode: AnkiScreenshotMode,
        aspect: AnkiCropAspect?,
    ): AnkiScreenshotProvider? {
        if (mode == AnkiScreenshotMode.NONE) return null
        return AnkiScreenshotProvider {
            // The popup calls this from a main-thread coroutine; the decode is heavy.
            withContext(Dispatchers.IO) {
                try {
                    capture(page, block, mode, aspect)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Anki screenshot: capture failed for page ${page.index}" }
                    null
                }
            }
        }
    }

    private fun capture(
        page: ReaderPage,
        block: OcrTextBlock,
        mode: AnkiScreenshotMode,
        aspect: AnkiCropAspect?,
    ): ByteArray? {
        val openStream = page.stream ?: return null
        openStream().use { input ->
            ImageDecoder.open(input).use { decoder ->
                val region = AnkiScreenshotCrop.rectFor(
                    mode = mode,
                    block = NormalizedCropRect(block.xmin, block.ymin, block.xmax, block.ymax),
                    pageWidth = decoder.width,
                    pageHeight = decoder.height,
                    aspect = aspect,
                ) ?: return null

                val crop = AnkiScreenshotCrop.pixelRectFor(region, decoder.width, decoder.height) ?: return null
                val sampleSize = AnkiScreenshotCrop.sampleSizeFor(crop, MAX_SIDE_PX)
                // Decodes only the crop, shrunk while decoding, so the page never exists as one
                // bitmap (a 4× upscaled page is 30–40 MP — plan 05 §B4, handoff §8).
                //
                // Contract checked against `ca.mpreg:imagedecoder:17`'s source
                // (`ImageDecoder.decode`): the region is "in the oriented coordinates of [width] and
                // [height]", shrunk by `ceil(size / sampleSize)` while decoding, and it must be
                // non-empty and inside the image (`checkRegion`) — which `pixelRectFor` guarantees.
                val bitmap = decoder.decodeToBitmap(
                    Rect(crop.left, crop.top, crop.right, crop.bottom),
                    sampleSize,
                )
                return try {
                    encodeWebp(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun encodeWebp(bitmap: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, QUALITY, out)) { "WebP encode failed" }
        return out.toByteArray()
    }
}
// KUN <--
