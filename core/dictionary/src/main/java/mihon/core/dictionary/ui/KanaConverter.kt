// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.ui

import kotlin.math.max
import kotlin.math.min

object KanaConverter {

    // Ported from Yomitan ref/yomitan/ext/js/language/ja/japanese-kana-romaji-dicts.js
    // Mozc romaji→hiragana mapping, ordered by source length descending so replaceAll
    // matches the longest possible sequence first.
    private val ROMAJI_TO_HIRAGANA: List<Pair<String, String>> = listOf(
        // Double letters – must be matched first
        "qq" to "っq",
        "vv" to "っv",
        "ll" to "っl",
        "xx" to "っx",
        "kk" to "っk",
        "gg" to "っg",
        "ss" to "っs",
        "zz" to "っz",
        "jj" to "っj",
        "tt" to "っt",
        "dd" to "っd",
        "hh" to "っh",
        "ff" to "っf",
        "bb" to "っb",
        "pp" to "っp",
        "mm" to "っm",
        "yy" to "っy",
        "rr" to "っr",
        "ww" to "っw",
        "cc" to "っc",

        // Length 4
        "hwyu" to "ふゅ",

        // Length 3
        "vya" to "ゔゃ",
        "vyi" to "ゔぃ",
        "vyu" to "ゔゅ",
        "vye" to "ゔぇ",
        "vyo" to "ゔょ",
        "kya" to "きゃ",
        "kyi" to "きぃ",
        "kyu" to "きゅ",
        "kye" to "きぇ",
        "kyo" to "きょ",
        "gya" to "ぎゃ",
        "gyi" to "ぎぃ",
        "gyu" to "ぎゅ",
        "gye" to "ぎぇ",
        "gyo" to "ぎょ",
        "sya" to "しゃ",
        "syi" to "しぃ",
        "syu" to "しゅ",
        "sye" to "しぇ",
        "syo" to "しょ",
        "sha" to "しゃ",
        "shi" to "し",
        "shu" to "しゅ",
        "she" to "しぇ",
        "sho" to "しょ",
        "zya" to "じゃ",
        "zyi" to "じぃ",
        "zyu" to "じゅ",
        "zye" to "じぇ",
        "zyo" to "じょ",
        "tya" to "ちゃ",
        "tyi" to "ちぃ",
        "tyu" to "ちゅ",
        "tye" to "ちぇ",
        "tyo" to "ちょ",
        "cha" to "ちゃ",
        "chi" to "ち",
        "chu" to "ちゅ",
        "che" to "ちぇ",
        "cho" to "ちょ",
        "cya" to "ちゃ",
        "cyi" to "ちぃ",
        "cyu" to "ちゅ",
        "cye" to "ちぇ",
        "cyo" to "ちょ",
        "dya" to "ぢゃ",
        "dyi" to "ぢぃ",
        "dyu" to "ぢゅ",
        "dye" to "ぢぇ",
        "dyo" to "ぢょ",
        "tsa" to "つぁ",
        "tsi" to "つぃ",
        "tse" to "つぇ",
        "tso" to "つぉ",
        "tha" to "てゃ",
        "thi" to "てぃ",
        "thu" to "てゅ",
        "the" to "てぇ",
        "tho" to "てょ",
        "dha" to "でゃ",
        "dhi" to "でぃ",
        "dhu" to "でゅ",
        "dhe" to "でぇ",
        "dho" to "でょ",
        "twa" to "とぁ",
        "twi" to "とぃ",
        "twu" to "とぅ",
        "twe" to "とぇ",
        "two" to "とぉ",
        "dwa" to "どぁ",
        "dwi" to "どぃ",
        "dwu" to "どぅ",
        "dwe" to "どぇ",
        "dwo" to "どぉ",
        "nya" to "にゃ",
        "nyi" to "にぃ",
        "nyu" to "にゅ",
        "nye" to "にぇ",
        "nyo" to "にょ",
        "hya" to "ひゃ",
        "hyi" to "ひぃ",
        "hyu" to "ひゅ",
        "hye" to "ひぇ",
        "hyo" to "ひょ",
        "bya" to "びゃ",
        "byi" to "びぃ",
        "byu" to "びゅ",
        "bye" to "びぇ",
        "byo" to "びょ",
        "pya" to "ぴゃ",
        "pyi" to "ぴぃ",
        "pyu" to "ぴゅ",
        "pye" to "ぴぇ",
        "pyo" to "ぴょ",
        "fya" to "ふゃ",
        "fyu" to "ふゅ",
        "fyo" to "ふょ",
        "hwa" to "ふぁ",
        "hwi" to "ふぃ",
        "hwe" to "ふぇ",
        "hwo" to "ふぉ",
        "mya" to "みゃ",
        "myi" to "みぃ",
        "myu" to "みゅ",
        "mye" to "みぇ",
        "myo" to "みょ",
        "rya" to "りゃ",
        "ryi" to "りぃ",
        "ryu" to "りゅ",
        "rye" to "りぇ",
        "ryo" to "りょ",
        "lyi" to "ぃ",
        "xyi" to "ぃ",
        "lye" to "ぇ",
        "xye" to "ぇ",
        "xka" to "ヵ",
        "xke" to "ヶ",
        "lka" to "ヵ",
        "lke" to "ヶ",
        "kwa" to "くぁ",
        "kwi" to "くぃ",
        "kwu" to "くぅ",
        "kwe" to "くぇ",
        "kwo" to "くぉ",
        "gwa" to "ぐぁ",
        "gwi" to "ぐぃ",
        "gwu" to "ぐぅ",
        "gwe" to "ぐぇ",
        "gwo" to "ぐぉ",
        "swa" to "すぁ",
        "swi" to "すぃ",
        "swu" to "すぅ",
        "swe" to "すぇ",
        "swo" to "すぉ",
        "zwa" to "ずぁ",
        "zwi" to "ずぃ",
        "zwu" to "ずぅ",
        "zwe" to "ずぇ",
        "zwo" to "ずぉ",
        "jya" to "じゃ",
        "jyi" to "じぃ",
        "jyu" to "じゅ",
        "jye" to "じぇ",
        "jyo" to "じょ",
        "xtsu" to "っ",
        "ltsu" to "っ",
        "tsu" to "つ",
        "xtu" to "っ",
        "ltu" to "っ",
        "xya" to "ゃ",
        "lya" to "ゃ",
        "wyi" to "ゐ",
        "xyu" to "ゅ",
        "lyu" to "ゅ",
        "wye" to "ゑ",
        "xyo" to "ょ",
        "lyo" to "ょ",
        "xwa" to "ゎ",
        "lwa" to "ゎ",
        "wha" to "うぁ",
        "whi" to "うぃ",
        "whu" to "う",
        "whe" to "うぇ",
        "who" to "うぉ",

        // Length 2
        "nn" to "ん",
        "n'" to "ん",
        "va" to "ゔぁ",
        "vi" to "ゔぃ",
        "vu" to "ゔ",
        "ve" to "ゔぇ",
        "vo" to "ゔぉ",
        "fa" to "ふぁ",
        "fi" to "ふぃ",
        "fe" to "ふぇ",
        "fo" to "ふぉ",
        "xn" to "ん",
        "wu" to "う",
        "xa" to "ぁ",
        "xi" to "ぃ",
        "xu" to "ぅ",
        "xe" to "ぇ",
        "xo" to "ぉ",
        "la" to "ぁ",
        "li" to "ぃ",
        "lu" to "ぅ",
        "le" to "ぇ",
        "lo" to "ぉ",
        "ye" to "いぇ",
        "ka" to "か",
        "ki" to "き",
        "ku" to "く",
        "ke" to "け",
        "ko" to "こ",
        "ga" to "が",
        "gi" to "ぎ",
        "gu" to "ぐ",
        "ge" to "げ",
        "go" to "ご",
        "sa" to "さ",
        "si" to "し",
        "su" to "す",
        "se" to "せ",
        "so" to "そ",
        "ca" to "か",
        "ci" to "し",
        "cu" to "く",
        "ce" to "せ",
        "co" to "こ",
        "qa" to "くぁ",
        "qi" to "くぃ",
        "qu" to "く",
        "qe" to "くぇ",
        "qo" to "くぉ",
        "za" to "ざ",
        "zi" to "じ",
        "zu" to "ず",
        "ze" to "ぜ",
        "zo" to "ぞ",
        "ja" to "じゃ",
        "ji" to "じ",
        "ju" to "じゅ",
        "je" to "じぇ",
        "jo" to "じょ",
        "ta" to "た",
        "ti" to "ち",
        "tu" to "つ",
        "te" to "て",
        "to" to "と",
        "da" to "だ",
        "di" to "ぢ",
        "du" to "づ",
        "de" to "で",
        "do" to "ど",
        "na" to "な",
        "ni" to "に",
        "nu" to "ぬ",
        "ne" to "ね",
        "no" to "の",
        "ha" to "は",
        "hi" to "ひ",
        "hu" to "ふ",
        "fu" to "ふ",
        "he" to "へ",
        "ho" to "ほ",
        "ba" to "ば",
        "bi" to "び",
        "bu" to "ぶ",
        "be" to "べ",
        "bo" to "ぼ",
        "pa" to "ぱ",
        "pi" to "ぴ",
        "pu" to "ぷ",
        "pe" to "ぺ",
        "po" to "ぽ",
        "ma" to "ま",
        "mi" to "み",
        "mu" to "む",
        "me" to "め",
        "mo" to "も",
        "ya" to "や",
        "yu" to "ゆ",
        "yo" to "よ",
        "ra" to "ら",
        "ri" to "り",
        "ru" to "る",
        "re" to "れ",
        "ro" to "ろ",
        "wa" to "わ",
        "wi" to "うぃ",
        "we" to "うぇ",
        "wo" to "を",

        // Length 1
        "a" to "あ",
        "i" to "い",
        "u" to "う",
        "e" to "え",
        "o" to "お",

        // Special / symbols
        "." to "。",
        "," to "、",
        ":" to "：",
        "/" to "・",
        "!" to "！",
        "?" to "？",
        "~" to "〜",
        "-" to "ー",
        "‘" to "「",
        "’" to "」",
        "“" to "『",
        "”" to "』",
        "[" to "［",
        "]" to "］",
        "(" to "（",
        ")" to "）",
        "{" to "｛",
        "}" to "｝",
        " " to "　",

        // n → ん is last (special case for IME mode)
        "n" to "ん",
    )

