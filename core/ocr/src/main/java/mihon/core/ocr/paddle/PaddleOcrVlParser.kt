// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr.paddle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.core.ocr.EngineLine
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.WritingDirection
import kotlin.math.max
import kotlin.math.min

/**
 * Parses PaddleOCR-VL text spotting outputs into engine-agnostic [EngineLine]s.
 *
 * Supports:
 * - Bracketed coordinates: `[ymin, xmin, ymax, xmax] text` or `[xmin, ymin, xmax, ymax] text`
 * - XML box tags: `<box>[ymin, xmin, ymax, xmax]</box> text` or `<box>(ymin, xmin, ymax, xmax)</box> text`
 * - JSON responses: `[{"bbox": [x1, y1, x2, y2], "text": "..."}]`
 * - Normalized (0.0–1.0) and 1000-scaled (0–1000) coordinate spaces
 */
object PaddleOcrVlParser {

    private val JSON_PARSER = Json { ignoreUnknownKeys = true }

    // Pattern matching [n1, n2, n3, n4] or (n1, n2, n3, n4)
    private val COORD_PATTERN = Regex("""(?:<box>)?[\(\[]\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*[\)\]](?:</box>)?\s*:?\s*(.*)""")

    // Pattern for 4-point polygon: [[x1, y1], [x2, y2], [x3, y3], [x4, y4]] text
    private val POLY_PATTERN = Regex("""\[\s*\[\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\]\s*,\s*\[\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\]\s*,\s*\[\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\]\s*,\s*\[\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\]\s*\]\s*:?\s*(.*)""")

    /**
     * Parses the raw model output [response] into a list of [EngineLine].
     */
    fun parse(response: String, language: OcrLanguage = OcrLanguage.JAPANESE): List<EngineLine> {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. Try parsing as JSON array
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.contains("\"bbox\"")) {
            val jsonResult = parseJson(trimmed, language)
            if (jsonResult.isNotEmpty()) return jsonResult
        }

        // 2. Parse line by line
        val lines = mutableListOf<EngineLine>()
        for (rawLine in trimmed.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // Try 4-point polygon first
            val polyMatch = POLY_PATTERN.find(line)
            if (polyMatch != null) {
                val (x1, y1, x2, y2, x3, y3, x4, y4, text) = polyMatch.destructured
                if (text.isNotBlank()) {
                    val xs = listOf(x1.toDouble(), x2.toDouble(), x3.toDouble(), x4.toDouble())
                    val ys = listOf(y1.toDouble(), y2.toDouble(), y3.toDouble(), y4.toDouble())
                    val left = normalizeCoord(xs.minOrNull() ?: 0.0)
                    val right = normalizeCoord(xs.maxOrNull() ?: 1.0)
                    val top = normalizeCoord(ys.minOrNull() ?: 0.0)
                    val bottom = normalizeCoord(ys.maxOrNull() ?: 1.0)
                    lines.add(createEngineLine(text, left, top, right, bottom, language))
                    continue
                }
            }

            // Try 4-coordinate bounding box: [c1, c2, c3, c4] text
            val match = COORD_PATTERN.find(line)
            if (match != null) {
                val (c1Str, c2Str, c3Str, c4Str, text) = match.destructured
                if (text.isNotBlank()) {
                    val c1 = c1Str.toDouble()
                    val c2 = c2Str.toDouble()
                    val c3 = c3Str.toDouble()
                    val c4 = c4Str.toDouble()

                    // Determine if [ymin, xmin, ymax, xmax] or [xmin, ymin, xmax, ymax]
                    // Standard VLM order (PaddleOCR-VL / Qwen-VL / PaliGemma) is [ymin, xmin, ymax, xmax]
                    val (top, left, bottom, right) = if (c3 >= c1 && c4 >= c2) {
                        // Standard [ymin, xmin, ymax, xmax]
                        listOf(normalizeCoord(c1), normalizeCoord(c2), normalizeCoord(c3), normalizeCoord(c4))
                    } else {
                        listOf(
                            normalizeCoord(min(c1, c3)),
                            normalizeCoord(min(c2, c4)),
                            normalizeCoord(max(c1, c3)),
                            normalizeCoord(max(c2, c4)),
                        )
                    }

                    lines.add(createEngineLine(text, left, top, right, bottom, language))
                    continue
                }
            }

            // Fallback for lines without coordinates (e.g. if prompted with plain OCR mode)
            if (!line.startsWith("#") && !line.startsWith("```") && line.isNotBlank()) {
                // Synthesize an approximate line
                val idx = lines.size
                val approxTop = (idx * 0.05).coerceAtMost(0.9)
                val approxBottom = (approxTop + 0.04).coerceAtMost(1.0)
                lines.add(createEngineLine(line, 0.1, approxTop, 0.9, approxBottom, language))
            }
        }

        return lines
    }

    private fun parseJson(jsonStr: String, language: OcrLanguage): List<EngineLine> {
        return try {
            val element = JSON_PARSER.parseToJsonElement(jsonStr)
            val array = element.jsonArray
            array.mapNotNull { item ->
                val obj = item.jsonObject
                val text = obj["text"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val bbox = obj["bbox"]?.jsonArray ?: return@mapNotNull null
                if (bbox.size < 4) return@mapNotNull null

                val c1 = bbox[0].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                val c2 = bbox[1].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                val c3 = bbox[2].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                val c4 = bbox[3].jsonPrimitive.content.toDoubleOrNull() ?: 0.0

                val left = normalizeCoord(min(c1, c3))
                val top = normalizeCoord(min(c2, c4))
                val right = normalizeCoord(max(c1, c3))
                val bottom = normalizeCoord(max(c2, c4))

                createEngineLine(text, left, top, right, bottom, language)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeCoord(value: Double): Double {
        return when {
            value <= 1.0 -> value.coerceIn(0.0, 1.0)
            value <= 1000.0 -> (value / 1000.0).coerceIn(0.0, 1.0)
            else -> (value / 2000.0).coerceIn(0.0, 1.0) // fallback for ~2000px images
        }
    }

    private fun createEngineLine(
        text: String,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        language: OcrLanguage,
    ): EngineLine {
        val safeLeft = min(left, right).coerceIn(0.0, 1.0)
        val safeRight = max(left, right).coerceIn(0.0, 1.0)
        val safeTop = min(top, bottom).coerceIn(0.0, 1.0)
        val safeBottom = max(top, bottom).coerceIn(0.0, 1.0)

        val bbox = NormalizedBBox(safeLeft, safeTop, safeRight, safeBottom)
        val width = bbox.width
        val height = bbox.height

        val writingDirection = when {
            height >= width * 1.25 -> WritingDirection.TTB
            width >= height * 1.25 -> WritingDirection.LTR
            language == OcrLanguage.JAPANESE -> WritingDirection.TTB
            else -> WritingDirection.LTR
        }

        return EngineLine(
            text = text.trim(),
            bbox = bbox,
            writingDirection = writingDirection,
            language = language,
        )
    }
}
// KUN <--
