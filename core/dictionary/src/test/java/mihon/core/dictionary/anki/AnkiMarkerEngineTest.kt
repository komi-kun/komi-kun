// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.Frequency
import mihon.core.dictionary.model.FrequencyEntry
import mihon.core.dictionary.model.GlossaryEntry
import mihon.core.dictionary.model.KanjiEntry
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.PitchEntry
import mihon.core.dictionary.model.TermResult
import mihon.core.dictionary.model.TransformGroup
import org.junit.jupiter.api.Test

/**
 * T1 marker-engine gate (plan 05 §A3/B7): the scanner regex, the `{name-render-error}` fallback
 * and each catalog entry of `06-anki.md` §(a), driven by small expected-output fixtures.
 */
class AnkiMarkerEngineTest {

    private fun term(
        expression: String = "食べる",
        reading: String = "たべる",
        rules: String = "v1",
        glossaries: List<GlossaryEntry> = listOf(GlossaryEntry("JMDict", "to eat", "v1", "")),
        frequencies: List<FrequencyEntry> = emptyList(),
        pitches: List<PitchEntry> = emptyList(),
    ) = TermResult(
        expression = expression,
        reading = reading,
        rules = rules,
        score = 0,
        glossaries = glossaries,
        frequencies = frequencies,
        pitches = pitches,
    )

    private fun ctx(
        term: TermResult = term(),
        cloze: AnkiCloze? = null,
        kanji: KanjiResult? = null,
        process: List<TransformGroup> = emptyList(),
        media: AnkiMediaInfo? = null,
        glossaryIndex: Int? = null,
        selectedDict: String? = null,
        popupSelection: String? = null,
        clipboardText: String? = null,
        clipboardImageFilename: String? = null,
        screenshotFilename: String? = null,
        wordAudioFilename: String? = null,
        sentenceAudioFilename: String? = null,
        documentTitle: String = "",
        url: String = "",
        searchQuery: String = "",
        selectionText: String? = null,
        sentenceFuriganaHtml: String? = null,
        sentenceFuriganaPlain: String? = null,
        styles: List<DictionaryStyle> = emptyList(),
        exportMedia: AnkiDictionaryMediaContext? = null,
    ) = AnkiMarkerContext(
        term = term,
        kanji = kanji,
        process = process,
        cloze = cloze,
        media = media,
        glossaryIndex = glossaryIndex,
        selectedDict = selectedDict,
        popupSelection = popupSelection,
        clipboardText = clipboardText,
        clipboardImageFilename = clipboardImageFilename,
        screenshotFilename = screenshotFilename,
        wordAudioFilename = wordAudioFilename,
        sentenceAudioFilename = sentenceAudioFilename,
        documentTitle = documentTitle,
        url = url,
        searchQuery = searchQuery,
        selectionText = selectionText,
        sentenceFuriganaHtml = sentenceFuriganaHtml,
        sentenceFuriganaPlain = sentenceFuriganaPlain,
        styles = styles,
        exportMedia = exportMedia,
    )

    private fun render(template: String, context: AnkiMarkerContext = ctx()): String =
        AnkiMarkerEngine.render(template, context)

    // --- Scanner ------------------------------------------------------------------------

    @Test
    fun `marker scanner matches the spec regex and ignores colon or space forms`() {
        render("{expression}") shouldBe "食べる"
        // No `{marker:arg}` syntax — colon-bearing tokens are left untouched.
        render("{expression:foo}") shouldBe "{expression:foo}"
        render("{foo bar}") shouldBe "{foo bar}"
    }

    @Test
    fun `unknown markers render as name-render-error`() {
        render("{does-not-exist}") shouldBe "{does-not-exist-render-error}"
        // `{definition}` is explicitly not a Yomitan marker (06-anki.md §a).
        render("{definition}") shouldBe "{definition-render-error}"
    }

    @Test
    fun `markersIn lists every marker in order`() {
        AnkiMarkerEngine.markersIn("{expression}/{reading}") shouldBe listOf("expression", "reading")
    }

    // --- Core term markers --------------------------------------------------------------

    @Test
    fun `expression and reading are html-escaped`() {
        render("{expression}", ctx(term = term("<b>"))) shouldBe "&lt;b&gt;"
        render("{reading}") shouldBe "たべる"
    }

    @Test
    fun `furigana renders ruby segments`() {
        render("{furigana}") shouldBe "<ruby>食<rt>た</rt></ruby>べる"
    }

