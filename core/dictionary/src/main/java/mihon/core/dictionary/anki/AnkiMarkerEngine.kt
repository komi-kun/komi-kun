// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Adapted from Chimahon's `chimahon/anki/AnkiCardCreator.kt` (GPL-3.0) marker renderer and from
// the authoritative Yomitan behavioral spec (`docs/design/yomitan-spec/06-anki.md` §a/§b). The
// marker scanner is the spec regex `\{[\p{Letter}\p{Number}_-]+\}`; unknown markers become
// `{name-render-error}`. Structured glossary JSON is parsed with kotlinx.serialization so the
// whole engine runs on the JVM.
package mihon.core.dictionary.anki

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.FrequencyEntry
import mihon.core.dictionary.model.GlossaryEntry
import mihon.core.dictionary.model.KanjiResult
import mihon.core.dictionary.model.PitchEntry
import mihon.core.dictionary.model.TermResult
import mihon.core.dictionary.model.TransformGroup
import kotlin.math.floor

/** Cloze split around the looked-up term (`06-anki.md` §a; `anki-note-data-creator.js:958-998`). */
data class AnkiCloze(
    val sentence: String,
    val prefix: String,
    val body: String,
    val bodyKana: String,
    val suffix: String,
)

/** Reader context for `// KUN` `media`/`chapter` markers. */
data class AnkiMediaInfo(
    val mangaTitle: String = "",
    val chapterName: String = "",
)

/** One content-addressed dictionary-media reference discovered while rendering. */
data class AnkiDictionaryMediaReference(
    val dictionary: String,
    val path: String,
    val placeholder: String,
)

/** Collects `{dictionaryMedia}` references and hands out stable placeholders (`builder:394-498`). */
class AnkiDictionaryMediaContext {
    private val items = linkedMapOf<Pair<String, String>, AnkiDictionaryMediaReference>()

    val references: List<AnkiDictionaryMediaReference> get() = items.values.toList()

    fun placeholderFor(dictionary: String, path: String): String {
        val key = dictionary to path
        return items.getOrPut(key) {
            val extension = path.substringBefore('?')
                .substringAfterLast('.', "png")
                .replace(Regex("[^A-Za-z0-9]"), "")
                .ifBlank { "png" }
                .lowercase()
            AnkiDictionaryMediaReference(
                dictionary = dictionary,
                path = path,
                placeholder = "komikun_dict_${items.size}.$extension",
            )
        }.placeholder
    }
}

/** Everything a marker can read while a field template is rendered. */
data class AnkiMarkerContext(
    val term: TermResult? = null,
    val kanji: KanjiResult? = null,
    val process: List<TransformGroup> = emptyList(),
    val cloze: AnkiCloze? = null,
    val media: AnkiMediaInfo? = null,
    val glossaryIndex: Int? = null,
    val selectedDict: String? = null,
    val popupSelection: String? = null,
    val clipboardText: String? = null,
    val clipboardImageFilename: String? = null,
    val screenshotFilename: String? = null,
    val wordAudioFilename: String? = null,
    val sentenceAudioFilename: String? = null,
    val documentTitle: String = "",
    val url: String = "",
    val searchQuery: String = "",
    val selectionText: String? = null,
    val sentenceFuriganaHtml: String? = null,
    val sentenceFuriganaPlain: String? = null,
    val styles: List<DictionaryStyle> = emptyList(),
    val exportMedia: AnkiDictionaryMediaContext? = null,
)

/**
 * The marker engine: a pure, Android-free string renderer (`06-anki.md` §a). Args live in the
 * template definition, never in the marker, so there is no `{marker:arg}` syntax.
 */
object AnkiMarkerEngine {

    /**
     * Exactly Yomitan's `markerPattern` (`anki-util.js:23`): `\{[\p{Letter}\p{Number}_-]+\}`.
     * The JVM regex engine (unit tests) does not accept the long `\p{Letter}`/`\p{Number}` names,
     * so the equivalent `\p{L}`/`\p{N}` shorthands are used; Android/ICU accepts both.
     */
    val MARKER_PATTERN = Regex("""\{([\p{L}\p{N}_-]+)\}""")

