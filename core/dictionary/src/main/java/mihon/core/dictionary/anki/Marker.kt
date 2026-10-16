// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported/adapted from Chimahon's `chimahon/anki/AnkiCardCreator.kt` marker constants (GPL-3.0)
// and the authoritative Yomitan catalog in `docs/design/yomitan-spec/06-anki.md` §(a).
package mihon.core.dictionary.anki

/**
 * Field-marker names. The Yomitan catalog is canonical; Chimahon's extra markers are kept as
 * `// KUN` extensions and must degrade to `""` when their data is absent (plan 05 §B2).
 *
 * There is **no** `{marker:arg}` syntax — template args live inside the Handlebars definition
 * (`06-anki.md` §a).
 */
object Marker {
    // --- Term (Yomitan) ------------------------------------------------------------------
    const val EXPRESSION = "expression"
    const val READING = "reading"
    const val FURIGANA = "furigana"
    const val FURIGANA_PLAIN = "furigana-plain"
    const val AUDIO = "audio"
    const val GLOSSARY = "glossary"
    const val GLOSSARY_BRIEF = "glossary-brief"
    const val GLOSSARY_NO_DICT = "glossary-no-dictionary"
    const val GLOSSARY_PLAIN = "glossary-plain"
    const val GLOSSARY_PLAIN_NO_DICT = "glossary-plain-no-dictionary"
    const val GLOSSARY_FIRST = "glossary-first"
    const val GLOSSARY_FIRST_BRIEF = "glossary-first-brief"
    const val GLOSSARY_FIRST_NO_DICT = "glossary-first-no-dictionary"
    const val DICTIONARY = "dictionary"
    const val DICTIONARY_ALIAS = "dictionary-alias"
    const val TAGS = "tags"
    const val CONJUGATION = "conjugation"
    const val PART_OF_SPEECH = "part-of-speech"
    const val FREQUENCIES = "frequencies"
    const val FREQUENCY_HARMONIC_RANK = "frequency-harmonic-rank"
    const val FREQUENCY_HARMONIC_OCCURRENCE = "frequency-harmonic-occurrence"
    const val FREQUENCY_AVERAGE_RANK = "frequency-average-rank"
    const val FREQUENCY_AVERAGE_OCCURRENCE = "frequency-average-occurrence"
    const val PITCH_ACCENTS = "pitch-accents"
    const val PITCH_ACCENT_GRAPHS = "pitch-accent-graphs"
    const val PITCH_ACCENT_GRAPHS_JJ = "pitch-accent-graphs-jj"
    const val PITCH_ACCENT_POSITIONS = "pitch-accent-positions"
    const val PITCH_ACCENT_CATEGORIES = "pitch-accent-categories"
    const val PHONETIC_TRANSCRIPTIONS = "phonetic-transcriptions"
    const val SCREENSHOT = "screenshot"
    const val CLIPBOARD_IMAGE = "clipboard-image"
    const val CLIPBOARD_TEXT = "clipboard-text"
    const val SENTENCE = "sentence"
    const val SENTENCE_FURIGANA = "sentence-furigana"
    const val SENTENCE_FURIGANA_PLAIN = "sentence-furigana-plain"
    const val CLOZE_PREFIX = "cloze-prefix"
    const val CLOZE_BODY = "cloze-body"
    const val CLOZE_BODY_KANA = "cloze-body-kana"
    const val CLOZE_SUFFIX = "cloze-suffix"
    const val DOCUMENT_TITLE = "document-title"
    const val URL = "url"
    const val URL_PLAIN = "url-plain"
    const val SEARCH_QUERY = "search-query"
    const val POPUP_SELECTION_TEXT = "popup-selection-text"

    // --- Kanji (Yomitan) -----------------------------------------------------------------
    const val CHARACTER = "character"
    const val KUNYOMI = "kunyomi"
    const val ONYOMI = "onyomi"
    const val ONYOMI_HIRAGANA = "onyomi-hiragana"
    const val STROKE_COUNT = "stroke-count"

    // --- Dynamic (generated per dictionary) ----------------------------------------------
    const val SINGLE_GLOSSARY_PREFIX = "single-glossary-"
    const val SINGLE_FREQUENCY_NUMBER_PREFIX = "single-frequency-number-"
    const val SINGLE_FREQUENCY_PREFIX = "single-frequency-"