    @Test
    fun `furigana-plain renders text_reading brackets`() {
        render("{furigana-plain}") shouldBe "食[た]べる "
    }

    @Test
    fun `same expression and reading fall back to plain text`() {
        render("{furigana}", ctx(term = term("カタカナ", "カタカナ"))) shouldBe "カタカナ"
    }

    // --- Glossary variants --------------------------------------------------------------

    @Test
    fun `glossary renders the yomitan container and dictionary tag`() {
        val out = render("{glossary}")
        out shouldContain """class="yomitan-glossary""""
        out shouldContain "to eat"
        out shouldContain "(v1, JMDict)"
    }

    @Test
    fun `glossary-no-dictionary drops the dictionary tag`() {
        render("{glossary-no-dictionary}") shouldNotContain "(v1, JMDict)"
    }

    @Test
    fun `glossary-brief drops the header`() {
        render("{glossary-brief}") shouldNotContain "(v1, JMDict)"
    }

    @Test
    fun `glossary-plain and glossary-plain-no-dictionary`() {
        render("{glossary-plain}") shouldContain "(JMDict)"
        render("{glossary-plain-no-dictionary}") shouldBe "to eat"
    }

    @Test
    fun `glossary-first only uses the first definition`() {
        val two = term(
            glossaries = listOf(
                GlossaryEntry("A", "first", "", ""),
                GlossaryEntry("B", "second", "", ""),
            ),
        )
        val out = render("{glossary-first}", ctx(term = two))
        out shouldContain "first"
        out shouldNotContain "second"
    }

    @Test
    fun `single-glossary dynamic marker filters by dictionary`() {
        val two = term(
            glossaries = listOf(
                GlossaryEntry("JMDict", "one", "", ""),
                GlossaryEntry("Daijirin", "two", "", ""),
            ),
        )
        val out = render("{single-glossary-jmdict}", ctx(term = two))
        out shouldContain "one"
        out shouldNotContain "two"
        render("{single-glossary-jmdict-plain-no-dictionary}", ctx(term = two)) shouldBe "one"
    }

    @Test
    fun `dictionary and dictionary-alias use the primary dictionary`() {
        val two = term(glossaries = listOf(GlossaryEntry("Daijirin", "x", "", ""), GlossaryEntry("JMDict", "y", "", "")))
        render("{dictionary}", ctx(term = two)) shouldBe "Daijirin"
        render("{dictionary-alias}", ctx(term = two)) shouldBe "Daijirin"
    }

    @Test
    fun `glossary scoped styles are injected`() {
        val out = render(
            "{glossary}",
            ctx(styles = listOf(DictionaryStyle("JMDict", "span { color: red; }"))),
        )
        out shouldContain "<style>"
        out shouldContain "[data-dictionary=\"JMDict\"] span { color: red; }"
    }

    // --- Tags / POS / conjugation -------------------------------------------------------

    @Test
    fun `tags merge term and definition tags`() {
        val t = term(glossaries = listOf(GlossaryEntry("JMDict", "to eat", "v1 common", "verb")))
        render("{tags}", ctx(term = t)) shouldBe "v1, common, verb"
    }

    @Test
    fun `part-of-speech is prettified and Unknown when absent`() {
        render("{part-of-speech}", ctx(term = term(rules = "v1"))) shouldBe "Ichidan verb"
        render("{part-of-speech}", ctx(term = term(rules = ""))) shouldBe "Unknown"
    }

    @Test
    fun `conjugation joins the inflection chain with guillemets`() {
        val out = render("{conjugation}", ctx(process = listOf(TransformGroup("past", ""), TransformGroup("plain", ""))))
        out shouldBe "past « plain"
    }

    // --- Frequencies --------------------------------------------------------------------

    private fun freqs(vararg entries: Pair<String, Int>) = entries.map { (dict, value) ->
        FrequencyEntry(dict, listOf(Frequency(value, value.toString())))
    }

    @Test
    fun `frequencies list renders ul with dictionary prefix`() {
        val out = render("{frequencies}", ctx(term = term(frequencies = freqs("JMDict" to 500))))
        out shouldContain "<ul data-content=\"frequencies\">"
        out shouldContain "JMDict: 500"
    }

    @Test
    fun `harmonic and average rank use the collected numbers`() {
        val t = term(frequencies = freqs("A" to 10, "B" to 20))
        render("{frequency-harmonic-rank}", ctx(term = t)) shouldBe "13"
        render("{frequency-average-rank}", ctx(term = t)) shouldBe "15"
    }

