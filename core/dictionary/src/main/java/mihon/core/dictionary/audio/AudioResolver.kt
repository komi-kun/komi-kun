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
// The remaining types (`language-pod-101`, `lingua-libre`, `wiktionary`, `text-to-speech*`) are
// recognised models but have no resolver here yet, so they simply yield no candidates.
package mihon.core.dictionary.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

class AudioResolver(
    private val http: AudioHttp,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

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
        val effective = effectiveSources(sources, languageIso, enableDefaultSources)

        val seen = HashSet<String>()
        val out = ArrayList<AudioCandidate>()
        effective.forEachIndexed { index, source ->
            val label = AudioSourceTypes.displayName(source.type)
            for (candidate in candidatesFor(source, term, reading, languageIso)) {
                if (candidate.url.isBlank()) continue
                if (seen.add(candidate.url)) {
                    out += candidate.copy(sourceIndex = index, sourceLabel = label)
                }
            }
        }
        return AudioResolution(sources = effective, candidates = out)
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

    companion object {
        private val SOURCE_TAG = Regex("<source[^>]*\\bsrc=\"([^\"]+)\"")

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

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
// KUN <--
