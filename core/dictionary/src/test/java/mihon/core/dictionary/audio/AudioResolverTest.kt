// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AudioResolverTest {

    private class FakeHttp(
        private val responses: Map<String, AudioHttpResponse> = emptyMap(),
        private val default: AudioHttpResponse = AudioHttpResponse(ok = false, status = 404, bodyText = ""),
    ) : AudioHttp {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): AudioHttpResponse {
            requested += url
            return responses[url] ?: default
        }
    }

    private fun ok(body: String) = AudioHttpResponse(ok = true, status = 200, bodyText = body)

    private fun audioSourceList(vararg entries: Pair<String?, String>): String {
        val items = entries.joinToString(",") { (name, url) ->
            val namePart = name?.let { "\"name\":\"$it\"," } ?: ""
            "{$namePart\"url\":\"$url\"}"
        }
        return """{"type":"audioSourceList","audioSources":[$items]}"""
    }

    // --- custom -------------------------------------------------------------------------------

    @Test
    fun `custom expands placeholders into a single candidate`() = runBlocking {
        val resolver = AudioResolver(FakeHttp())
        val result = resolver.candidatesFor(
            AudioSourceConfig(AudioSourceTypes.CUSTOM, url = "http://host/a?t={term}&r={reading}"),
            term = "猫",
            reading = "ねこ",
            languageIso = "ja",
        )
        result shouldContainExactly listOf(
            AudioCandidate(url = "http://host/a?t=猫&r=ねこ", sourceType = AudioSourceTypes.CUSTOM),
        )
    }

    @Test
    fun `custom with a blank url yields nothing`() = runBlocking {
        AudioResolver(FakeHttp()).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.CUSTOM),
            "a",
            "b",
            "ja",
        ) shouldBe emptyList()
    }

    // --- custom-json (Local Audio Server / AnkiConnect-Android) --------------------------------

    @Test
    fun `custom-json parses audioSourceList in order`() = runBlocking {
        val url = "http://127.0.0.1:8765/localaudio/get/?term={term}&reading={reading}"
        val expanded = "http://127.0.0.1:8765/localaudio/get/?term=読む&reading=よむ"
        val http = FakeHttp(
            responses = mapOf(
                expanded to ok(audioSourceList("NHK16 (male)" to "http://127.0.0.1:8765/localaudio/nhk16/a.mp3", null to "http://b.mp3")),
            ),
        )
        val result = AudioResolver(http).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, url = url),
            term = "読む",
            reading = "よむ",
            languageIso = "ja",
        )
        http.requested shouldContainExactly listOf(expanded)
        result shouldContainExactly listOf(
            AudioCandidate("http://127.0.0.1:8765/localaudio/nhk16/a.mp3", "NHK16 (male)", AudioSourceTypes.CUSTOM_JSON),
            AudioCandidate("http://b.mp3", null, AudioSourceTypes.CUSTOM_JSON),
        )
    }

    @Test
    fun `custom-json rejects a wrong envelope and non-ok responses`() = runBlocking {
        val resolver = AudioResolver(
            FakeHttp(
                responses = mapOf(
                    "http://x/" to ok("""{"type":"other","audioSources":[]}"""),
                ),
            ),
        )
        resolver.candidatesFor(AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, "http://x/"), "a", "b", "ja") shouldBe emptyList()

        val failing = AudioResolver(FakeHttp(default = AudioHttpResponse(false, 500, "")))
        failing.candidatesFor(AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, "http://y/"), "a", "b", "ja") shouldBe emptyList()
    }

    // --- jpod101 -------------------------------------------------------------------------------

    @Test
    fun `jpod101 builds kanji and kana params`() = runBlocking {
        val result = AudioResolver(FakeHttp()).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.JPOD_101),
            term = "読む",
            reading = "よむ",
            languageIso = "ja",
        )
        result.single().url shouldBe
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%E8%AA%AD%E3%82%80&kana=%E3%82%88%E3%82%80"
    }

    @Test
    fun `jpod101 moves an entirely-kana term to the kana param`() = runBlocking {
        val result = AudioResolver(FakeHttp()).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.JPOD_101),
            term = "よむ",
            reading = "よむ",
            languageIso = "ja",
        )
        result.single().url shouldBe
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kana=%E3%82%88%E3%82%80"
    }

    // --- jisho ---------------------------------------------------------------------------------

    @Test
    fun `jisho scrapes the audio element source`() = runBlocking {
        val html = """
            <div class="concept_light">
              <audio id="audio_読む:よむ"><source src="https://d1vjc5dkua4yhp.cloudfront.net/audio/読む.mp3" type="audio/mpeg"></audio>
            </div>
        """.trimIndent()
        val http = FakeHttp(responses = mapOf("https://jisho.org/search/%E8%AA%AD%E3%82%80" to ok(html)))
        val result = AudioResolver(http).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.JISHO),
            term = "読む",
            reading = "よむ",
            languageIso = "ja",
        )
        result.single().url shouldBe "https://d1vjc5dkua4yhp.cloudfront.net/audio/読む.mp3"
    }

    @Test
    fun `jisho returns nothing when the element is missing`() = runBlocking {
        val http = FakeHttp(responses = mapOf("https://jisho.org/search/a" to ok("<html></html>")))
        AudioResolver(http).candidatesFor(AudioSourceConfig(AudioSourceTypes.JISHO), "a", "a", "ja") shouldBe emptyList()
    }

    // --- ordering, defaults and dedupe ---------------------------------------------------------

    @Test
    fun `resolveAll keeps source order appends defaults and dedupes urls`() = runBlocking {
        val localUrl = "http://127.0.0.1:5050/?term={term}&reading={reading}"
        val http = FakeHttp(
            responses = mapOf(
                "http://127.0.0.1:5050/?term=猫&reading=ねこ" to
                    ok(audioSourceList("Local A" to "http://local/a.mp3", "Local B" to "http://local/b.mp3")),
            ),
        )
        val result = AudioResolver(http).resolveAll(
            sources = listOf(
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, localUrl),
                // Same URL as the custom-json's first entry -> must be deduped away.
                AudioSourceConfig(AudioSourceTypes.CUSTOM, "http://local/a.mp3"),
            ),
            term = "猫",
            reading = "ねこ",
            languageIso = "ja",
            enableDefaultSources = true,
        )
        // Local entries first, then the ja defaults (jpod101 before jisho), one URL each.
        result.map { it.url } shouldContainExactly listOf(
            "http://local/a.mp3",
            "http://local/b.mp3",
            "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%E7%8C%AB&kana=%E3%81%AD%E3%81%93",
        )
    }

    @Test
    fun `defaults are omitted when disabled and when the type is configured`() = runBlocking {
        val resolver = AudioResolver(FakeHttp())
        resolver.resolveAll(
            sources = listOf(AudioSourceConfig(AudioSourceTypes.CUSTOM, "http://no-placeholder")),
            term = "a",
            reading = "b",
            languageIso = "ja",
            enableDefaultSources = false,
        ).map { it.url } shouldBe listOf("http://no-placeholder")

        // jpod101 is already configured, so the default jpod101 is not added twice.
        resolver.resolveAll(
            sources = listOf(AudioSourceConfig(AudioSourceTypes.JPOD_101)),
            term = "a",
            reading = "b",
            languageIso = "ja",
            enableDefaultSources = true,
        ).count { it.sourceType == AudioSourceTypes.JPOD_101 } shouldBe 1
    }

    @Test
    fun `unresolved source types yield no candidates`() = runBlocking {
        val resolver = AudioResolver(FakeHttp())
        val types = listOf(
            AudioSourceTypes.LANGUAGE_POD_101,
            AudioSourceTypes.LINGUA_LIBRE,
            AudioSourceTypes.WIKTIONARY,
            AudioSourceTypes.TEXT_TO_SPEECH,
            AudioSourceTypes.TEXT_TO_SPEECH_READING,
        )
        for (type in types) {
            resolver.candidatesFor(AudioSourceConfig(type), "a", "b", "ja") shouldBe emptyList()
        }
    }

    // --- picker metadata (source identity + order) --------------------------------------------

    @Test
    fun `resolve tags candidates with their source index and label in configured order`() = runBlocking {
        val http = FakeHttp(
            responses = mapOf(
                // Two distinct custom-json sources, each yielding one sample, plus a custom URL.
                "http://first/?term=猫&reading=ねこ" to ok(audioSourceList("First A" to "http://first/a.mp3")),
                "http://second/?term=猫&reading=ねこ" to ok(audioSourceList("Second A" to "http://second/a.mp3")),
            ),
        )
        val resolution = AudioResolver(http).resolve(
            sources = listOf(
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, "http://first/?term={term}&reading={reading}"),
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, "http://second/?term={term}&reading={reading}"),
                AudioSourceConfig(AudioSourceTypes.CUSTOM, "http://third/a.mp3"),
            ),
            term = "猫",
            reading = "ねこ",
            languageIso = "ja",
            enableDefaultSources = false,
        )

        // The picker lists sources in the exact configured order.
        resolution.sources.map { it.url } shouldContainExactly listOf(
            "http://first/?term={term}&reading={reading}",
            "http://second/?term={term}&reading={reading}",
            "http://third/a.mp3",
        )
        // Candidates carry their origin so the menu can group samples under the right source.
        resolution.candidates.map { it.sourceIndex } shouldContainExactly listOf(0, 1, 2)
        resolution.candidates.map { it.url } shouldContainExactly listOf(
            "http://first/a.mp3",
            "http://second/a.mp3",
            "http://third/a.mp3",
        )
        resolution.candidates.map { it.sourceLabel } shouldContainExactly listOf(
            "Custom URL (JSON)",
            "Custom URL (JSON)",
            "Custom URL",
        )
    }

    @Test
    fun `resolve keeps multiple samples of one source grouped and in order`() = runBlocking {
        val http = FakeHttp(
            responses = mapOf(
                "http://local/?t=猫&r=ねこ" to
                    ok(audioSourceList("Sample One" to "http://local/1.mp3", "Sample Two" to "http://local/2.mp3")),
            ),
        )
        val resolution = AudioResolver(http).resolve(
            sources = listOf(
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, "http://local/?t={term}&r={reading}"),
            ),
            term = "猫",
            reading = "ねこ",
            languageIso = "ja",
            enableDefaultSources = false,
        )

        resolution.sources.size shouldBe 1
        resolution.candidates.map { it.sourceIndex } shouldContainExactly listOf(0, 0)
        resolution.candidates.map { it.name } shouldContainExactly listOf("Sample One", "Sample Two")
    }

    @Test
    fun `effectiveSources preserves configured order and only appends unconfigured defaults`() {
        val resolver = AudioResolver(FakeHttp())
        val configured = listOf(
            AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, "http://local/"),
            AudioSourceConfig(AudioSourceTypes.JISHO),
            AudioSourceConfig(AudioSourceTypes.CUSTOM, "http://x/"),
        )
        val effective = resolver.effectiveSources(configured, languageIso = "ja", enableDefaultSources = true)

        // Configured order first; jpod101 and jisho are already configured, so only
        // language-pod-101 is appended from the ja defaults.
        effective.map { it.type } shouldContainExactly listOf(
            AudioSourceTypes.CUSTOM_JSON,
            AudioSourceTypes.JISHO,
            AudioSourceTypes.CUSTOM,
            AudioSourceTypes.JPOD_101,
            AudioSourceTypes.LANGUAGE_POD_101,
        )
    }
}
// KUN <--
