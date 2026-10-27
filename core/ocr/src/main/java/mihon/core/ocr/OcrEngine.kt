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

    /**
     * Identity of the model bytes the engine would load *now* (e.g. the installed release marker
     * plus the model files' own fingerprint), or `null` when the engine has none or cannot tell.
     *
     * Cache keys include it, because every release publishes identically named model files: the
     * selected release says nothing about the bytes on disk, so a page OCR'd after selecting a
     * variant but before its download completed would otherwise be stored under the new release's
     * tag while the old weights produced it.
     */
    fun modelBytesIdentity(): String? = null
    // KUN <--
}
