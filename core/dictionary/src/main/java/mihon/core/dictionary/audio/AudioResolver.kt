// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Resolves Yomitan's `audio.sources[]` into playable candidate URLs, reproducing the behaviour of
// `ext/js/media/audio-downloader.js`:
//
//  * `custom`       — `_getInfoCustom`: template substitution, yields one URL.
//  * `custom-json`  — `_getInfoCustomJson`: GET the templated URL and read the
//                     `{"type":"audioSourceList","audioSources":[{"name","url"}]}` payload (this is
//                     what the Local Audio Server / AnkiConnect-for-Android return).
//  * `jpod101`      — `_getInfoJpod101`: kanji/kana query against
//                     assets.languagepod101.com (kana-only terms move to `kana`).
//  * `jisho`        — `_getInfoJisho`: scrape `#audio_{term}:{reading}` from jisho.org.
//
//  * `language-pod-101` — POST form query against www.{lang}{pod|class}101.com.
//  * `lingua-libre`     — Wikimedia Commons search query in category Lingua_Libre_pronunciation-{iso639-3}.
//  * `wiktionary`        — Wikimedia Commons search query for Wiktionary pronunciation recordings.
//  * `text-to-speech*`   — Synthetic TTS URLs for native speech playback.
package mihon.core.dictionary.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap

