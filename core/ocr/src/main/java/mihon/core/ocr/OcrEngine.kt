// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrEngine.kt` @ f85c063.
// KUN <--
package mihon.core.ocr

import android.graphics.Bitmap

/**
 * The single engine seam. Implementations decode [bytes] (a page image) and return the
 * engine-agnostic [EngineLine] IR; everything downstream (merging, caching, overlay) is
 * engine-independent.
 */
interface OcrEngine {
    suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine>

    // KUN -->
    /**
     * Recognises one [bitmap] (a prepared page or a chunk) at detector long side
     * [detectorLimitSide] — the value [mihon.core.ocr.ImageChunk.detectorLimitSide] carries, resolved
     * from the chunk's own long side. Defaults to [DETECTOR_LIMIT_SIDE] for the whole-page callers
     * that have no resolved limit.
     */
    suspend fun recognize(
        bitmap: Bitmap,
        language: OcrLanguage,
        detectorLimitSide: Int = DETECTOR_LIMIT_SIDE,
    ): List<EngineLine> = emptyList()
    // KUN <--
}
