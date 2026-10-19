// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr.paddle

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * (PP-OCRv6 Small) or PaddleOCR-VL-1.6 (on-device LiteRT-LM or server) based on user preference.
 *
 * When configured for PaddleOCR-VL, it can automatically fall back to PP-OCRv6 if the VL engine
 * is missing/offline or fails, and alerts the user with a prominent warning.
 */
class RoutingOcrEngine(
    private val context: Context,
    private val preferences: OcrPreferences,
    private val localEngine: PaddleOcrEngine,
    private val vlServerEngine: PaddleOcrVlEngine,
    private val vlOnDeviceEngine: PaddleOcrVlOnDeviceEngine,
    private val onFailoverWarning: ((reason: String) -> Unit)? = null,
) : OcrEngine, Closeable {

    private var lastFailoverWarningTime = 0L

    override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
        val selected = preferences.effectiveEngineType
        return when (selected) {
            OcrEngineType.PP_OCRV6_SMALL -> {
                localEngine.recognize(bytes, language)
            }
            OcrEngineType.PADDLEOCR_VL -> {
                val mode = preferences.paddleVlExecutionMode.get()
                val targetEngine = if (mode == OcrPreferences.MODE_ON_DEVICE) {
                    vlOnDeviceEngine
                } else {
                    vlServerEngine
                }

                try {
                    val lines = targetEngine.recognize(bytes, language)
                    if (lines.isNotEmpty() || !preferences.paddleVlFallback.get()) {
                        lines
                    } else {
                        logcat(LogPriority.INFO) { "PaddleOCR-VL returned 0 lines, falling back to local PP-OCRv6" }
                        showFailoverWarning("No text found")
                        localEngine.recognize(bytes, language)
                    }
                } catch (e: Exception) {
                    if (preferences.paddleVlFallback.get()) {
                        val reason = e.localizedMessage ?: e.message ?: "Execution failed"
                        logcat(LogPriority.WARN, e) {
                            "PaddleOCR-VL ($mode) failed ($reason), falling back to local PP-OCRv6"
                        }
                        showFailoverWarning(reason)
                        localEngine.recognize(bytes, language)
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    private suspend fun showFailoverWarning(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastFailoverWarningTime > FAILOVER_WARNING_COOLDOWN_MS) {
            lastFailoverWarningTime = now
            if (onFailoverWarning != null) {
                onFailoverWarning.invoke(reason)
            } else {
                runCatching {
                    withContext(Dispatchers.Main) {
                        val message = "PaddleOCR-VL failed ($reason). Falling back to on-device PP-OCRv6."
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun close() {
        localEngine.close()
        vlOnDeviceEngine.close()
    }

    companion object {
        private const val FAILOVER_WARNING_COOLDOWN_MS = 30_000L
    }
}
// KUN <--