class AudioResolver(
    private val http: AudioHttp,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val resolutionCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, AudioResolution>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioResolution>?): Boolean {
                return size > 256
            }
        },
    )

    /**
     * Candidate URLs for [sources], in priority order. When [enableDefaultSources] is set, the
     * language's implicit defaults are appended (unless the same *type* is already configured),
     * matching Yomitan's `getRequiredAudioSources`. Duplicate URLs keep their first occurrence so a
     * repeated source never plays twice.
     */
    suspend fun resolveAll(
        sources: List<AudioSourceConfig>,
        term: String,
        reading: String,
        languageIso: String,
        enableDefaultSources: Boolean,
    ): List<AudioCandidate> = resolve(sources, term, reading, languageIso, enableDefaultSources).candidates

    /**
     * Like [resolveAll] but also returns the ordered effective source list (configured sources,
     * then appended defaults), so the audio picker can list every source — including the ones that
     * produced no candidate — in the user's configured order.
     */
    suspend fun resolve(
        sources: List<AudioSourceConfig>,
        term: String,
        reading: String,
        languageIso: String,
        enableDefaultSources: Boolean,
    ): AudioResolution {
        val cacheKey = "$languageIso\n$term\n$reading\n${sources.hashCode()}\n$enableDefaultSources"
        resolutionCache[cacheKey]?.let { return it }

        val effective = effectiveSources(sources, languageIso, enableDefaultSources)

        val seen = HashSet<String>()
        val out = ArrayList<AudioCandidate>()

        val candidateLists = coroutineScope {
            effective.map { source ->
                async { candidatesFor(source, term, reading, languageIso) }
            }.awaitAll()
        }

        candidateLists.forEachIndexed { index, candidates ->
            val source = effective[index]
            val label = AudioSourceTypes.displayName(source.type)
            for (candidate in candidates) {
                if (candidate.url.isBlank()) continue
                if (seen.add(candidate.url)) {
                    out += candidate.copy(sourceIndex = index, sourceLabel = label)
                }
            }
        }
        val resolution = AudioResolution(sources = effective, candidates = out)
        resolutionCache[cacheKey] = resolution
        return resolution
    }

    /**
     * The ordered source list actually resolved: the configured `audio.sources[]` verbatim
     * (order preserved), followed by the language's implicit defaults only when
     * [enableDefaultSources] is on and the type is not already configured.
     */
    fun effectiveSources(
        sources: List<AudioSourceConfig>,
        languageIso: String,
        enableDefaultSources: Boolean,
    ): List<AudioSourceConfig> {
        val effective = ArrayList<AudioSourceConfig>(sources)
        if (enableDefaultSources) {
            val configuredTypes = sources.mapTo(HashSet()) { it.type }
            AudioSourceTypes.defaultsFor(languageIso)
                .filterNot { it in configuredTypes }
                .forEach { effective += AudioSourceConfig(type = it) }
        }
        return effective
    }

    /** Candidates for a single source. Network errors yield an empty list, never a throw. */
    suspend fun candidatesFor(
        source: AudioSourceConfig,
        term: String,
        reading: String,
        languageIso: String,
    ): List<AudioCandidate> = try {
        when (source.type) {
            AudioSourceTypes.CUSTOM -> custom(source, term, reading, languageIso)
            AudioSourceTypes.CUSTOM_JSON -> customJson(source, term, reading, languageIso)
            AudioSourceTypes.JPOD_101 -> jpod101(term, reading)
            AudioSourceTypes.JISHO -> jisho(term, reading)
            AudioSourceTypes.LANGUAGE_POD_101 -> languagePod101(term, reading, languageIso)
            AudioSourceTypes.LINGUA_LIBRE -> linguaLibre(term, languageIso)
            AudioSourceTypes.WIKTIONARY -> wiktionary(term, languageIso)
            AudioSourceTypes.TEXT_TO_SPEECH,
            AudioSourceTypes.TEXT_TO_SPEECH_READING,
            -> textToSpeech(source, term, reading, languageIso)
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ---------------------------------------------------------------------------------------------

    private fun custom(
        source: AudioSourceConfig,
        term: String,
        reading: String,
        languageIso: String,
    ): List<AudioCandidate> {
        if (source.url.isBlank()) return emptyList()
        val url = AudioUrlTemplate.expand(source.url, term, reading, languageIso)
        return listOf(AudioCandidate(url = url, sourceType = source.type))
    }

    private suspend fun customJson(
        source: AudioSourceConfig,
        term: String,
        reading: String,
        languageIso: String,
    ): List<AudioCandidate> {
        if (source.url.isBlank()) return emptyList()
        val requestUrl = AudioUrlTemplate.expand(source.url, term, reading, languageIso)
        val response = http.get(requestUrl)
        if (!response.ok) return emptyList()
        return parseAudioSourceList(response.bodyText, source.type)
    }

    private fun parseAudioSourceList(body: String, sourceType: String): List<AudioCandidate> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        if (root["type"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "audioSourceList") return emptyList()
        val sources = root["audioSources"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return sources.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (url.isBlank()) return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
            AudioCandidate(url = url, name = name, sourceType = sourceType)
        }
    }

    private fun jpod101(term: String, reading: String): List<AudioCandidate> {
        var effectiveTerm = term
        var effectiveReading = reading
        if (reading == term && isStringEntirelyKana(term)) {
            effectiveReading = term
            effectiveTerm = ""
        }
        val params = buildList {
            if (effectiveTerm.isNotEmpty()) add("kanji=" + urlEncode(effectiveTerm))
            if (effectiveReading.isNotEmpty()) add("kana=" + urlEncode(effectiveReading))
        }
        if (params.isEmpty()) return emptyList()
        val url = "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?" + params.joinToString("&")
        return listOf(AudioCandidate(url = url, sourceType = AudioSourceTypes.JPOD_101))
    }

    private suspend fun jisho(term: String, reading: String): List<AudioCandidate> {
        if (term.isBlank()) return emptyList()
        val response = http.get("https://jisho.org/search/" + urlEncode(term))
        if (!response.ok) return emptyList()
        val url = parseJishoAudioUrl(response.bodyText, term, reading) ?: return emptyList()
        return listOf(AudioCandidate(url = url, sourceType = AudioSourceTypes.JISHO))
    }

    private suspend fun languagePod101(
        term: String,
        reading: String,
        languageIso: String,
    ): List<AudioCandidate> {
        val language = LANGUAGE_POD_101_LANGUAGES[languageIso.lowercase().take(2)]
            ?: LANGUAGE_POD_101_LANGUAGES[languageIso.lowercase()]
            ?: return emptyList()
        val podOrClass = if (language in CLASS_LANGUAGES) "class" else "pod"
        val origin = "https://www.${language.lowercase()}${podOrClass}101.com"
        val fetchUrl = "$origin/learningcenter/reference/dictionary_post"
        val formFields = mapOf(
            "post" to "dictionary_reference",
            "match_type" to "exact",
            "search_query" to term,
            "vulgar" to "true",
        )
        val response = http.postForm(fetchUrl, formFields)
        if (!response.ok) return emptyList()
        return parseLanguagePod101Html(response.bodyText, language, term, reading, origin)
    }

    private suspend fun linguaLibre(term: String, languageIso: String): List<AudioCandidate> {
        val iso6393 = toIso6393(languageIso) ?: return emptyList()
        val searchParam = "intitle:/-$term.wav/i incategory:\"Lingua_Libre_pronunciation-$iso6393\""
        val fetchUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search&srsearch=" +
            urlEncode(searchParam) + "&srnamespace=6&origin=*"
        val response = http.get(fetchUrl)
        if (!response.ok) return emptyList()

        val titles = parseWikimediaSearchTitles(json, response.bodyText)
        if (titles.isEmpty()) return emptyList()

        val results = mutableListOf<AudioCandidate>()
        for (title in titles) {
            val fileInfoUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&titles=" +
                urlEncode(title) + "&prop=imageinfo&iiprop=user|url&origin=*"
            val infoResponse = http.get(fileInfoUrl)
            if (!infoResponse.ok) continue
            val entries = parseWikimediaImageInfo(json, infoResponse.bodyText)
            for ((fileUrl, fileUser) in entries) {
                val isValid = title.startsWith("File:LL-Q", ignoreCase = true) &&
                    title.endsWith("($iso6393)-$fileUser-$term.wav", ignoreCase = true)
                if (isValid) {
                    results += AudioCandidate(
                        url = fileUrl,
                        name = fileUser.ifBlank { null },
                        sourceType = AudioSourceTypes.LINGUA_LIBRE,
                    )
                }
            }
        }
        return results
    }

    private suspend fun wiktionary(term: String, languageIso: String): List<AudioCandidate> {
        val iso = languageIso.lowercase().trim()
        val searchParam = "intitle:/$iso(-[a-zA-Z]{2})?-$term[0123456789]*.ogg/i"
        val fetchUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search&srsearch=" +
            urlEncode(searchParam) + "&srnamespace=6&origin=*"
        val response = http.get(fetchUrl)
        if (!response.ok) return emptyList()

        val titles = parseWikimediaSearchTitles(json, response.bodyText)
        if (titles.isEmpty()) return emptyList()

        val results = mutableListOf<AudioCandidate>()
        for (title in titles) {
            val fileInfoUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&titles=" +
                urlEncode(title) + "&prop=imageinfo&iiprop=user|url&origin=*"
            val infoResponse = http.get(fileInfoUrl)
            if (!infoResponse.ok) continue
            val entries = parseWikimediaImageInfo(json, infoResponse.bodyText)
            for ((fileUrl, fileUser) in entries) {
                val region = validateWiktionaryFilename(title, iso, term) ?: continue
                val displayName = if (region.isNotEmpty()) "($region) $fileUser" else fileUser
                results += AudioCandidate(
                    url = fileUrl,
                    name = displayName.ifBlank { null },
                    sourceType = AudioSourceTypes.WIKTIONARY,
                )
            }
        }
        return results
    }

    private fun textToSpeech(
        source: AudioSourceConfig,
        term: String,
        reading: String,
        languageIso: String,
    ): List<AudioCandidate> {
        val textToSpeak = when (source.type) {
            AudioSourceTypes.TEXT_TO_SPEECH -> term
            AudioSourceTypes.TEXT_TO_SPEECH_READING -> reading.ifBlank { term }
            else -> return emptyList()
        }.trim()
        if (textToSpeak.isBlank()) return emptyList()
        val url = "tts://speak?text=" + urlEncode(textToSpeak) +
            "&lang=" + urlEncode(languageIso) +
            "&voice=" + urlEncode(source.voice)
        val name = source.voice.ifBlank { null }
        return listOf(AudioCandidate(url = url, name = name, sourceType = source.type))
    }

    companion object {
        private val SOURCE_TAG = Regex("<source[^>]*\\bsrc=[\"']([^\"']+)[\"']")
        private val DC_RESULT_ROW_SPLIT = Regex("<div[^>]*class=[\"'][^\"']*\\bdc-result-row\\b[^\"']*[\"']")
        private val VOCAB_KANA_REGEX = Regex("<[^>]*class=[\"'][^\"']*\\bdc-vocab_kana\\b[^\"']*[\"'][^>]*>([^<]*)<")
        private val VOCAB_REGEX = Regex("<[^>]*class=[\"'][^\"']*\\bdc-vocab\\b[^\"']*[\"'][^>]*>([^<]*)<")

        val LANGUAGE_POD_101_LANGUAGES = mapOf(
            "ja" to "Japanese",
            "en" to "English",
            "zh" to "Chinese",
            "yue" to "Cantonese",
            "ko" to "Korean",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "ru" to "Russian",
            "pt" to "Portuguese",
            "ar" to "Arabic",
            "nl" to "Dutch",
            "pl" to "Polish",
            "sv" to "Swedish",
            "th" to "Thai",
            "vi" to "Vietnamese",
            "id" to "Indonesian",
            "hi" to "Hindi",
            "tr" to "Turkish",
            "cs" to "Czech",
            "da" to "Danish",
            "no" to "Norwegian",
            "fi" to "Finnish",
            "el" to "Greek",
            "he" to "Hebrew",
            "hu" to "Hungarian",
            "fa" to "Persian",
            "ro" to "Romanian",
            "sw" to "Swahili",
            "ur" to "Urdu",
            "af" to "Afrikaans",
            "bg" to "Bulgarian",
            "tl" to "Filipino",
        )

        val CLASS_LANGUAGES = setOf(
            "Cantonese",
            "Chinese",
            "Czech",
            "Danish",
            "English",
            "Korean",
            "Norwegian",
            "Turkish",
        )

        val ISO_639_3_MAP = mapOf(
            "ja" to "jpn",
            "en" to "eng",
            "zh" to "cmn",
            "ko" to "kor",
            "es" to "spa",
            "fr" to "fra",
            "de" to "deu",
            "it" to "ita",
            "ru" to "rus",
            "pt" to "por",
            "nl" to "nld",
            "pl" to "pol",
            "sv" to "swe",
            "vi" to "vie",
            "id" to "ind",
            "tr" to "tur",
            "ar" to "ara",
        )

        fun toIso6393(iso: String): String? {
            val lower = iso.lowercase().trim()
            return ISO_639_3_MAP[lower] ?: if (lower.length == 3) lower else null
        }

        fun parseLanguagePod101Html(
            html: String,
            language: String,
            term: String,
            reading: String,
            origin: String,
        ): List<AudioCandidate> {
            val rows = html.split(DC_RESULT_ROW_SPLIT).drop(1)
            val seen = HashSet<String>()
            val candidates = mutableListOf<AudioCandidate>()
            for (row in rows) {
                val isValid = if (language == "Japanese") {
                    val match = VOCAB_KANA_REGEX.find(row) ?: continue
                    val htmlReading = unescapeHtml(match.groupValues[1].trim())
                    reading == term || reading == htmlReading
                } else {
                    val match = VOCAB_REGEX.find(row) ?: continue
                    val vocabText = unescapeHtml(match.groupValues[1].trim())
                    term == vocabText
                }
                if (!isValid) continue

                val sourceMatch = SOURCE_TAG.find(row) ?: continue
                val rawUrl = unescapeHtml(sourceMatch.groupValues[1].trim())
                if (rawUrl.isBlank()) continue
                val normalized = normalizeUrl(rawUrl, origin)
                if (seen.add(normalized)) {
                    candidates += AudioCandidate(url = normalized, sourceType = AudioSourceTypes.LANGUAGE_POD_101)
                }
            }
            return candidates
        }

        fun normalizeUrl(url: String, origin: String): String = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> origin + url
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "$origin/$url"
        }

        fun parseWikimediaSearchTitles(json: Json, body: String): List<String> {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val query = root["query"] as? JsonObject ?: return emptyList()
            val search = query["search"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            return search.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                (obj["title"] as? JsonPrimitive)?.contentOrNull
            }
        }

        fun parseWikimediaImageInfo(json: Json, body: String): List<Pair<String, String>> {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val query = root["query"] as? JsonObject ?: return emptyList()
            val pages = query["pages"] as? JsonObject ?: return emptyList()
            val out = mutableListOf<Pair<String, String>>()
            for ((_, pageElement) in pages) {
                val pageObj = pageElement as? JsonObject ?: continue
                val imageInfoArray = pageObj["imageinfo"] as? kotlinx.serialization.json.JsonArray ?: continue
                val firstInfo = imageInfoArray.firstOrNull() as? JsonObject ?: continue
                val url = (firstInfo["url"] as? JsonPrimitive)?.contentOrNull
                val user = (firstInfo["user"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (!url.isNullOrBlank()) {
                    out += Pair(url, user)
                }
            }
            return out
        }

        fun validateWiktionaryFilename(
            filename: String,
            iso: String,
            term: String,
        ): String? {
            if (!filename.startsWith("File:$iso", ignoreCase = true)) return null
            if (!filename.endsWith(".ogg", ignoreCase = true)) return null
            var rest = filename.substring("File:$iso".length)
            var region = ""
            if (rest.startsWith("-")) {
                if (rest.length >= 4 && rest[3] == '-' && rest[1].isLetter() && rest[2].isLetter()) {
                    region = rest.substring(1, 3).uppercase()
                    rest = rest.substring(3)
                }
            }
            if (!rest.startsWith("-$term", ignoreCase = true)) return null
            val digitsPart = rest.substring("-$term".length).dropLast(4)
            if (!digitsPart.all { it.isDigit() }) return null
            return region
        }

        /** `DisplayAudio`-equivalent: find the `audio_{term}:{reading}` element's first `<source>`. */
        fun parseJishoAudioUrl(html: String, term: String, reading: String): String? {
            val index = html.indexOf("id=\"audio_$term:$reading\"")
            if (index < 0) return null
            val match = SOURCE_TAG.find(html, index) ?: return null
            return unescapeHtml(match.groupValues[1])
        }

        private fun unescapeHtml(value: String): String = value
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")

        /** Yomitan's `isStringEntirelyKana` (audio-downloader.js) — hiragana/katakana block. */
        fun isStringEntirelyKana(value: String): Boolean {
            if (value.isEmpty()) return false
            return value.all { c ->
                c.code in 0x3040..0x309F || // Hiragana
                    c.code in 0x30A0..0x30FF || // Katakana
                    c.code == 0x30FC || // Katakana-Hiragana prolonged sound mark
                    c.code in 0xFF66..0xFF9D // Halfwidth katakana
            }
        }

        fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
// KUN <--