    /** Field → template map parsed from the user's JSON (`AnkiCardCreator.parseFieldMap`). */
    fun parseFieldMap(json: String): Map<String, String> {
        if (json.isBlank() || json.trim() == "{}") return emptyMap()
        return try {
            val obj = AnkiJson.parseToJsonElement(json).jsonObject
            buildMap {
                obj.keys.forEach { key ->
                    val raw = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@forEach
                    if (raw.isNotEmpty()) put(key, normalizeFieldValue(raw))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val LEGACY_FIELD_MARKERS = mapOf(
        "TARGET_WORD" to Marker.EXPRESSION,
        "READING" to Marker.READING,
        "FURIGANA" to Marker.FURIGANA,
        "DEFINITION" to Marker.GLOSSARY,
        "SENTENCE" to Marker.SENTENCE,
        "FREQUENCY" to Marker.FREQUENCY_HARMONIC_RANK,
        "PITCH_ACCENT" to Marker.PITCH_ACCENTS,
        "NONE" to "",
    )

    private fun normalizeFieldValue(raw: String): String {
        // If it already looks like a template (markers or HTML), preserve it.
        if (raw.contains("{") || raw.contains("<") || raw.contains(">")) return raw
        val parts = raw.split(",")
        if (parts.all { it.trim() in LEGACY_FIELD_MARKERS }) {
            return parts.mapNotNull { part ->
                val marker = LEGACY_FIELD_MARKERS[part.trim()]
                if (marker.isNullOrBlank()) null else "{$marker}"
            }.joinToString("<br>")
        }
        return raw
    }

    /** Render one field template, replacing every `{marker}` with its expansion. */
    fun render(template: String, context: AnkiMarkerContext): String {
        if (template.isBlank()) return ""
        return MARKER_PATTERN.replace(template) { match ->
            val marker = match.groupValues[1]
            try {
                renderMarker(marker, context)
            } catch (_: Exception) {
                "{$marker-render-error}"
            }
        }
    }

    /** True when a template references any `{marker}`. */
    fun containsMarker(text: String, marker: String): Boolean = MARKER_PATTERN.findAll(text).any { it.groupValues[1] == marker }

    fun markersIn(text: String): List<String> = MARKER_PATTERN.findAll(text).map { it.groupValues[1] }.toList()

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun renderMarker(marker: String, ctx: AnkiMarkerContext): String {
        val term = ctx.term
        val glossaries = ctx.glossaryEntries()
        return when (marker) {
            Marker.EXPRESSION -> escapeHtml(term?.expression.orEmpty())
            Marker.READING -> escapeHtml(term?.reading.orEmpty())
            Marker.FURIGANA -> buildFuriganaHtml(term?.expression.orEmpty(), term?.reading.orEmpty())
            Marker.FURIGANA_PLAIN -> buildFuriganaPlain(term?.expression.orEmpty(), term?.reading.orEmpty())
            Marker.AUDIO -> ctx.wordAudioFilename?.let { "[sound:$it]" }.orEmpty()
            Marker.WORD_AUDIO -> ctx.wordAudioFilename?.let { "[sound:$it]" }.orEmpty()
            Marker.SENTENCE_AUDIO -> ctx.sentenceAudioFilename?.let { "[sound:$it]" }.orEmpty()

            Marker.GLOSSARY -> buildGlossary(glossaries, brief = false, noDictTag = false, firstOnly = false, styles = ctx.styles, exportMedia = ctx.exportMedia)
            Marker.GLOSSARY_BRIEF -> buildGlossary(glossaries, brief = true, noDictTag = false, firstOnly = false, styles = ctx.styles, exportMedia = ctx.exportMedia)
            Marker.GLOSSARY_NO_DICT -> buildGlossary(glossaries, brief = false, noDictTag = true, firstOnly = false, styles = ctx.styles, exportMedia = ctx.exportMedia)
            Marker.GLOSSARY_FIRST -> buildGlossary(glossaries, brief = false, noDictTag = false, firstOnly = true, styles = ctx.styles, exportMedia = ctx.exportMedia)
            Marker.GLOSSARY_FIRST_BRIEF -> buildGlossary(glossaries, brief = true, noDictTag = false, firstOnly = true, styles = ctx.styles, exportMedia = ctx.exportMedia)
            Marker.GLOSSARY_FIRST_NO_DICT -> buildGlossary(glossaries, brief = false, noDictTag = true, firstOnly = true, styles = ctx.styles, exportMedia = ctx.exportMedia)
            Marker.GLOSSARY_PLAIN -> buildGlossaryPlain(glossaries, noDictTag = false)
            Marker.GLOSSARY_PLAIN_NO_DICT -> buildGlossaryPlain(glossaries, noDictTag = true)

            Marker.SENTENCE -> ctx.cloze?.let { escapeHtml(it.sentence) }.orEmpty()
            Marker.SENTENCE_BOLD -> ctx.cloze?.let { "${escapeHtml(it.prefix)}<b>${escapeHtml(it.body)}</b>${escapeHtml(it.suffix)}" }.orEmpty()
            Marker.SENTENCE_FURIGANA -> ctx.sentenceFuriganaHtml ?: ctx.cloze?.let { escapeHtml(it.sentence) }.orEmpty()
            Marker.SENTENCE_FURIGANA_PLAIN -> ctx.sentenceFuriganaPlain ?: ctx.cloze?.sentence.orEmpty()
            Marker.CLOZE_PREFIX -> ctx.cloze?.let { escapeHtml(it.prefix) }.orEmpty()
            Marker.CLOZE_BODY -> ctx.cloze?.let { escapeHtml(it.body) }.orEmpty()
            Marker.CLOZE_BODY_KANA -> ctx.cloze?.let { escapeHtml(it.bodyKana) }.orEmpty()
            Marker.CLOZE_SUFFIX -> ctx.cloze?.let { escapeHtml(it.suffix) }.orEmpty()

            Marker.TAGS -> buildTags(term)
            Marker.PART_OF_SPEECH -> buildPartOfSpeech(term)
            Marker.CONJUGATION -> buildConjugation(ctx)
            Marker.DICTIONARY, Marker.DICTIONARY_ALIAS -> glossaries.firstOrNull()?.let { escapeHtml(it.dictName) }.orEmpty()

            Marker.FREQUENCIES -> buildFrequenciesList(term?.frequencies.orEmpty(), null)
            Marker.FREQUENCY_LOWEST -> selectLowestFrequencyValue(term?.frequencies.orEmpty()).orEmpty()
            Marker.FREQUENCY_HARMONIC_RANK -> frequencyRank(term?.frequencies.orEmpty()) { it.harmonic() }
            Marker.FREQUENCY_HARMONIC_OCCURRENCE -> frequencyOccurrence(term?.frequencies.orEmpty()) { it.harmonic() }
            Marker.FREQUENCY_AVERAGE_RANK -> frequencyRank(term?.frequencies.orEmpty()) { it.average() }
            Marker.FREQUENCY_AVERAGE_OCCURRENCE -> frequencyOccurrence(term?.frequencies.orEmpty()) { it.average() }

            Marker.PITCH_ACCENTS -> buildPitchAccents(term?.reading.orEmpty(), term?.pitches.orEmpty(), PitchFormat.TEXT)
            Marker.PITCH_ACCENT_POSITIONS -> buildPitchAccentPositions(term?.pitches.orEmpty())
            Marker.PITCH_ACCENT_CATEGORIES -> buildPitchCategories(term?.reading.orEmpty(), term?.rules.orEmpty(), term?.pitches.orEmpty())
            Marker.PITCH_ACCENT_GRAPHS, Marker.PITCH_ACCENT_GRAPHS_JJ -> buildPitchAccentGraphs(term?.reading.orEmpty(), term?.pitches.orEmpty())
            Marker.PHONETIC_TRANSCRIPTIONS -> buildPhoneticTranscriptions(term?.pitches.orEmpty())
            Marker.MORAE -> buildMorae(term?.reading.orEmpty())

            Marker.SCREENSHOT -> ctx.screenshotFilename?.let { """<img src="$it" />""" }.orEmpty()
            Marker.CLIPBOARD_IMAGE -> ctx.clipboardImageFilename?.let { """<img src="$it" />""" }.orEmpty()
            Marker.CLIPBOARD_TEXT -> ctx.clipboardText.orEmpty()

            Marker.SEARCH_QUERY -> escapeHtmlWithLineBreaks(ctx.searchQuery)
            Marker.URL -> if (ctx.url.isBlank()) "" else """<a href="${attrEscape(ctx.url)}">${escapeHtml(ctx.url)}</a>"""
            Marker.URL_PLAIN -> escapeHtml(ctx.url)
            Marker.DOCUMENT_TITLE -> escapeHtml(ctx.documentTitle)
            Marker.POPUP_SELECTION_TEXT -> ctx.popupSelection?.let { escapeHtmlWithLineBreaks(it) }.orEmpty()
            Marker.SELECTION_TEXT -> ctx.selectionText?.let { escapeHtml(it) }.orEmpty()
            Marker.MEDIA_NAME, Marker.MISC_INFO -> ctx.media?.mangaTitle?.let { escapeHtml(it) }.orEmpty()
            Marker.CHAPTER -> ctx.media?.chapterName?.let { escapeHtml(it) }.orEmpty()
            Marker.MEDIA -> buildMedia(ctx.media)
            Marker.SENTENCE_TRANSLATION -> ""

            Marker.CHARACTER -> escapeHtml(ctx.kanji?.character.orEmpty())
            Marker.KUNYOMI -> ctx.kanji?.entries.orEmpty().joinToString(", ") { escapeHtml(splitReadings(it.kunyomi)) }
            Marker.ONYOMI -> ctx.kanji?.entries.orEmpty().joinToString(", ") { escapeHtml(splitReadings(it.onyomi)) }
            Marker.ONYOMI_HIRAGANA -> ctx.kanji?.entries.orEmpty().joinToString(", ") { escapeHtml(toHiragana(splitReadings(it.onyomi))) }
            Marker.STROKE_COUNT -> buildStrokeCount(ctx.kanji)

            Marker.SELECTED_GLOSSARY -> buildSelectedGlossary(ctx, glossaries, fallback = true)
            Marker.SELECTED_GLOSSARY_NO_FALLBACK -> buildSelectedGlossary(ctx, glossaries, fallback = false)

            else -> parseDynamicMarker(marker, ctx, glossaries)
        }
    }

    private fun AnkiMarkerContext.glossaryEntries(): Array<GlossaryEntry> {
        val all = term?.glossaries ?: return emptyArray()
        val index = glossaryIndex
        return if (index != null && index >= 0) {
            arrayOf(all.getOrElse(index) { all.first() })
        } else {
            all.toTypedArray()
        }
    }

    private fun buildSelectedGlossary(
        ctx: AnkiMarkerContext,
        glossaries: Array<GlossaryEntry>,
        fallback: Boolean,
    ): String {
        val selected = ctx.selectedDict?.takeIf { it.isNotBlank() } ?: return if (fallback) {
            buildGlossary(glossaries, brief = false, noDictTag = false, firstOnly = true, styles = ctx.styles, exportMedia = ctx.exportMedia)
        } else {
            ""
        }
        return buildGlossary(
            glossaries,
            brief = false,
            noDictTag = false,
            firstOnly = false,
            dictionaryFilter = selected,
            styles = ctx.styles,
            exportMedia = ctx.exportMedia,
        )
    }

    private fun buildMedia(media: AnkiMediaInfo?): String {
        if (media == null || media.mangaTitle.isBlank()) return ""
        return if (media.chapterName.isNotBlank()) {
            "${escapeHtml(media.mangaTitle)}-${escapeHtml(media.chapterName)}"
        } else {
            escapeHtml(media.mangaTitle)
        }
    }

    // =====================================================================================
    // Dynamic markers
    // =====================================================================================

    private fun parseDynamicMarker(
        marker: String,
        ctx: AnkiMarkerContext,
        glossaries: Array<GlossaryEntry>,
    ): String {
        return parseSingleGlossaryMarker(marker, ctx, glossaries)
            ?: parseSingleFrequencyMarker(marker, ctx)
            ?: throw IllegalArgumentException("Unknown marker {$marker}")
    }

    private fun parseSingleGlossaryMarker(
        marker: String,
        ctx: AnkiMarkerContext,
        glossaries: Array<GlossaryEntry>,
    ): String? {
        val prefix = Marker.SINGLE_GLOSSARY_PREFIX
        if (!marker.startsWith(prefix)) return null

        val rest = marker.substring(prefix.length)
        if (rest.isBlank()) return ""

        val tokens = rest.split("-").toMutableList()
        var hasBrief = false
        var hasFirst = false
        var hasPlain = false
        var noDictTag = false
        while (tokens.isNotEmpty()) {
            val suffix = tokens.last().lowercase()
            when {
                suffix == "brief" -> {
                    hasBrief = true
                    tokens.removeAt(tokens.lastIndex)
                }

                suffix == "first" -> {
                    hasFirst = true
                    tokens.removeAt(tokens.lastIndex)
                }

                suffix == "plain" -> {
                    hasPlain = true
                    tokens.removeAt(tokens.lastIndex)
                }

                tokens.size >= 2 &&
                    tokens[tokens.lastIndex - 1].equals("no", ignoreCase = true) &&
                    suffix == "dictionary" -> {
                    noDictTag = true
                    tokens.removeAt(tokens.lastIndex)
                    tokens.removeAt(tokens.lastIndex)
                }

                else -> break
            }
        }

        val dictName = tokens.joinToString("-").trim()
        if (dictName.isEmpty()) return ""
        val dictionaryFilter = dictName.takeUnless { it.equals("all", ignoreCase = true) }

        val filtered = if (dictionaryFilter == null) {
            glossaries
        } else {
            glossaries.filter { it.dictName.contains(dictionaryFilter, ignoreCase = true) }.toTypedArray()
        }

        if (hasPlain) {
            val entries = if (hasFirst) filtered.take(1).toTypedArray() else filtered
            return buildGlossaryPlain(entries, noDictTag = noDictTag)
        }

        return buildGlossary(
            filtered,
            brief = hasBrief,
            noDictTag = noDictTag,
            firstOnly = hasFirst,
            styles = ctx.styles,
            exportMedia = ctx.exportMedia,
        )
    }

    private fun parseSingleFrequencyMarker(marker: String, ctx: AnkiMarkerContext): String? {
        val frequencies = ctx.term?.frequencies.orEmpty()
        return when {
            marker.startsWith(Marker.SINGLE_FREQUENCY_NUMBER_PREFIX) -> {
                val key = marker.removePrefix(Marker.SINGLE_FREQUENCY_NUMBER_PREFIX)
                buildSingleFrequencyNumber(frequencies, key)
            }

            marker.startsWith(Marker.SINGLE_FREQUENCY_PREFIX) -> {
                val key = marker.removePrefix(Marker.SINGLE_FREQUENCY_PREFIX)
                buildFrequenciesList(frequencies, key)
            }

            else -> null
        }
    }

    // =====================================================================================
    // Furigana (`anki-template-renderer.js:178-210`)
    // =====================================================================================

    fun buildFuriganaHtml(expression: String, reading: String): String {
        if (expression.isBlank()) return ""
        if (reading.isBlank() || expression == reading) return escapeHtml(expression)
        val segments = distributeFurigana(expression, reading)
        return segments.joinToString("") { (text, furigana) ->
            if (furigana.isNotEmpty()) {
                "<ruby>${escapeHtml(text)}<rt>${escapeHtml(furigana)}</rt></ruby>"
            } else {
                escapeHtml(text)
            }
        }
    }

    fun buildFuriganaPlain(expression: String, reading: String): String {
        if (expression.isBlank()) return ""
        if (reading.isBlank() || expression == reading) return expression
        val segments = distributeFurigana(expression, reading)
        return buildString {
            for ((text, furigana) in segments) {
                if (furigana.isNotEmpty()) {
                    append(text).append('[').append(furigana).append(']')
                } else {
                    append(text).append(' ')
                }
            }
        }
    }

    private data class FuriganaGroup(val isKanji: Boolean, val text: String, val normalized: String?)

    fun distributeFurigana(expression: String, reading: String): List<Pair<String, String>> {
        if (reading == expression) return listOf(expression to "")
        val groups = mutableListOf<FuriganaGroup>()
        for (char in expression) {
            val kanji = isKanji(char)
            if (groups.lastOrNull()?.isKanji == kanji) {
                val previous = groups.removeAt(groups.lastIndex)
                val text = previous.text + char
                groups += FuriganaGroup(kanji, text, if (!kanji) toHiragana(text) else null)
            } else {
                groups += FuriganaGroup(kanji, char.toString(), if (!kanji) toHiragana(char.toString()) else null)
            }
        }
        return segmentizeFurigana(reading, toHiragana(reading), groups, 0)
            ?: listOf(expression to reading)
    }

    private fun segmentizeFurigana(
        reading: String,
        readingNormalized: String,
        groups: List<FuriganaGroup>,
        start: Int,
    ): List<Pair<String, String>>? {
        if (start >= groups.size) return if (reading.isEmpty()) emptyList() else null
        val group = groups[start]
        if (!group.isKanji) {
            if (group.normalized == null || !readingNormalized.startsWith(group.normalized)) return null
            val tail = segmentizeFurigana(
                reading.substring(group.text.length),
                readingNormalized.substring(group.text.length),
                groups,
                start + 1,
            )?.toMutableList() ?: return null
            if (reading.startsWith(group.text)) {
                tail.add(0, group.text to "")
            } else {
                tail.addAll(0, getFuriganaKanaSegments(group.text, reading))
            }
            return tail
        }

        var result: List<Pair<String, String>>? = null
        for (i in reading.length downTo group.text.length) {
            val tail = segmentizeFurigana(reading.substring(i), readingNormalized.substring(i), groups, start + 1)
                ?: continue
            if (result != null) return null
            result = tail.toMutableList().also { it.add(0, group.text to reading.substring(0, i)) }
            if (groups.size - start == 1) break
        }
        return result
    }

    private fun getFuriganaKanaSegments(text: String, reading: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var start = 0
        var state = reading.firstOrNull() == text.firstOrNull()
        for (i in 1 until text.length) {
            val nextState = reading.getOrNull(i) == text[i]
            if (nextState != state) {
                result += text.substring(start, i) to if (state) "" else reading.substring(start, i.coerceAtMost(reading.length))
                start = i
                state = nextState
            }
        }
        result += text.substring(start) to if (state) "" else reading.substring(start.coerceAtMost(reading.length))
        return result
    }

    private fun isKanji(ch: Char) =
        ch.code in 0x4E00..0x9FFF ||
            ch.code in 0x3400..0x4DBF ||
            ch.code in 0xF900..0xFAFF ||
            ch == '\u3005'

    fun toHiragana(str: String) = str.map { ch ->
        if (ch.code in 0x30A1..0x30F6) (ch.code - 0x60).toChar() else ch
    }.joinToString("")

    // =====================================================================================
    // Cloze (`anki-note-data-creator.js:958-998`)
    // =====================================================================================

    fun buildCloze(
        sentence: String,
        offset: Int,
        expression: String,
        reading: String,
        matched: String? = null,
    ): AnkiCloze {
        if (sentence.isEmpty() || offset < 0 || expression.isEmpty()) {
            return AnkiCloze(sentence, prefix = "", body = expression, bodyKana = reading, suffix = "")
        }

        val safeOffset = offset.coerceIn(0, sentence.length)
        val prefix = sentence.substring(0, safeOffset)
        val boldLen = (matched?.length ?: expression.length).coerceAtLeast(1)
        val bodyEnd = (safeOffset + boldLen).coerceAtMost(sentence.length)
        val body = sentence.substring(safeOffset, bodyEnd)
        val suffix = sentence.substring(bodyEnd)
        return AnkiCloze(sentence, prefix, body, calculateBodyKana(expression, reading), suffix)
    }

    private fun calculateBodyKana(expression: String, reading: String): String {
        if (reading.isEmpty()) return expression
        if (expression == reading) return expression
        return distributeFurigana(expression, reading).joinToString("") { (text, furigana) ->
            if (furigana.isNotEmpty()) furigana else text
        }
    }

    // =====================================================================================
    // Glossary (`default-anki-field-templates.handlebars`)
    // =====================================================================================

    private fun buildGlossary(
        glossaries: Array<GlossaryEntry>,
        brief: Boolean,
        noDictTag: Boolean,
        firstOnly: Boolean,
        dictionaryFilter: String? = null,
        styles: List<DictionaryStyle> = emptyList(),
        exportMedia: AnkiDictionaryMediaContext? = null,
    ): String {
        if (glossaries.isEmpty()) return ""
        val filtered = if (dictionaryFilter != null) {
            glossaries.filter { it.dictName.contains(dictionaryFilter, ignoreCase = true) }.toTypedArray()
        } else {
            glossaries
        }
        if (filtered.isEmpty()) return ""

        val entries = if (firstOnly) arrayOf(filtered[0]) else filtered
        val scopedStyles = buildScopedDictionaryStyles(entries.map { it.dictName }.toSet(), styles)

        val sb = StringBuilder()
        sb.append("""<div style="text-align: left;" class="yomitan-glossary">""")
        if (entries.size == 1 && firstOnly) {
            sb.append(renderGlossarySingle(entries[0], brief, noDictTag, "", exportMedia))
        } else {
            sb.append("<ol>")
            var previousDictName = ""
            for (entry in entries) {
                sb.append("""<li data-dictionary="${attrEscape(entry.dictName)}">""")
                sb.append(renderGlossarySingle(entry, brief, noDictTag, previousDictName, exportMedia))
                sb.append("</li>")
                previousDictName = entry.dictName
            }
            sb.append("</ol>")
        }
        sb.append("</div>")
        if (scopedStyles.isNotEmpty()) {
            sb.append("<style>").append(scopedStyles).append("</style>")
        }
        return sb.toString()
    }

    private fun renderGlossarySingle(
        entry: GlossaryEntry,
        brief: Boolean,
        noDictTag: Boolean,
        previousDictName: String,
        exportMedia: AnkiDictionaryMediaContext?,
    ): String {
        val sb = StringBuilder()
        if (!brief) {
            val tagParts = mutableListOf<String>()
            if (entry.definitionTags.isNotBlank()) {
                tagParts += entry.definitionTags.split(" ").filter { it.isNotBlank() }
            }
            if (!noDictTag && entry.dictName.isNotBlank() && entry.dictName != previousDictName) {
                tagParts += entry.dictName
            }
            if (tagParts.isNotEmpty()) {
                sb.append("<i>(${tagParts.joinToString(", ")})</i> ")
            }
        }
        val content = glossaryToHtml(entry.glossary, entry.dictName, exportMedia)
        if (content.isNotEmpty()) sb.append(content)
        return sb.toString()
    }

    private fun buildGlossaryPlain(glossaries: Array<GlossaryEntry>, noDictTag: Boolean): String {
        if (glossaries.isEmpty()) return ""
        return glossaries.joinToString("<br>") { entry ->
            buildString {
                if (!noDictTag && entry.dictName.isNotBlank()) {
                    append("(${escapeHtml(entry.dictName)})<br>")
                }
                append(glossaryToPlainText(entry.glossary))
            }
        }
    }

    private fun buildScopedDictionaryStyles(
        dictionaryNames: Set<String>,
        styles: List<DictionaryStyle>,
    ): String {
        if (dictionaryNames.isEmpty() || styles.isEmpty()) return ""
        return styles
            .asSequence()
            .filter { it.dictName in dictionaryNames && it.styles.isNotBlank() }
            .joinToString("\n") { style ->
                scopeDictionaryCss(
                    css = sanitizeDictionaryCss(style.styles),
                    scopeSelector = """[data-dictionary="${cssDoubleQuotedContent(style.dictName)}"]""",
                )
            }
            .trim()
    }

    private fun sanitizeDictionaryCss(css: String): String = css
        .replace(Regex("(?is)<\\s*/?\\s*style[^>]*>"), "")
        .replace(Regex("(?is)@import\\s+[^;]+;"), "")
        .replace(Regex("(?is)url\\s*\\(\\s*javascript:[^)]+\\)"), "")
        .trim()

    private fun scopeDictionaryCss(
        css: String,
        scopeSelector: String,
        parentSelectors: List<String> = emptyList(),
    ): String {
        if (css.isBlank()) return ""
        if (!css.contains("{")) return "$scopeSelector { $css }"

        val out = StringBuilder()
        var i = 0
        while (i < css.length) {
            while (i < css.length && css[i].isWhitespace()) i++
            if (i >= css.length) break
            if (css.startsWith("/*", i)) {
                val end = css.indexOf("*/", i + 2)
                i = if (end >= 0) end + 2 else css.length
                continue
            }
            val preludeStart = i
            while (i < css.length && css[i] != '{' && css[i] != ';') i++
            if (i >= css.length) break
            if (css[i] == ';') {
                i++
                continue
            }
            val prelude = css.substring(preludeStart, i).trim()
            val bodyStart = i + 1
            val bodyEnd = findCssBlockEnd(css, i)
            if (bodyEnd <= bodyStart) break
            val body = css.substring(bodyStart, bodyEnd).trim()

            when {
                prelude.startsWith("@media", true) || prelude.startsWith("@supports", true) || prelude.startsWith("@layer", true) -> {
                    val scopedBody = scopeDictionaryCss(body, scopeSelector, parentSelectors)
                    if (scopedBody.isNotBlank()) out.append(prelude).append(" {\n").append(scopedBody).append("\n}\n")
                }

                prelude.startsWith("@", true) -> out.append(prelude).append(" { ").append(body).append(" }\n")

                else -> {
                    val selectors = if (parentSelectors.isNotEmpty()) {
                        splitCssSelectorList(prelude).flatMap { selector ->
                            parentSelectors.map { parent -> combineNestedCssSelector(selector.trim(), parent) }
                        }
                    } else {
                        splitCssSelectorList(prelude).map { prefixCssSelector(it.trim(), scopeSelector) }
                    }.filter { it.isNotBlank() }
                    if (selectors.isNotEmpty()) {
                        val (declarations, nestedRules) = splitCssDeclarationsAndNestedRules(body)
                        if (declarations.isNotBlank()) {
                            out.append(selectors.joinToString(", ")).append(" { ").append(declarations).append(" }\n")
                        }
                        if (nestedRules.isNotBlank()) {
                            val scopedNestedRules = scopeDictionaryCss(nestedRules, scopeSelector, selectors)
                            if (scopedNestedRules.isNotBlank()) out.append(scopedNestedRules).append("\n")
                        }
                    }
                }
            }
            i = bodyEnd + 1
        }
        return out.toString().trim()
    }

    private fun findCssBlockEnd(css: String, openBraceIndex: Int): Int {
        var depth = 0
        var i = openBraceIndex
        while (i < css.length) {
            when (css[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return css.length
    }

    private fun splitCssSelectorList(selectorText: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var bracketDepth = 0
        var quote: Char? = null

        selectorLoop@ for (i in selectorText.indices) {
            val ch = selectorText[i]
            val prev = selectorText.getOrNull(i - 1)
            if (quote != null) {
                current.append(ch)
                if (ch == quote && prev != '\\') quote = null
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                current.append(ch)
                continue
            }
            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                ',' -> if (parenDepth == 0 && bracketDepth == 0) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    current.clear()
                    continue@selectorLoop
                }
            }
            current.append(ch)
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    private fun findTopLevelCssChar(text: String, charToFind: Char, startIndex: Int): Int {
        var quote: Char? = null
        var parenDepth = 0
        var bracketDepth = 0
        var i = startIndex
        while (i < text.length) {
            val ch = text[i]
            val prev = text.getOrNull(i - 1)
            if (quote != null) {
                if (ch == quote && prev != '\\') quote = null
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                quote = ch
                i++
                continue
            }
            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            }
            if (ch == charToFind && parenDepth == 0 && bracketDepth == 0) return i
            i++
        }
        return -1
    }

    private fun splitCssDeclarationsAndNestedRules(blockContent: String): Pair<String, String> {
        val declarations = StringBuilder()
        val nestedRules = StringBuilder()
        var i = 0
        while (i < blockContent.length) {
            while (i < blockContent.length && blockContent[i].isWhitespace()) i++
            if (i >= blockContent.length) break
            val nextSemi = findTopLevelCssChar(blockContent, ';', i)
            val nextBrace = findTopLevelCssChar(blockContent, '{', i)
            if (nextBrace >= 0 && (nextSemi < 0 || nextBrace < nextSemi)) {
                val closeBrace = findCssBlockEnd(blockContent, nextBrace)
                if (closeBrace <= nextBrace) {
                    declarations.append(blockContent.substring(i))
                    break
                }
                nestedRules.append(blockContent.substring(i, closeBrace + 1)).append('\n')
                i = closeBrace + 1
                continue
            }
            if (nextSemi >= 0) {
                declarations.append(blockContent.substring(i, nextSemi + 1)).append('\n')
                i = nextSemi + 1
            } else {
                declarations.append(blockContent.substring(i))
                break
            }
        }
        return declarations.toString().trim() to nestedRules.toString().trim()
    }

    private fun combineNestedCssSelector(selector: String, parentSelector: String): String {
        if (selector.isBlank()) return ""
        return if (selector.contains("&")) selector.replace("&", parentSelector) else "$parentSelector $selector"
    }

    private fun prefixCssSelector(selector: String, scopeSelector: String): String {
        if (selector.isBlank()) return ""
        if (selector.contains("&")) return selector.replace("&", scopeSelector)
        if (selector.equals(":root", true) || selector.equals("html", true) || selector.equals("body", true)) {
            return scopeSelector
        }
        val rootWithAttr = Regex("""(?i)^(:root|html|body)(\[[^\]]+\])([\s\S]*)$""").find(selector)
        if (rootWithAttr != null) {
            val head = rootWithAttr.groupValues[1]
            val attr = rootWithAttr.groupValues[2]
            val tail = rootWithAttr.groupValues[3]
            return if (tail.isBlank()) "$head$attr $scopeSelector" else "$head$attr $scopeSelector$tail"
        }
        val rootDesc = Regex("""(?i)^(:root|html|body)\s+(.+)$""").find(selector)
        if (rootDesc != null) return "$scopeSelector ${rootDesc.groupValues[2]}"
        return "$scopeSelector $selector"
    }

    // =====================================================================================
    // Structured content → HTML
    // =====================================================================================

    fun glossaryToHtml(raw: String, dictionary: String = "", exportMedia: AnkiDictionaryMediaContext? = null): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return escapeHtmlWithLineBreaks(trimmed)
        }
        return try {
            when (val element = AnkiJson.parseToJsonElement(trimmed)) {
                is JsonArray -> arrayToHtml(element, dictionary, "div", exportMedia)
                is JsonObject -> objectToHtml(element, dictionary, exportMedia)
                else -> escapeHtmlWithLineBreaks(trimmed)
            }
        } catch (_: Exception) {
            escapeHtmlWithLineBreaks(trimmed)
        }
    }

    fun glossaryToPlainText(raw: String): String {
        val html = glossaryToHtml(raw)
        val text = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|li|div|tr|h[1-6])>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&quot;", "\"")
        return text
            .split('\n')
            .joinToString("\n") { line -> line.replace(Regex("[ \\t\\u000B\\u000C]+"), " ").trim() }
            .trim()
    }

    private fun arrayToHtml(
        array: JsonArray,
        dictionary: String,
        parentTag: String,
        exportMedia: AnkiDictionaryMediaContext?,
    ): String {
        val allStrings = array.all { it is JsonPrimitive && it.isString }
        if (allStrings) {
            if (array.size == 1) return contentValueToHtml(array[0], dictionary, parentTag, exportMedia)
            val isListParent = parentTag in setOf("ul", "ol", "li", "span", "td", "th")
            if (!isListParent) {
                return """<ul style="margin: 0.2em 0; padding-left: 1.2em;">""" +
                    array.joinToString("") { "<li>${contentValueToHtml(it, dictionary, "li", exportMedia)}</li>" } +
                    "</ul>"
            }
        }
        return array.joinToString("") { contentValueToHtml(it, dictionary, parentTag, exportMedia) }
    }

    private fun objectToHtml(node: JsonObject, dictionary: String, exportMedia: AnkiDictionaryMediaContext?): String {
        val type = node.string("type")
        if (type == "ruby") {
            val expression = node.string("headword")
            val reading = node.string("reading")
            return if (reading.isNotEmpty() && reading != expression) {
                "<ruby>${escapeHtml(expression)}<rt>${escapeHtml(reading)}</rt></ruby>"
            } else {
                escapeHtml(expression)
            }
        }
        if (type == "structured-content") {
            val content = node["content"] ?: return ""
            return """<span class="structured-content">${contentValueToHtml(content, dictionary, "span", exportMedia)}</span>"""
        }
        if (type == "image") return imageNodeToHtml(node, dictionary, exportMedia)

        val tag = node.string("tag").trim().lowercase()
        if (tag.isEmpty()) {
            val content = node["content"] ?: return ""
            return contentValueToHtml(content, dictionary, "div", exportMedia)
        }

        val dataObj = node["data"] as? JsonObject
        if (dataObj != null && dataObj.string("content") == "attribution") return ""
        if (tag == "img") return imageNodeToHtml(node, dictionary, exportMedia)
        if (tag == "br") return "<br>"
        if (tag == "hr") return "<hr>"

        val sb = StringBuilder("<$tag")
        val classParts = mutableListOf("gloss-sc-$tag")
        val dataAttributes = StringBuilder()

        node.string("href").takeIf { it.isNotEmpty() }?.let { sb.append(""" href="${attrEscape(it)}"""") }
        node.string("title").takeIf { it.isNotEmpty() }?.let { sb.append(""" title="${attrEscape(it)}"""") }
        node.string("lang").takeIf { it.isNotEmpty() }?.let { sb.append(""" lang="${attrEscape(it)}"""") }
        node.string("id").takeIf { it.isNotEmpty() }?.let { sb.append(""" id="${attrEscape(it)}"""") }
        if (tag == "td" || tag == "th") {
            node.int("colSpan").takeIf { it > 0 }?.let { sb.append(""" colspan="$it"""") }
            node.int("rowSpan").takeIf { it > 0 }?.let { sb.append(""" rowspan="$it"""") }
        }
        if (tag == "details" && node.boolean("open")) sb.append(" open")

        if (dataObj != null) {
            for (key in dataObj.keys) {
                val rawValue = dataObj[key]
                if (rawValue is JsonNull) continue
                val value = (rawValue as? JsonPrimitive)?.contentOrNull ?: rawValue.toString()
                if (key == "class") {
                    classParts += value.split(" ").filter { it.isNotBlank() }
                    dataAttributes.append(""" data-sc-class="${attrEscape(value)}"""")
                } else {
                    dataAttributes.append(""" ${structuredDataAttributeName(key)}="${attrEscape(value)}"""")
                }
            }
        }
        sb.append(""" class="${attrEscape(classParts.joinToString(" "))}"""")
        sb.append(dataAttributes)

        val styleObj = node["style"] as? JsonObject
        if (styleObj != null && styleObj.isNotEmpty()) {
            val cssStr = styleObj.keys.mapNotNull { prop ->
                val rawValue = styleObj[prop] ?: return@mapNotNull null
                if (rawValue is JsonNull) return@mapNotNull null
                val value = structuredStyleValue(prop, rawValue)
                if (value.isBlank()) null else "${camelToKebab(prop)}: ${attrEscape(value)}"
            }.joinToString("; ")
            if (cssStr.isNotBlank()) sb.append(""" style="$cssStr"""")
        } else {
            when (tag) {
                "ul", "ol" -> sb.append(""" style="margin: 0.2em 0; padding-left: 1.2em;"""")
                "p", "div" -> sb.append(""" style="margin: 0.1em 0;"""")
            }
        }
        sb.append(">")

        val content = node["content"]
        if (content != null) {
            if (tag == "table") {
                sb.append(tableContentToHtml(content, dictionary, exportMedia))
            } else {
                sb.append(contentValueToHtml(content, dictionary, tag, exportMedia))
            }
        }
        sb.append("</$tag>")
        return if (tag == "table") """<div class="gloss-sc-table-container">$sb</div>""" else sb.toString()
    }

    private fun contentValueToHtml(
        value: JsonElement?,
        dictionary: String,
        parentTag: String,
        exportMedia: AnkiDictionaryMediaContext?,
    ): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> if (value.isString) escapeHtmlWithLineBreaks(value.content) else value.content
        is JsonArray -> arrayToHtml(value, dictionary, parentTag, exportMedia)
        is JsonObject -> objectToHtml(value, dictionary, exportMedia)
    }

    private fun imageNodeToHtml(node: JsonObject, dictionary: String, exportMedia: AnkiDictionaryMediaContext?): String {
        val path = node.string("path").ifEmpty { node.string("src") }
        if (path.isEmpty()) return ""
        val renderPath = if (exportMedia != null && !isResolvableImagePath(path)) {
            exportMedia.placeholderFor(dictionary, path)
        } else {
            path
        }

        val sb = StringBuilder("<img")
        sb.append(""" src="${attrEscape(renderPath)}"""")
        node.string("alt").takeIf { it.isNotEmpty() }?.let { sb.append(""" alt="${attrEscape(it)}"""") }
        node.string("title").takeIf { it.isNotEmpty() }?.let { sb.append(""" title="${attrEscape(it)}"""") }

        val width = node.int("width")
        val height = node.int("height")
        val sizeUnits = node.string("sizeUnits").ifBlank { "px" }
        val appearance = node.string("appearance")
        val styleParts = mutableListOf<String>()
        if (width > 0 || height > 0) {
            styleParts.add("width: ${if (width > 0) "$width$sizeUnits" else "auto"}")
            styleParts.add("height: ${if (height > 0) "$height$sizeUnits" else "auto"}")
        }
        styleParts.add("vertical-align: middle")
        if (appearance == "monochrome") {
            sb.append(""" class="gloss-image-monochrome"""")
            styleParts.add("background-color: currentColor")
            styleParts.add("-webkit-mask-image: url('${attrEscape(renderPath)}')")
            styleParts.add("-webkit-mask-repeat: no-repeat")
            styleParts.add("-webkit-mask-size: contain")
            styleParts.add("mask-image: url('${attrEscape(renderPath)}')")
            styleParts.add("mask-repeat: no-repeat")
            styleParts.add("mask-size: contain")
        }
        sb.append(""" style="${styleParts.joinToString("; ")}"""")
        sb.append(" />")
        return sb.toString()
    }

    private fun isResolvableImagePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.startsWith("data:") || lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun tableContentToHtml(content: JsonElement?, dictionary: String, exportMedia: AnkiDictionaryMediaContext?): String {
        val items: List<JsonElement> = when (content) {
            is JsonArray -> content.toList()
            else -> listOfNotNull(content)
        }
        val hasSection = items.any { it is JsonObject && it.string("tag") in setOf("tbody", "thead", "tfoot") }
        val hasBareRows = items.any { it is JsonObject && it.string("tag") == "tr" }
        return if (hasBareRows && !hasSection) {
            val tbody = JsonObject(mapOf("tag" to JsonPrimitive("tbody"), "content" to JsonArray(items)))
            objectToHtml(tbody, dictionary, exportMedia)
        } else {
            contentValueToHtml(content, dictionary, "table", exportMedia)
        }
    }

    // =====================================================================================
    // Tags / part-of-speech / conjugation (`anki-template-renderer.js:307-357,435-464`)
    // =====================================================================================

    fun buildTags(term: TermResult?): String {
        if (term == null) return ""
        val seen = linkedSetOf<String>()
        for (g in term.glossaries) {
            g.definitionTags.split(" ").filter { it.isNotBlank() }.forEach { seen.add(it) }
            g.termTags.split(" ").filter { it.isNotBlank() }.forEach { seen.add(it) }
        }
        return seen.joinToString(", ") { escapeHtml(it) }
    }

    private val POS_PRETTY = mapOf(
        "v1" to "Ichidan verb",
        "v5" to "Godan verb",
        "vk" to "Kuru verb",
        "vs" to "Suru verb",
        "vz" to "Zuru verb",
        "adj-i" to "I-adjective",
        "adj-na" to "Na-adjective",
        "n" to "Noun",
        "adv" to "Adverb",
        "prt" to "Particle",
        "exp" to "Expression",
        "conj" to "Conjunction",
        "pref" to "Prefix",
        "suf" to "Suffix",
        "aux-v" to "Auxiliary verb",
    )

    fun buildPartOfSpeech(term: TermResult?): String {
        val rules = term?.rules.orEmpty()
        val seen = linkedSetOf<String>()
        rules.split(Regex("""\s+""")).map { it.trim() }.filter { it.isNotBlank() }
            .forEach { rule -> seen.add(POS_PRETTY[rule.lowercase()] ?: rule) }
        return if (seen.isEmpty()) "Unknown" else seen.joinToString(", ") { escapeHtml(it) }
    }

    fun buildConjugation(ctx: AnkiMarkerContext): String {
        val fromProcess = ctx.process.map { it.name }.filter { it.isNotBlank() }
        if (fromProcess.isNotEmpty()) return fromProcess.joinToString(" « ") { escapeHtml(it) }
        val rules = ctx.term?.rules.orEmpty()
        if (rules.isBlank()) return ""
        return rules.split(" « ").joinToString(" « ") { escapeHtml(it) }
    }

    // =====================================================================================
    // Frequencies (`anki-note-data-creator.js:178-245,352-356`)
    // =====================================================================================

    fun buildFrequenciesList(frequencies: List<FrequencyEntry>, dictionaryKey: String? = null): String {
        if (frequencies.isEmpty()) return ""
        val sb = StringBuilder("<ul data-content=\"frequencies\">")
        var appended = false
        for (group in frequencies) {
            if (dictionaryKey != null && !matchesDynamicDictionaryName(group.dictName, dictionaryKey)) continue
            val dictAttr = attrEscape(group.dictName)
            for (freq in group.frequencies) {
                if (freq.value <= 0 && freq.displayValue.isBlank()) continue
                appended = true
                val display = freq.displayValue.takeIf { it.isNotBlank() } ?: freq.value.toString()
                sb.append("<li data-dictionary=\"$dictAttr\">")
                if (dictionaryKey == null && group.dictName.isNotBlank()) {
                    sb.append("${escapeHtml(group.dictName)}: ")
                }
                sb.append(escapeHtml(display))
                sb.append("</li>")
            }
        }
        sb.append("</ul>")
        return if (appended) sb.toString() else ""
    }

    private fun buildSingleFrequencyNumber(frequencies: List<FrequencyEntry>, dictionaryKey: String): String {
        if (dictionaryKey.isBlank()) return ""
        return frequencies
            .asSequence()
            .filter { group -> matchesDynamicDictionaryName(group.dictName, dictionaryKey) }
            .flatMap { group -> group.frequencies.asSequence() }
            .filter { frequency -> frequency.value > 0 || frequency.displayValue.isNotBlank() }
            .map { frequency -> frequency.displayValue.takeIf { it.isNotBlank() } ?: frequency.value.toString() }
            .joinToString("<br>") { escapeHtml(it) }
    }

    fun selectLowestFrequencyValue(frequencies: List<FrequencyEntry>): String? {
        var bestValue: Int? = null
        var bestDisplayValue: String? = null
        for (group in frequencies) {
            for (frequency in group.frequencies) {
                if (frequency.value <= 0) continue
                if (bestValue == null || frequency.value < bestValue) {
                    bestValue = frequency.value
                    bestDisplayValue = frequency.displayValue.takeIf { it.isNotBlank() }
                }
            }
        }
        return bestDisplayValue ?: bestValue?.toString()
    }

    /** One frequency sample per dictionary (Yomitan takes the first value per dictionary). */
    private fun collectFrequencyNumbers(frequencies: List<FrequencyEntry>): List<Double> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Double>()
        for (group in frequencies) {
            if (!seen.add(group.dictName)) continue
            for (f in group.frequencies) {
                if (f.value > 0) {
                    out.add(f.value.toDouble())
                    break
                }
            }
        }
        return out
    }

    private fun harmonicOf(numbers: List<Double>): Int {
        if (numbers.isEmpty()) return -1
        val total = numbers.sumOf { 1.0 / it }
        if (total <= 0.0) return -1
        return floor(numbers.size / total).toInt()
    }

    private fun averageOf(numbers: List<Double>): Int {
        if (numbers.isEmpty()) return -1
        return floor(numbers.sum() / numbers.size).toInt()
    }

    private fun List<FrequencyEntry>.harmonic(): Int = harmonicOf(collectFrequencyNumbers(this))

    private fun List<FrequencyEntry>.average(): Int = averageOf(collectFrequencyNumbers(this))

    /** `-1`→`9999999` for rank markers (`frequency-harmonic-rank`/`frequency-average-rank`). */
    private fun frequencyRank(frequencies: List<FrequencyEntry>, compute: (List<FrequencyEntry>) -> Int): String {
        val value = compute(frequencies)
        return if (value == -1) "9999999" else value.toString()
    }

    /** `-1`→`0` for occurrence markers (`frequency-harmonic-occurrence`/`frequency-average-occurrence`). */
    private fun frequencyOccurrence(frequencies: List<FrequencyEntry>, compute: (List<FrequencyEntry>) -> Int): String {
        val value = compute(frequencies)
        return if (value == -1) "0" else value.toString()
    }

    private fun matchesDynamicDictionaryName(dictionaryName: String, markerDictionaryKey: String): Boolean {
        val normalizedKey = yomitanKebabCase(markerDictionaryKey)
        return normalizedKey.isNotBlank() &&
            (yomitanKebabCase(dictionaryName) == normalizedKey || dictionaryName.equals(markerDictionaryKey, ignoreCase = true))
    }

    // =====================================================================================
    // Pitch accent (`anki-template-renderer.js:559-578,878-906`)
    // =====================================================================================

    internal enum class PitchFormat { TEXT, GRAPH, GRAPH_JJ, POSITION }

    fun buildPitchAccentPositions(pitches: List<PitchEntry>): String {
        val positions = pitches.flatMap { group -> group.pitchPositions.toList() }
        if (positions.isEmpty()) return ""
        return if (positions.size == 1) {
            renderPitchPosition(positions.first())
        } else {
            positions.joinToString(prefix = "<ol>", postfix = "</ol>", separator = "") { "<li>${renderPitchPosition(it)}</li>" }
        }
    }

    private fun renderPitchPosition(position: Int): String {
        val escaped = escapeHtml(position.toString())
        val attr = attrEscape(position.toString())
        return """<span class="pronunciation-downstep-notation" data-downstep-position="$attr" style="display:inline;"><span class="pronunciation-downstep-notation-prefix">[</span><span class="pronunciation-downstep-notation-number">$escaped</span><span class="pronunciation-downstep-notation-suffix">]</span></span>"""
    }

    internal fun buildPitchAccents(reading: String, pitches: List<PitchEntry>, format: PitchFormat): String {
        if (pitches.isEmpty() || reading.isEmpty()) return ""
        val all = pitches.flatMap { group -> group.pitchPositions.map { group.dictName to it } }
        if (all.isEmpty()) return ""
        val morae = getMorae(reading)
        return if (all.size == 1) {
            renderPitchItem(all[0].first, all[0].second, morae, format)
        } else {
            "<ol>" + all.joinToString("") { "<li>${renderPitchItem(it.first, it.second, morae, format)}</li>" } + "</ol>"
        }
    }

    private fun renderPitchItem(dictName: String, position: Int, morae: List<String>, format: PitchFormat): String {
        val prefix = if (dictName.isNotBlank()) "${escapeHtml(dictName)}: " else ""
        return when (format) {
            PitchFormat.POSITION -> "$prefix$position"
            PitchFormat.TEXT -> "$prefix${renderOverlineText(morae, position)}"
            PitchFormat.GRAPH -> createPitchSvg(morae, position)
            PitchFormat.GRAPH_JJ -> createPitchSvg(morae, position)
        }
    }

    fun buildPitchAccentGraphs(reading: String, pitches: List<PitchEntry>): String {
        if (pitches.isEmpty() || reading.isEmpty()) return ""
        val morae = getMorae(reading)
        if (morae.isEmpty()) return ""
        val sb = StringBuilder("""<div class="pitch-accent-graphs">""")
        for (group in pitches) {
            for (pos in group.pitchPositions) sb.append(createPitchSvg(morae, pos))
        }
        sb.append("</div>")
        return sb.toString()
    }

    fun buildPitchCategories(reading: String, rules: String, pitches: List<PitchEntry>): String {
        val categories = linkedSetOf<String>()
        val moraCount = getMorae(reading).size
        if (moraCount == 0) return ""
        val verbOrAdjective = isVerbOrAdjective(rules)
        for (group in pitches) {
            for (pos in group.pitchPositions) {
                val cat = pitchPositionToCategory(pos, moraCount, verbOrAdjective)
                if (cat.isNotEmpty()) categories.add(cat)
            }
        }
        return categories.joinToString(",")
    }

    private fun pitchPositionToCategory(position: Int, moraCount: Int, verbOrAdjective: Boolean): String = when {
        position == 0 -> "heiban"
        verbOrAdjective && position > 0 -> "kifuku"
        position == 1 -> "atamadaka"
        position == moraCount -> "odaka"
        position > 1 -> "nakadaka"
        else -> ""
    }

    private fun isVerbOrAdjective(rules: String): Boolean =
        rules.split(Regex("""\s+""")).map { it.trim().lowercase() }.any { rule ->
            rule.startsWith("v") || rule.startsWith("adj") || rule == "aux-v"
        }

    fun buildPhoneticTranscriptions(pitches: List<PitchEntry>): String {
        val items = mutableListOf<String>()
        for (group in pitches) {
            for (transcription in group.transcriptions) {
                if (transcription.isBlank()) continue
                items += """<li class="pronunciation" data-pronunciation-type="phonetic-transcription"><span class="ipa">${escapeHtml(transcription)}</span></li>"""
            }
        }
        if (items.isEmpty()) return ""
        return "<ul>${items.joinToString("")}</ul>"
    }

    private fun renderOverlineText(morae: List<String>, p: Int): String {
        val sb = StringBuilder()
        sb.append("""<span class="pronunciation" data-pronunciation-type="pitch-accent" data-pitch-accent-downstep-position="${attrEscape(p.toString())}">""")
        sb.append("""<span class="pronunciation-text" style="display:inline;">""")
        for (i in morae.indices) {
            val mora = morae[i]
            val highPitch = isMoraPitchHigh(i, p)
            val highPitchNext = isMoraPitchHigh(i + 1, p)
            val moraStyle = mutableListOf("display:inline-block", "position:relative")
            if (highPitch && !highPitchNext) {
                moraStyle.add("padding-right:0.1em")
                moraStyle.add("margin-right:0.1em")
            }
            sb.append(
                """<span class="pronunciation-mora" data-position="$i" data-pitch="${if (highPitch) "high" else "low"}" data-pitch-next="${if (highPitchNext) "high" else "low"}" style="${moraStyle.joinToString(";")}">""",
            )
            for (character in mora) {
                sb.append("""<span class="pronunciation-character" style="display:inline;">""")
                sb.append(escapeHtml(character.toString()))
                sb.append("</span>")
            }
            val lineStyle = mutableListOf("border-color:currentColor")
            if (highPitch) {
                lineStyle.add("display:block")
                lineStyle.add("user-select:none")
                lineStyle.add("pointer-events:none")
                lineStyle.add("position:absolute")
                lineStyle.add("top:0.1em")
                lineStyle.add("left:0")
                lineStyle.add("right:0")
                lineStyle.add("height:0")
                lineStyle.add("border-top-width:0.1em")
                lineStyle.add("border-top-style:solid")
            } else {
                lineStyle.add("display:none")
            }
            if (highPitch && !highPitchNext) {
                lineStyle.add("right:-0.1em")
                lineStyle.add("height:0.4em")
                lineStyle.add("border-right-width:0.1em")
                lineStyle.add("border-right-style:solid")
            }
            sb.append("""<span style="${lineStyle.joinToString(";")}"></span>""")
            sb.append("</span>")
        }
        sb.append("</span>")
        sb.append("</span>")
        return sb.toString()
    }

    private fun isMoraPitchHigh(moraIndex: Int, pitchAccentValue: Int): Boolean = when (pitchAccentValue) {
        0 -> moraIndex > 0
        1 -> moraIndex < 1
        else -> moraIndex > 0 && moraIndex < pitchAccentValue
    }

    fun createPitchSvg(morae: List<String>, p: Int): String {
        val n = morae.size
        val stepWidth = 35
        val marginLr = 16
        val height = 45
        val svgWidth = ((n) * stepWidth) + (marginLr * 2)
        val points = mutableListOf<Point>()
        for (i in 0..n) {
            val isHigh = when {
                p == 1 -> i == 0
                p == 0 -> i > 0
                p >= 2 -> i in 1 until p
                else -> false
            }
            points.add(Point(marginLr + (i * stepWidth), if (isHigh) 10 else 35))
        }
        val svg = StringBuilder()
        val displayWidth = (svgWidth * 0.8).toInt()
        svg.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${displayWidth}px" height="40px" viewBox="0 0 $svgWidth $height" style="display: inline-block; vertical-align: middle;">""")
        if (points.size > 1) {
            val d = StringBuilder("M ${points[0].x} ${points[0].y}")
            for (i in 1 until points.size) d.append(" L ${points[i].x} ${points[i].y}")
            svg.append("""<path d="$d" fill="none" stroke="currentColor" stroke-width="2" />""")
        }
        for (i in points.indices) {
            val pt = points[i]
            val isTail = i >= n
            if (isTail) {
                svg.append("""<circle cx="${pt.x}" cy="${pt.y}" r="4" fill="none" stroke="currentColor" stroke-width="2" />""")
            } else {
                svg.append("""<circle cx="${pt.x}" cy="${pt.y}" r="5" fill="currentColor" />""")
            }
        }
        svg.append("</svg>")
        return svg.toString()
    }

    private data class Point(val x: Int, val y: Int)

    fun buildMorae(reading: String): String = getMorae(reading).joinToString(" ")

    fun getMorae(text: String): List<String> {
        val morae = mutableListOf<String>()
        val smallKana = setOf(
            'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ゃ', 'ゅ', 'ょ', 'ゎ',
            'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヵ', 'ヶ',
        )
        for (char in text) {
            if (morae.isNotEmpty() && char in smallKana) {
                morae[morae.size - 1] = morae.last() + char
            } else {
                morae.add(char.toString())
            }
        }
        return morae
    }

    // =====================================================================================
    // Kanji
    // =====================================================================================

    private fun buildStrokeCount(kanji: KanjiResult?): String {
        val value = kanji?.entries?.asSequence()
            ?.mapNotNull { entry -> entry.stats.entries.firstOrNull { it.key.contains("stroke", ignoreCase = true) }?.value }
            ?.firstOrNull { it.isNotBlank() }
        return if (value != null) "Stroke count: ${escapeHtml(value)}" else "Stroke count: Unknown"
    }

    /** Yomitan stores readings as a list; our model keeps one comma-joined string (`kunyomi`). */
    private fun splitReadings(value: String): String = value
        .split(",", "、")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(", ")

    // =====================================================================================
    // HTML helpers
    // =====================================================================================

    fun escapeHtml(text: String): String = text
        .replace("\u001F", "")
        .replace("\u0000", "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun attrEscape(text: String): String = text
        .replace("\u001F", "")
        .replace("\u0000", "")
        .replace("&", "&amp;")
        .replace("\"", "&quot;")

    private fun cssDoubleQuotedContent(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\A ")
        .replace("\r", "")

    private fun structuredDataAttributeName(key: String): String {
        val kebab = key.replace(Regex("[A-Z]")) { match ->
            val lower = match.value.lowercase()
            if (match.range.first == 0) lower else "-$lower"
        }
        val prefix = if (kebab.firstOrNull()?.isStructuredDataCjkStart() == true) "data-sc" else "data-sc-"
        return "$prefix$kebab"
    }

    private fun Char.isStructuredDataCjkStart(): Boolean =
        this in '\u3000'..'\u9FFF' || this in '\uF900'..'\uFAFF'

    private fun structuredStyleValue(prop: String, value: JsonElement): String {
        if (value is JsonArray) return value.joinToString(" ") { it.toString().trim('"') }
        val primitive = value as? JsonPrimitive ?: return value.toString()
        if (!primitive.isString) {
            val content = primitive.content
            return if (prop.startsWith("margin")) "${content}em" else content
        }
        return primitive.content
    }

    private fun camelToKebab(name: String): String = name.replace(Regex("([A-Z])")) { "-${it.value.lowercase()}" }

    fun yomitanKebabCase(name: String): String = name.trim()
        .replace(Regex("""[\s_\u3000]+"""), "-")
        .replace(Regex("""[^\p{L}\p{N}-]+"""), "")
        .replace(Regex("""--+"""), "-")
        .trim('-')
        .lowercase()

    fun normalizePlainText(text: String): String = text
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\\n", "\n")

    fun escapeHtmlWithLineBreaks(text: String): String = escapeHtml(normalizePlainText(text)).replace("\n", "<br>")

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.let {
        it.contentOrNull?.toBooleanStrictOrNull() ?: false
    } ?: false
}
// KUN <--