    /**
     * Converts romaji text to kana: lowercase romaji becomes hiragana, uppercase romaji becomes
     * katakana — decided **per syllable**, not per string. A syllable is rendered in katakana only
     * when every character of its romaji source is an uppercase ASCII letter, which is wanakana's
     * rule (`enforceKatakana = [...input.slice(start, end)].every(isCharUpperCase)`, wanakana
     * 5.3.1 `toKana.js`):
     *
     * - `KA` → カ, `ka` → か, `KITTE` → キッテ, `KAra` → カら, `kaRA` → かラ
     * - a case change *inside* one syllable stays hiragana: `Ka`/`kA` → か, `Kara` → から
     * - a sokuon and the syllable after it form one token and share one case decision, like the
     *   slice wanakana cases: `KKA` → ッカ, `KKa` → っか, `KATTE` → カッテ, `KATTe` → カって
     *
     * Ported from Yomitan's convertToKana() in japanese-wanakana.js. Its `replaceAll` sweep only
     * caught syllables spelled in one uniform case, so mixed syllables leaked their romaji
     * (`Ka` → `Kあ`); syllables are now matched longest-first and cased individually. Katakana is
     * still derived from the hiragana table ([hiraganaToKatakana]) rather than a second table.
     */
    fun toKana(text: String): String {
        if (text.isEmpty()) return text
        val result = StringBuilder(text.length)
        var index = 0
        // End of the sokuon+syllable token and its case, already decided when the sokuon was
        // emitted: wanakana renders the whole token in one case, so `kKA` is っか, not っカ.
        var sokuonTokenEnd = -1
        var sokuonKatakana = false
        while (index < text.length) {
            val length = longestRomajiMatchAt(text, index)
            if (length == 0) {
                result.append(text[index])
                index += 1
                continue
            }
            val key = text.substring(index, index + length).lowercase()
            val hiragana = ROMAJI_TO_HIRAGANA_MAP.getValue(key)
            if (isSokuonPlaceholder(hiragana)) {
                // Doubled consonant: emit the sokuon and let the loop re-match from the second
                // letter, which is what makes `tt` → っt (an unfinished sokuon) and `tte` → って.
                // The case is taken over the sokuon *and* the syllable that follows it, which is
                // wanakana's token slice (`KKa` → っか, `KKA` → ッカ), and that decision also
                // applies to the syllable itself.
                val syllableLength = longestRomajiMatchAt(text, index + 1)
                sokuonTokenEnd = index + 1 + syllableLength
                // A dangling doubled consonant (`tt` → っt) has no syllable to share a case with,
                // so it keeps the case of its own two letters, as before.
                val caseLength = if (syllableLength == 0) length else 1 + syllableLength
                sokuonKatakana = isUppercaseRomaji(text, index, caseLength)
                result.append(if (sokuonKatakana) SOKUON_KATAKANA else SOKUON_HIRAGANA)
                index += 1
            } else {
                // `nn` directly before a nya/nyi/nyu/nye/nyo syllable must not swallow the second
                // `n`: wanakana closes ん on the first `n` alone and starts the syllable at the
                // second, so `konnyaku` is こんにゃく rather than こんやく (and `koNNya` keeps its
                // ン, because only that one `n` decides the case).
                val splitNn = key == "nn" && isNyaRowAfter(text, index)
                val syllableLength = if (splitNn) 1 else length
                val katakana = if (index + length == sokuonTokenEnd) {
                    sokuonKatakana
                } else {
                    isUppercaseRomaji(text, index, syllableLength)
                }
                result.append(if (katakana) hiraganaToKatakana(hiragana) else hiragana)
                index += syllableLength
            }
        }
        return fillSokuonGaps(result.toString())
    }

