// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr.paddle

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.ocr.EngineLine
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File

/**
 * On-device implementation of PaddleOCR-VL-1.6 using Google AI Edge LiteRT-LM.
 *
 * Runs the .litertlm format model package directly on Android GPU or CPU delegates,
 * providing high-accuracy vision-language OCR on powerful mobile hardware without
 * requiring a network connection or external server.
 */
class PaddleOcrVlOnDeviceEngine(
    private val context: Context,
    private val preferences: OcrPreferences,
) : OcrEngine, Closeable {

    private var engine: Engine? = null
    private val mutex = Mutex()

    fun getModelFile(): File {
        val customPath = preferences.paddleVlModelPath.get()
        if (customPath.isNotBlank()) {
            val file = File(customPath)
            if (file.exists()) return file
        }
        val defaultDir = context.filesDir.resolve("paddleocr_vl")
        return defaultDir.resolve("PaddleOCR-VL-1.6.litertlm")
    }

    fun isModelAvailable(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 0
    }

    private suspend fun ensureInitialized(): Engine = mutex.withLock {
        engine?.let { return it }

        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            throw IllegalStateException(
                "PaddleOCR-VL model not found at ${modelFile.absolutePath}. Please download or place the .litertlm file there.",
            )
        }

        withContext(Dispatchers.IO) {
            logcat(LogPriority.INFO) { "Initializing on-device PaddleOCR-VL LiteRT-LM engine from ${modelFile.absolutePath}" }
            // Attempt GPU backend first; fall back to CPU if GPU delegate is unavailable or incompatible
            val initializedEngine = try {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                )
                Engine(config).also { it.initialize() }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "GPU backend initialization failed for LiteRT-LM; falling back to CPU" }
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                )
                Engine(config).also { it.initialize() }
            }
            engine = initializedEngine
            initializedEngine
        }
    }

    override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
        val eng = ensureInitialized()
        val prompt = preferences.paddleVlPrompt.get().ifBlank { "Spotting:" }

        val contents = Contents.of(
            Content.Text(prompt),
            Content.ImageBytes(bytes),
        )

        val responseText = StringBuilder()
        withContext(Dispatchers.IO) {
            eng.createConversation().use { conversation ->
                conversation.sendMessageAsync(contents).collect { message ->
                    for (content in message.contents.contents) {
                        if (content is Content.Text) {
                            responseText.append(content.text)
                        }
                    }
                }
            }
        }

        val rawOutput = responseText.toString()
        logcat(LogPriority.DEBUG) { "On-device PaddleOCR-VL output length: ${rawOutput.length}" }
        return PaddleOcrVlParser.parse(rawOutput, language)
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
// KUN <--