    @Test
    fun `missing frequencies map -1 to 9999999 for rank and 0 for occurrence`() {
        val t = term(frequencies = emptyList())
        render("{frequency-harmonic-rank}", ctx(term = t)) shouldBe "9999999"
        render("{frequency-average-rank}", ctx(term = t)) shouldBe "9999999"
        render("{frequency-harmonic-occurrence}", ctx(term = t)) shouldBe "0"
        render("{frequency-average-occurrence}", ctx(term = t)) shouldBe "0"
    }

    @Test
    fun `single-frequency-number dynamic marker returns raw numbers`() {
        val t = term(frequencies = freqs("JMDict" to 500))
        render("{single-frequency-number-jmdict}", ctx(term = t)) shouldBe "500"
    }

    // --- Pitch --------------------------------------------------------------------------

    private val pitch = PitchEntry("PitchDict", intArrayOf(1), listOf("ˈtest"))

    @Test
    fun `pitch accent positions render the downstep number`() {
        val out = render("{pitch-accent-positions}", ctx(term = term(pitches = listOf(pitch))))
        out shouldContain "data-downstep-position=\"1\""
    }

    @Test
    fun `pitch accent categories are comma-joined`() {
        render("{pitch-accent-categories}", ctx(term = term(rules = "", pitches = listOf(pitch)))) shouldBe "atamadaka"
        render("{pitch-accent-categories}", ctx(term = term(rules = "v1", pitches = listOf(pitch)))) shouldBe "kifuku"
    }

    @Test
    fun `pitch accents text format renders overline morae`() {
        val out = render("{pitch-accents}", ctx(term = term(pitches = listOf(pitch))))
        out shouldContain "pronunciation-text"
    }

    @Test
    fun `pitch accent graphs render svg`() {
        render("{pitch-accent-graphs}", ctx(term = term(pitches = listOf(pitch)))) shouldContain "<svg"
        render("{pitch-accent-graphs-jj}", ctx(term = term(pitches = listOf(pitch)))) shouldContain "<svg"
    }

    @Test
    fun `phonetic transcriptions render a ul`() {
        val out = render("{phonetic-transcriptions}", ctx(term = term(pitches = listOf(pitch))))
        out shouldContain "<ul>"
        out shouldContain "ˈtest"
    }

    @Test
    fun `morae split small kana into the previous mora`() {
        render("{morae}", ctx(term = term("きょう", "きょう"))) shouldBe "きょ う"
    }

    // --- Media / context ----------------------------------------------------------------

    @Test
    fun `audio and screenshot markers use the injected filenames`() {
        render("{audio}", ctx(wordAudioFilename = "a.mp3")) shouldBe "[sound:a.mp3]"
        render("{screenshot}", ctx(screenshotFilename = "s.webp")) shouldBe """<img src="s.webp" />"""
        render("{clipboard-image}", ctx(clipboardImageFilename = "c.png")) shouldBe """<img src="c.png" />"""
        render("{clipboard-text}", ctx(clipboardText = "raw")) shouldBe "raw"
    }

    @Test
    fun `audio and screenshot render empty when absent`() {
        render("{audio}") shouldBe ""
        render("{screenshot}") shouldBe ""
    }

    @Test
    fun `url document-title and search-query`() {
        render("{url}", ctx(url = "https://example.com/x")) shouldBe """<a href="https://example.com/x">https://example.com/x</a>"""
        render("{url-plain}", ctx(url = "https://example.com/x")) shouldBe "https://example.com/x"
        render("{url}", ctx(url = "")) shouldBe ""
        render("{document-title}", ctx(documentTitle = "Chapter 1")) shouldBe "Chapter 1"
        render("{search-query}", ctx(searchQuery = "a\nb")) shouldBe "a<br>b"
    }

    @Test
    fun `popup selection text is escaped with line breaks`() {
        render("{popup-selection-text}", ctx(popupSelection = "<b>x</b>\ny")) shouldBe "&lt;b&gt;x&lt;/b&gt;<br>y"
    }

    // --- Sentence / cloze ---------------------------------------------------------------

    private val cloze = AnkiCloze(
        sentence = "私は食べる。",
        prefix = "私は",
        body = "食べる",
        bodyKana = "たべる",
        suffix = "。",
    )

