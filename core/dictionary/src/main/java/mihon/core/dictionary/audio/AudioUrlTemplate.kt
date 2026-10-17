// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Yomitan's Custom URL placeholder substitution (`AudioDownloader._getCustomUrl`,
// ext/js/media/audio-downloader.js). Supported patterns: `{term}`, `{reading}` and `{language}`
// (the ISO code of the active language). Unknown `{...}` placeholders are left untouched, exactly
// like Yomitan's replacer.
package mihon.core.dictionary.audio

object AudioUrlTemplate {

    private val PLACEHOLDER = Regex("\\{([^}]*)}")

    fun expand(
        template: String,
        term: String,
        reading: String,
        language: String,
    ): String = PLACEHOLDER.replace(template) { match ->
        when (match.groupValues[1]) {
            "term" -> term
            "reading" -> reading
            "language" -> language
            else -> match.value
        }
    }
}
// KUN <--