    // --- KUN extensions (Chimahon; not in Yomitan) ---------------------------------------
    const val WORD_AUDIO = "word-audio"
    const val SENTENCE_AUDIO = "sentence-audio"
    const val SELECTED_GLOSSARY = "selected-glossary"
    const val SELECTED_GLOSSARY_NO_FALLBACK = "selected-glossary-no-fallback"
    const val SENTENCE_BOLD = "sentence-bold"
    const val MEDIA_NAME = "media-name"
    const val CHAPTER = "chapter"
    const val MEDIA = "media"
    const val MORAE = "morae"
    const val SENTENCE_TRANSLATION = "sentence-translation"
    const val MISC_INFO = "misc-info"
    const val SELECTION_TEXT = "selection-text"
    const val FREQUENCY_LOWEST = "frequency-lowest"

    /** Full Yomitan term catalog (`anki-template-util.js:28-78`). */
    val TERM_MARKERS: Set<String> = setOf(
        AUDIO, CLIPBOARD_IMAGE, CLIPBOARD_TEXT, CLOZE_BODY, CLOZE_BODY_KANA, CLOZE_PREFIX, CLOZE_SUFFIX,
        CONJUGATION, DICTIONARY, DICTIONARY_ALIAS, DOCUMENT_TITLE, EXPRESSION, FREQUENCIES,
        FREQUENCY_HARMONIC_RANK, FREQUENCY_HARMONIC_OCCURRENCE, FREQUENCY_AVERAGE_RANK,
        FREQUENCY_AVERAGE_OCCURRENCE, FURIGANA, FURIGANA_PLAIN, GLOSSARY, GLOSSARY_BRIEF,
        GLOSSARY_NO_DICT, GLOSSARY_PLAIN, GLOSSARY_PLAIN_NO_DICT, GLOSSARY_FIRST, GLOSSARY_FIRST_BRIEF,
        GLOSSARY_FIRST_NO_DICT, PART_OF_SPEECH, PHONETIC_TRANSCRIPTIONS, READING, SCREENSHOT,
        SEARCH_QUERY, POPUP_SELECTION_TEXT, SENTENCE, SENTENCE_FURIGANA, SENTENCE_FURIGANA_PLAIN,
        TAGS, URL, URL_PLAIN,
        // ja-only pitch set (`anki-template-util.js:68-77`).
        PITCH_ACCENTS, PITCH_ACCENT_GRAPHS, PITCH_ACCENT_GRAPHS_JJ, PITCH_ACCENT_POSITIONS,
        PITCH_ACCENT_CATEGORIES,
    )

    /** Full Yomitan kanji catalog (`anki-template-util.js:80-110`). */
    val KANJI_MARKERS: Set<String> = setOf(
        CHARACTER, CLIPBOARD_IMAGE, CLIPBOARD_TEXT, CLOZE_BODY, CLOZE_PREFIX, CLOZE_SUFFIX,
        DICTIONARY, DICTIONARY_ALIAS, DOCUMENT_TITLE, FREQUENCIES, FREQUENCY_HARMONIC_RANK,
        FREQUENCY_HARMONIC_OCCURRENCE, FREQUENCY_AVERAGE_RANK, FREQUENCY_AVERAGE_OCCURRENCE, GLOSSARY,
        KUNYOMI, ONYOMI, ONYOMI_HIRAGANA, SCREENSHOT, SEARCH_QUERY, POPUP_SELECTION_TEXT, SENTENCE,
        SENTENCE_FURIGANA, SENTENCE_FURIGANA_PLAIN, STROKE_COUNT, TAGS, URL, URL_PLAIN,
    )

    /** `// KUN` extras (Chimahon) — render `""` when absent; never crash. */
    val KUN_MARKERS: Set<String> = setOf(
        WORD_AUDIO, SENTENCE_AUDIO, SELECTED_GLOSSARY, SELECTED_GLOSSARY_NO_FALLBACK, SENTENCE_BOLD,
        MEDIA_NAME, CHAPTER, MEDIA, MORAE, SENTENCE_TRANSLATION, MISC_INFO, SELECTION_TEXT,
        FREQUENCY_LOWEST,
    )

    val ALL: Set<String> = TERM_MARKERS + KANJI_MARKERS + KUN_MARKERS
}
// KUN <--