    @Test
    fun `sentence and cloze markers`() {
        render("{sentence}", ctx(cloze = cloze)) shouldBe "私は食べる。"
        render("{cloze-prefix}", ctx(cloze = cloze)) shouldBe "私は"
        render("{cloze-body}", ctx(cloze = cloze)) shouldBe "食べる"
        render("{cloze-body-kana}", ctx(cloze = cloze)) shouldBe "たべる"
        render("{cloze-suffix}", ctx(cloze = cloze)) shouldBe "。"
    }

    @Test
    fun `sentence-furigana uses the precomputed media and falls back to the sentence`() {
        render("{sentence-furigana}", ctx(cloze = cloze, sentenceFuriganaHtml = "<ruby>食<rt>た</rt></ruby>")) shouldBe "<ruby>食<rt>た</rt></ruby>"
        render("{sentence-furigana}", ctx(cloze = cloze)) shouldBe "私は食べる。"
        render("{sentence-furigana-plain}", ctx(cloze = cloze, sentenceFuriganaPlain = "私 食[た]べる")) shouldBe "私 食[た]べる"
    }

    @Test
    fun `sentence-bold wraps the body`() {
        render("{sentence-bold}", ctx(cloze = cloze)) shouldBe "私は<b>食べる</b>。"
    }

    // --- Cloze builder ------------------------------------------------------------------

    @Test
    fun `buildCloze splits the sentence around the matched surface form`() {
        val built = AnkiMarkerEngine.buildCloze("私は走った。", 2, "走る", "はしる", "走った")
        built.prefix shouldBe "私は"
        built.body shouldBe "走った"
        built.suffix shouldBe "。"
        built.bodyKana shouldBe "はしる"
    }

    // --- Kanji --------------------------------------------------------------------------

    private val kanji = KanjiResult("日", listOf(KanjiEntry("Kanjidic", "ニチ,ジツ", "ひ,か", "", listOf("day"), mapOf("strokes" to "4"))))

    @Test
    fun `kanji markers`() {
        render("{character}", ctx(kanji = kanji)) shouldBe "日"
        render("{kunyomi}", ctx(kanji = kanji)) shouldBe "ひ, か"
        render("{onyomi}", ctx(kanji = kanji)) shouldBe "ニチ, ジツ"
        render("{onyomi-hiragana}", ctx(kanji = kanji)) shouldBe "にち, じつ"
        render("{stroke-count}", ctx(kanji = kanji)) shouldBe "Stroke count: 4"
    }

    @Test
    fun `stroke-count is Unknown when no stat is present`() {
        render("{stroke-count}", ctx(kanji = null)) shouldBe "Stroke count: Unknown"
    }

    // --- KUN extras ---------------------------------------------------------------------

    @Test
    fun `kun extras degrade to empty when data is absent`() {
        render("{word-audio}") shouldBe ""
        render("{sentence-audio}") shouldBe ""
        render("{media-name}") shouldBe ""
        render("{chapter}") shouldBe ""
        render("{selected-glossary-no-fallback}") shouldBe ""
        render("{sentence-translation}") shouldBe ""
    }

    @Test
    fun `kun media and selection markers`() {
        render("{media-name}", ctx(media = AnkiMediaInfo("Manga", "Ch 1"))) shouldBe "Manga"
        render("{chapter}", ctx(media = AnkiMediaInfo("Manga", "Ch 1"))) shouldBe "Ch 1"
        render("{media}", ctx(media = AnkiMediaInfo("Manga", "Ch 1"))) shouldBe "Manga-Ch 1"
        render("{selection-text}", ctx(selectionText = "sel")) shouldBe "sel"
        render("{selected-glossary}", ctx(selectedDict = "JMDict")) shouldContain "to eat"
    }

    // --- Field map ----------------------------------------------------------------------

    @Test
    fun `parseFieldMap supports legacy enum values`() {
        AnkiMarkerEngine.parseFieldMap("""{"Expression":"TARGET_WORD","MainDefinition":"DEFINITION"}""") shouldBe
            mapOf("Expression" to "{expression}", "MainDefinition" to "{glossary}")
    }

    @Test
    fun `parseFieldMap preserves template values and skips blanks`() {
        AnkiMarkerEngine.parseFieldMap("""{"A":"{expression} <b>x</b>","B":"  "}""") shouldBe
            mapOf("A" to "{expression} <b>x</b>")
    }
}
// KUN <--