    /**
     * IME-aware conversion that hides a single 'n' or 'ny' immediately before the
     * cursor so the user can continue typing to form na/ni/nu/ne/no or nya/nyu/nyo.
     * Ported from Yomitan's convertToKanaIME() in japanese-wanakana.js
     *
     * @return Pair(convertedText, newCursorPosition)
     */
    fun toKanaIME(text: String, cursorPos: Int): Pair<String, Int> {
        val prevLength = text.length
        val clamped = clampCursor(cursorPos, prevLength)

        val lowered = text.lowercase()
        val converted = when {
            // Single 'n' before cursor
            clamped > 0 &&
                lowered[clamped - 1] == 'n' &&
                lowered.slice(0 until clamped - 1).replace("nn", "").lastOrNull() != 'n' -> {
                val n = text.substring(clamped - 1, clamped)
                val convertedBefore = toKana(text.substring(0, clamped - 1))
                KanaWithCaret(
                    text = convertedBefore + n + toKana(text.substring(clamped)),
                    caret = convertedBefore.length + n.length,
                )
            }
            // 'ny' before cursor
            clamped > 1 &&
                lowered.substring(clamped - 2, clamped) == "ny" -> {
                val ny = text.substring(clamped - 2, clamped)
                val convertedBefore = toKana(text.substring(0, clamped - 2))
                KanaWithCaret(
                    text = convertedBefore + ny + toKana(text.substring(clamped)),
                    caret = convertedBefore.length + ny.length,
                )
            }
            else -> KanaWithCaret(
                text = toKana(text),
                // Only the text before the cursor moves it, so the caret is measured on the
                // converted prefix: a conversion *after* the cursor must not shift it (wanakana
                // does the same — `convertInput` sets `head.length + convertedText.length`).
                caret = toKana(text.substring(0, clamped)).length,
            )
        }

        val kanaString = converted.text
        return kanaString to clampCursor(converted.caret, kanaString.length)
    }

