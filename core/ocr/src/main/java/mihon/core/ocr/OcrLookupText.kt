// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon/src/main/java/chimahon/ocr/OcrLookupText.kt` @ f85c063.
// The Anki-profile scan/search resolution constants are inlined so the OCR module stays free of
// any Anki dependency (the constants live in Chimahon's `AnkiProfile`).
// KUN <--
package mihon.core.ocr

private const val SCAN_RESOLUTION_WORD = "word"
private const val SCAN_RESOLUTION_CHARACTER = "character"
private const val SEARCH_RESOLUTION_WORD = "word"
private const val SEARCH_RESOLUTION_LETTER = "letter"

/** Returns whether [char] can begin a dictionary lookup. */
fun isOcrLookupStartChar(char: Char): Boolean {
    if (char.isWhitespace()) return false
    val type = Character.getType(char)
    return type != Character.CONNECTOR_PUNCTUATION.toInt() &&
        type != Character.DASH_PUNCTUATION.toInt() &&
        type != Character.START_PUNCTUATION.toInt() &&
        type != Character.END_PUNCTUATION.toInt() &&
        type != Character.INITIAL_QUOTE_PUNCTUATION.toInt() &&
        type != Character.FINAL_QUOTE_PUNCTUATION.toInt() &&
        type != Character.OTHER_PUNCTUATION.toInt() &&
        type != Character.MATH_SYMBOL.toInt() &&
        type != Character.CURRENCY_SYMBOL.toInt() &&
        type != Character.MODIFIER_SYMBOL.toInt() &&
        type != Character.OTHER_SYMBOL.toInt()
}

/** Returns the lookup suffix beginning at [start], matching manga OCR behavior. */
fun extractOcrLookupText(text: String, start: Int): String {
    // KUN -->
    if (start >= text.length) return ""
    var index = start.coerceAtLeast(0)
    while (index < text.length && !isOcrLookupStartChar(text[index])) {
        index++
    }
    val result = StringBuilder()
    while (index < text.length && isOcrLookupStartChar(text[index])) {
        result.append(text[index])
        index++
    }
    return result.toString()
    // KUN <--
}

private val CJK_OcrLanguageCodes = setOf("ja", "zh", "yue", "ko")
private val mixedOrUnknownOcrLanguageCodes = setOf("all", "other", "multi", "unknown")

/** Normalizes a BCP-47-style language code ("es-419", "zh_Hant") to its base. */
fun normalizeOcrLanguageCode(code: String): String {
    return code.trim().substringBefore('-').substringBefore('_').lowercase()
}

/**
 * Whether whole-word scan expansion applies for [languageCode]: true for space-delimited
 * languages, false for CJK script and for mixed / unknown ("all languages") codes.
 */
fun isLanguageWholeWordScan(languageCode: String?): Boolean {
    if (languageCode == null) return false
    val lang = normalizeOcrLanguageCode(languageCode)
    if (lang.isBlank() || lang in mixedOrUnknownOcrLanguageCodes) return false
    return lang !in CJK_OcrLanguageCodes
}

/**
 * Resolves whether the scan should expand to whole words given an optional explicit [resolution]
 * ("word" / "character") and a [languageCode]. An explicit resolution always wins; otherwise the
 * value is derived from the language (word for space-delimited languages, character for CJK /
 * "all languages").
 */
fun shouldScanWholeWord(resolution: String, languageCode: String?): Boolean {
    return when (resolution.trim().lowercase()) {
        SCAN_RESOLUTION_WORD -> true
        SCAN_RESOLUTION_CHARACTER -> false
        else -> isLanguageWholeWordScan(languageCode)
    }
}

/**
 * Resolves a profile's effective scan resolution ("word"/"character") for [languageCode]. An
 * explicit stored value wins; "auto" (empty) derives from the language.
 */
fun effectiveScanResolution(stored: String, languageCode: String?): String {
    val value = stored.trim().lowercase()
    if (value == SCAN_RESOLUTION_WORD || value == SCAN_RESOLUTION_CHARACTER) {
        return value
    }
    return if (isLanguageWholeWordScan(languageCode)) SCAN_RESOLUTION_WORD else SCAN_RESOLUTION_CHARACTER
}

/**
 * Resolves a profile's effective search resolution: an explicit value wins, otherwise it derives
 * from the language ("word" vs "letter" for CJK / "all languages").
 */
fun effectiveSearchResolution(stored: String, languageCode: String?): String {
    val value = stored.trim().lowercase()
    if (value == SEARCH_RESOLUTION_WORD || value == SEARCH_RESOLUTION_LETTER) {
        return value
    }
    return if (isLanguageWholeWordScan(languageCode)) SEARCH_RESOLUTION_WORD else SEARCH_RESOLUTION_LETTER
}

/**
 * Whether the source and profile languages can share a dictionary profile: mixed/unknown source
 * or profile always matches; otherwise the resolved base language codes must be equal.
 */
fun isOcrAllowedForLanguage(sourceLanguage: String, profileLanguage: String): Boolean {
    val sourceLang = normalizeOcrLanguageCode(sourceLanguage)
    if (sourceLang.isBlank() || sourceLang in mixedOrUnknownOcrLanguageCodes) return true

    val profileLang = normalizeOcrLanguageCode(profileLanguage)
    if (profileLang.isBlank() || profileLang in mixedOrUnknownOcrLanguageCodes) return true

    return sourceLang == profileLang
}

/**
 * Returns the whole word containing [tapOffset], expanding backward to [lineStart] and forward to
 * [lineEnd] while [isOcrLookupStartChar] holds. The clamp prevents crossing into a neighboring OCR
 * line (block text concatenates lines without separators).
 */
fun extractWholeWord(text: String, tapOffset: Int, lineStart: Int, lineEnd: Int): String {
    var start = tapOffset.coerceIn(lineStart, lineEnd)
    while (start > lineStart && isOcrLookupStartChar(text[start - 1])) {
        start--
    }
    var end = tapOffset.coerceIn(lineStart, lineEnd)
    while (end < lineEnd && isOcrLookupStartChar(text[end])) {
        end++
    }
    return text.substring(start, end)
}

private val wordBoundaryRegex = Regex("""[^\p{L}][\p{L}\p{N}]*$""")

/**
 * Yomitan's `translation.searchResolution='word'` iteration step: returns [current] cut at the last
 * word boundary (dropping the trailing word), or "" when there are no boundaries to cut.
 */
fun nextWordBoundarySubstring(current: String): String {
    if (current.isEmpty()) return ""
    val match = wordBoundaryRegex.find(current)
    val nextLength = match?.range?.first ?: -1
    return if (nextLength > 0) current.substring(0, nextLength) else ""
}
