// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrEngine.kt` @ f85c063.
// KUN <--
package mihon.core.ocr

/**
 * The single engine seam. Implementations decode [bytes] (a page image) and return the
 * engine-agnostic [EngineLine] IR; everything downstream (merging, caching, overlay) is
 * engine-independent.
 */
interface OcrEngine {
    suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine>
}