    private data class KanaWithCaret(val text: String, val caret: Int)

    /**
     * Only converts alphabetic (romaji) segments to hiragana, leaving existing
     * kana/kanji/symbols untouched. Ported from Yomitan's convertAlphabeticToKana().
     */
    fun convertAlphabeticToKana(text: String): String {
        val result = StringBuilder()
        val part = StringBuilder()

        for (char in text) {
            val code = char.code
            when {
                // Lowercase a-z (pass through)
                code in 0x61..0x7a -> part.append(char)
                // Uppercase A-Z -> lowercase
                code in 0x41..0x5a -> part.append((code + 0x20).toChar())
                // Fullwidth A-Z (U+FF21-U+FF3A) -> ASCII lowercase
                code in 0xff21..0xff3a -> part.append((code - 0xff21 + 0x61).toChar())
                // Fullwidth a-z (U+FF41-U+FF5A) -> ASCII lowercase
                code in 0xff41..0xff5a -> part.append((code - 0xff41 + 0x61).toChar())
                // Hyphen / fullwidth hyphen
                code == 0x2d || code == 0xff0d -> part.append('-')
                else -> {
                    if (part.isNotEmpty()) {
                        result.append(toKana(part.toString()))
                        part.clear()
                    }
                    result.append(char)
                }
            }
        }

        if (part.isNotEmpty()) {
            result.append(toKana(part.toString()))
        }
        return result.toString()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    // Longest romaji key in the table (4: `hwyu`, `xtsu`, `ltsu`), bounding the match loop.
    private val MAX_ROMAJI_LENGTH = ROMAJI_TO_HIRAGANA.maxOf { it.first.length }

    private val ROMAJI_TO_HIRAGANA_MAP = ROMAJI_TO_HIRAGANA.toMap()

    private const val SOKUON_HIRAGANA = 'っ'
    private const val SOKUON_KATAKANA = 'ッ'

    /**
     * Length of the longest table key matching [text] at [start], case-insensitively; 0 when no
     * key starts there. Longest-first is what makes `xtsu` win over `tsu`, and `nn` over `n`.
     */
    private fun longestRomajiMatchAt(text: String, start: Int): Int {
        var length = min(MAX_ROMAJI_LENGTH, text.length - start)
        while (length > 0) {
            if (ROMAJI_TO_HIRAGANA_MAP.containsKey(text.substring(start, start + length).lowercase())) {
                return length
            }
            length -= 1
        }
        return 0
    }

    /**
     * wanakana's per-syllable case test: katakana iff every character of the matched romaji slice
     * is an uppercase ASCII letter (`N'` therefore stays hiragana, like wanakana's `ん`).
     */
    private fun isUppercaseRomaji(text: String, start: Int, length: Int): Boolean {
        for (offset in start until start + length) {
            if (text[offset] !in 'A'..'Z') return false
        }
        return true
    }

    /** Table values for doubled consonants (`kk` → `っk`) leave the consonant for the next match. */
    private fun isSokuonPlaceholder(value: String): Boolean {
        return value.length == 2 && value[0] == SOKUON_HIRAGANA && value[1] in 'a'..'z'
    }

    private val NYA_ROW = setOf("nya", "nyi", "nyu", "nye", "nyo")

    /**
     * Whether the `nn` matched at [start] is directly followed by a nya/nyi/nyu/nye/nyo syllable,
     * i.e. the second `n` starts that syllable rather than closing ん.
     */
    private fun isNyaRowAfter(text: String, start: Int): Boolean {
        return start + 4 <= text.length && text.substring(start + 1, start + 4).lowercase() in NYA_ROW
    }

    // Hiragana: U+3041 (ぁ) .. U+3096 (ゖ)
    // Katakana: U+30A1 (ァ) .. U+30F6 (ヶ)
    // Katakana offset = U+30A1 - U+3041 = 0x60
    private const val HIRAGANA_START = '\u3041'
    private const val HIRAGANA_END = '\u3096'
    private val HIRAGANA_RANGE = HIRAGANA_START..HIRAGANA_END
    private const val KATAKANA_OFFSET = 0x30a1 - 0x3041 // 0x60

    private fun hiraganaToKatakana(text: String): String {
        return text.map { c ->
            when {
                c in HIRAGANA_RANGE -> c + KATAKANA_OFFSET
                c in 'a'..'z' -> c.uppercaseChar()
                else -> c
            }
        }.joinToString("")
    }

    /**
     * Fills gaps in sokuon (っ) that replaceAll misses when run non-iteratively.
     * Ported from Yomitan's fillSokuonGaps().
     */
    private fun fillSokuonGaps(text: String): String {
        return text
            .replace(Regex("っ[a-z](?=っ)"), "っっ")
            .replace(Regex("ッ[A-Z](?=ッ)"), "ッッ")
    }

    private fun clampCursor(cursor: Int, length: Int): Int {
        return max(0, min(cursor, length))
    }
}
