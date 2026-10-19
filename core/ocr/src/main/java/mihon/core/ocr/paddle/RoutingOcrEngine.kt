// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr.paddle

import logcat.LogPriority
import mihon.core.ocr.EngineLine
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrEngineType
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable

/**
 * An [OcrEngine] router that delegates to either the local on-device [PaddleOcrEngine]
 * (PP-OCRv6 Small) or the [PaddleOcrVlEngine] (PaddleOCR-VL-1.6 VLM) based on user preference.
 *
 * When configured for PaddleOCR-VL, it can automatically fall back to PP-OCRv6 if the VL service
 * is offline or fails, ensuring reading flow is not interrupted.
 */
class RoutingOcrEngine(
    private val preferences: OcrPreferences,
    private val localEngine: PaddleOcrEngine,
    private val vlEngine: PaddleOcrVlEngine,
) : OcrEngine, Closeable {

    override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
        val selected = preferences.effectiveEngineType
        return when (selected) {
            OcrEngineType.PP_OCRV6_SMALL -> {
                localEngine.recognize(bytes, language)
            }
            OcrEngineType.PADDLEOCR_VL -> {
                try {
                    val lines = vlEngine.recognize(bytes, language)
                    if (lines.isNotEmpty() || !preferences.paddleVlFallback.get()) {
                        lines
                    } else {
                        logcat(LogPriority.INFO) { "PaddleOCR-VL returned 0 lines, falling back to local PP-OCRv6" }
                        localEngine.recognize(bytes, language)
                    }
                } catch (e: Exception) {
                    if (preferences.paddleVlFallback.get()) {
                        logcat(LogPriority.WARN, e) {
                            "PaddleOCR-VL failed (${e.message}), falling back to local PP-OCRv6"
                        }
                        localEngine.recognize(bytes, language)
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    override fun close() {
        localEngine.close()
    }
}
// KUN <--
