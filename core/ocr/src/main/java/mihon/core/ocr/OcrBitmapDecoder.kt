// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrBitmapDecoder.kt` @ f85c063.
// Adapted to komi-kun's decoder (`ca.mpreg:imagedecoder`), which replaced the dropped
// `tachiyomi.decoder` API; the mpreg decoder returns RGBA frames instead of `Bitmap`s.
// KUN <--
package mihon.core.ocr

import android.graphics.Bitmap
import ca.mpreg.imagedecoder.ImageDecoder
import java.io.ByteArrayInputStream
import java.io.IOException

/** Decodes page bytes to a software ARGB_8888 bitmap for OCR. */
object OcrBitmapDecoder {

    fun decode(data: ByteArray, sampleSize: Int = 1): Bitmap {
        require(data.isNotEmpty()) { "Image data is empty" }

        val frame = try {
            ImageDecoder.open(ByteArrayInputStream(data)).use { it.decodeNext() }
        } catch (e: Exception) {
            throw IOException("Failed to decode image for OCR", e)
        }

        return try {
            val full = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            frame.image.rewind()
            full.copyPixelsFromBuffer(frame.image)
            if (sampleSize > 1) {
                val scaled = Bitmap.createScaledBitmap(
                    full,
                    (frame.width / sampleSize).coerceAtLeast(1),
                    (frame.height / sampleSize).coerceAtLeast(1),
                    true,
                )
                full.recycle()
                scaled
            } else {
                full
            }
        } finally {
            frame.close()
        }
    }
}
