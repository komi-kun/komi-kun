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
}
