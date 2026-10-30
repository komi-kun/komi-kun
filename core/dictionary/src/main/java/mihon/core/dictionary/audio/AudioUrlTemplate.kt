// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Yomitan's Custom URL placeholder substitution (`AudioDownloader._getCustomUrl`,
// ext/js/media/audio-downloader.js). Supported patterns: `{term}`, `{reading}` and `{language}`
// (the ISO code of the active language). Unknown `{...}` placeholders are left untouched, exactly
// like Yomitan's replacer.
//
// The substitution is done with a hand-written scanner rather than a regular expression on
// purpose. Yomitan's original `/\{([^}]*)\}/g` contains a lone (unescaped) closing brace, which the
// JVM regex engine tolerates but Android's ICU engine rejects at class-initialisation time
// (`PatternSyntaxException: Syntax error in regexp pattern near index 10`, seen on device). Manual
// scanning makes the class of "works on the desktop JVM, crashes on Android" divergence impossible
// here and needs no escaping at all.
package mihon.core.dictionary.audio

object AudioUrlTemplate {

    fun expand(
        template: String,
        term: String,
        reading: String,
        language: String,
    ): String {
        // Fast path: nothing to substitute.
        var index = template.indexOf('{')
        if (index < 0) return template

        val out = StringBuilder(template.length + 16)
        var cursor = 0
        while (index >= 0) {
            val close = template.indexOf('}', index + 1)
            if (close < 0) {
                // A stray `{` with no matching `}` — leave the remainder verbatim.
                break
            }
            out.append(template, cursor, index)
            val name = template.substring(index + 1, close)
            when (name) {
                "term" -> out.append(term)
                "reading" -> out.append(reading)
                "language" -> out.append(language)
                // Unknown placeholders are preserved verbatim, braces included.
                else -> out.append(template, index, close + 1)
            }
            cursor = close + 1
            index = template.indexOf('{', cursor)
        }
        out.append(template, cursor, template.length)
        return out.toString()
    }
}
// KUN <--
