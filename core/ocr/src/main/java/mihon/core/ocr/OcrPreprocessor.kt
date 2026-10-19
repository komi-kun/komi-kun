// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrPreprocessor.kt` @ f85c063.
// KUN <--
package mihon.core.ocr

/**
 * Standardizes punctuation sequences without touching whitespace or newlines.
 * Port of the "clean OCR text" replacement engine.
 */
internal object OcrPreprocessor {

    private val COMPLEX_PUNCTUATION_REGEX = Regex("[!?\\.\\u2026\\u22EE\\uff0e\\uff1f\\uff01\\uff65]{2,}")
    private const val KATAKANA_MIDDLE_DOTS = "･･･"

    /**
     * Collapses runs of 2+ punctuation marks into a single standard character.
     * Priority: ellipsis > question mark > exclamation mark.
     */
    fun clean(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""

        val flattened = text.replace(COMPLEX_PUNCTUATION_REGEX) { matchResult ->
            val match = matchResult.value
            val isEllipsis = match.contains('.') ||
                match.contains('\u2026') ||
                match.contains('\u22EE') ||
                match.contains('\uff0e') ||
                match.contains('\uff65')

            if (isEllipsis) {
                "…"
            } else if (match.contains('?') || match.contains('\uff1f')) {
                "?"
            } else {
                "!"
            }
        }

        return flattened.replace(KATAKANA_MIDDLE_DOTS, "…")
    }

    // KUN -->
    private val STROKE_LOOKALIKES = setOf(
        '干', '手', '七', '十', '千',
        'イ', 'ニ', 'ハ', 'ロ', 'ト', 'メ', 'ソ', 'ン',
        '出',
    )

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x3041..0x3096) || // Hiragana
            (code in 0x30A1..0x30FA) || // Katakana
            (code == 0x30FC) || // Katakana-Hiragana Prolonged Sound Mark
            (code == 0x3005) || // Ideographic Iteration Mark 々
            (code in 0x4E00..0x9FFF) || // CJK Unified Ideographs
            (code in 0x3400..0x4DBF) // CJK Extension A
    }

    private fun isAsciiLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z')

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    /**
     * Identifies short garbage reads produced by stylised onomatopoeia or background noise in
     * Japanese manga (e.g. `干S`, `手15`, `七5`, `115`, `415`).
     */
    fun isImplausibleJapaneseNoise(text: String): Boolean {
        val trimmed = text.trim { it.isWhitespace() || !it.isLetterOrDigit() }
        if (trimmed.isEmpty()) {
            return !text.any { isCjk(it) }
        }

        val codePointLength = trimmed.codePointCount(0, trimmed.length)
        if (codePointLength > 4) return false

        var cjkCount = 0
        var asciiLetterCount = 0
        var asciiDigitCount = 0
        var strokeLookalikeCount = 0

        for (i in 0 until trimmed.length) {
            val c = trimmed[i]
            if (isCjk(c)) {
                cjkCount++
                if (c in STROKE_LOOKALIKES) {
                    strokeLookalikeCount++
                }
            } else if (isAsciiLetter(c)) {
                asciiLetterCount++
            } else if (isAsciiDigit(c)) {
                asciiDigitCount++
            }
        }

        // 1. Isolated ASCII / punctuation-only with no kana/kanji
        if (cjkCount == 0) return true

        // 2. Mixes CJK with ASCII letters in a short token (e.g. 干S)
        if (asciiLetterCount > 0) return true

        // 3. Mixes a stroke lookalike with ASCII digits (e.g. 手15, 七5)
        if (asciiDigitCount > 0 && strokeLookalikeCount > 0 && cjkCount == strokeLookalikeCount) {
            return true
        }

        return false
    }
    // KUN <--
}
