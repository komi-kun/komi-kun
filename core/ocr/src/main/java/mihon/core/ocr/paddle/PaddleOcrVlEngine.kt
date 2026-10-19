// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr.paddle

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import mihon.core.ocr.EngineLine
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OCR Engine implementation utilizing PaddleOCR-VL-1.6 (0.9B Vision-Language Model).
 *
 * Communicates with an OpenAI-compatible vision completion endpoint (such as vLLM or llama-server)
 * executing `PaddlePaddle/PaddleOCR-VL-1.6` with the `Spotting:` prompt.
 */
class PaddleOcrVlEngine(
    private val preferences: OcrPreferences,
    baseClient: OkHttpClient? = null,
) : OcrEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> = withContext(Dispatchers.IO) {
        val endpoint = preferences.paddleVlEndpoint.get().trim().trimEnd('/')
        if (endpoint.isBlank()) {
            logcat(LogPriority.WARN) { "PaddleOCR-VL endpoint is empty, skipping recognition" }
            return@withContext emptyList()
        }

        val url = if (endpoint.endsWith("/v1/chat/completions")) {
            endpoint
        } else {
            "$endpoint/v1/chat/completions"
        }

        val prompt = preferences.paddleVlPrompt.get().ifBlank { "Spotting:" }
        val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUri = "data:image/jpeg;base64,$base64Image"

        val requestJson = buildString {
            append("""{"model":"PaddlePaddle/PaddleOCR-VL-1.6","messages":[{"role":"user","content":[""")
            append("""{"type":"image_url","image_url":{"url":"""")
            append(dataUri)
            append("""}},{"type":"text","text":"""")
            append(escapeJson(prompt))
            append(""""}]}],"temperature":0.0,"max_tokens":4096}""")
        }

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(jsonMediaType))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    val code = res.code
                    val errorBody = res.body?.string()?.take(500)
                    logcat(LogPriority.WARN) { "PaddleOCR-VL request failed (HTTP $code): $errorBody" }
                    throw IOException("HTTP $code: $errorBody")
                }

                val bodyString = res.body?.string().orEmpty()
                val parsedText = extractContent(bodyString)
                if (parsedText.isBlank()) {
                    logcat(LogPriority.INFO) { "PaddleOCR-VL returned empty response content" }
                    return@withContext emptyList()
                }

                PaddleOcrVlParser.parse(parsedText, language)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "PaddleOCR-VL inference failed: ${e.message}" }
            throw e
        }
    }

    private fun extractContent(responseBody: String): String {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val choices = root["choices"]?.jsonArray
            val firstChoice = choices?.firstOrNull()?.jsonObject
            val message = firstChoice?.get("message")?.jsonObject
            message?.get("content")?.jsonPrimitive?.content.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun escapeJson(input: String): String {
        val out = StringBuilder()
        for (ch in input) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
// KUN <--
