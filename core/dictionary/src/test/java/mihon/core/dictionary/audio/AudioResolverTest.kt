// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AudioResolverTest {

    private class FakeHttp(
        private val responses: Map<String, AudioHttpResponse> = emptyMap(),
        private val postResponses: Map<String, AudioHttpResponse> = emptyMap(),
        private val default: AudioHttpResponse = AudioHttpResponse(ok = false, status = 404, bodyText = ""),
        // KUN --> a response can be held open so a streaming test can observe the partial result.
        private val gates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        // KUN <--
    ) : AudioHttp {
        val requested = mutableListOf<String>()
        val postedForms = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String): AudioHttpResponse {
            requested += url
            gates[url]?.await()
            return responses[url] ?: default
        }

        override suspend fun postForm(url: String, formFields: Map<String, String>): AudioHttpResponse {
            postedForms += url to formFields
            gates[url]?.await()
            return postResponses[url] ?: default
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
    fun `custom with a blank url yields nothing`() {
        runBlocking {
            AudioResolver(FakeHttp()).candidatesFor(
                AudioSourceConfig(AudioSourceTypes.CUSTOM),
                "a",
                "b",
                "ja",
            ) shouldBe emptyList()
        }
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
    fun `custom-json rejects a wrong envelope and non-ok responses`() {
        runBlocking {
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
    }

    // --- jpod101 -------------------------------------------------------------------------------

    @Test
    fun `jpod101 builds kanji and kana params`() {
        runBlocking {
            val result = AudioResolver(FakeHttp()).candidatesFor(
                AudioSourceConfig(AudioSourceTypes.JPOD_101),
                term = "読む",
                reading = "よむ",
                languageIso = "ja",
            )
            result.single().url shouldBe
                "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=%E8%AA%AD%E3%82%80&kana=%E3%82%88%E3%82%80"
        }
    }

    @Test
    fun `jpod101 moves an entirely-kana term to the kana param`() {
        runBlocking {
            val result = AudioResolver(FakeHttp()).candidatesFor(
                AudioSourceConfig(AudioSourceTypes.JPOD_101),
                term = "よむ",
                reading = "よむ",
                languageIso = "ja",
            )
            result.single().url shouldBe
                "https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kana=%E3%82%88%E3%82%80"
        }
    }

    // --- jisho ---------------------------------------------------------------------------------

    @Test
    fun `jisho scrapes the audio element source`() {
        runBlocking {
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
    }

    @Test
    fun `jisho normalizes protocol-relative cloudfront urls to https`() {
        runBlocking {
            val html = """
            <div class="concept_light">
              <audio id="audio_日本:にほん"><source src="//d1vjc5dkcd3yh2.cloudfront.net/audio/dc01e322abc0a7121e20dd1215e374fc.mp3" type="audio/mpeg"></audio>
            </div>
            """.trimIndent()
            val http = FakeHttp(responses = mapOf("https://jisho.org/search/%E6%97%A5%E6%9C%AC" to ok(html)))
            val result = AudioResolver(http).candidatesFor(
                AudioSourceConfig(AudioSourceTypes.JISHO),
                term = "日本",
                reading = "にほん",
                languageIso = "ja",
            )
            result.single().url shouldBe "https://d1vjc5dkcd3yh2.cloudfront.net/audio/dc01e322abc0a7121e20dd1215e374fc.mp3"
        }
    }

    @Test
    fun `jisho returns nothing when the element is missing`() {
        runBlocking {
            val http = FakeHttp(responses = mapOf("https://jisho.org/search/a" to ok("<html></html>")))
            AudioResolver(http).candidatesFor(AudioSourceConfig(AudioSourceTypes.JISHO), "a", "a", "ja") shouldBe emptyList()
        }
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
    fun `defaults are omitted when disabled and when the type is configured`() {
        runBlocking {
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
    }

    // --- language-pod-101 ----------------------------------------------------------------------

    @Test
    fun `language-pod-101 resolves Japanese by POST form and validates kana reading`() = runBlocking {
        val postUrl = "https://www.japanesepod101.com/learningcenter/reference/dictionary_post"
        val html = """
            <div class="dc-result-row">
              <span class="dc-vocab_kana">よむ</span>
              <audio><source src="/audio/japanese/yomu.mp3"></audio>
            </div>
            <div class="dc-result-row">
              <span class="dc-vocab_kana">ちがう</span>
              <audio><source src="/audio/japanese/wrong.mp3"></audio>
            </div>
        """.trimIndent()
        val http = FakeHttp(postResponses = mapOf(postUrl to ok(html)))
        val result = AudioResolver(http).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.LANGUAGE_POD_101),
            term = "読む",
            reading = "よむ",
            languageIso = "ja",
        )
        http.postedForms.size shouldBe 1
        val (url, fields) = http.postedForms.first()
        url shouldBe postUrl
        fields["search_query"] shouldBe "読む"
        fields["match_type"] shouldBe "exact"
        result shouldContainExactly listOf(
            AudioCandidate(
                url = "https://www.japanesepod101.com/audio/japanese/yomu.mp3",
                sourceType = AudioSourceTypes.LANGUAGE_POD_101,
            ),
        )
    }

    @Test
    fun `language-pod-101 uses class host for English and validates vocab text`() = runBlocking {
        val postUrl = "https://www.englishclass101.com/learningcenter/reference/dictionary_post"
        val html = """
            <div class="dc-result-row">
              <span class="dc-vocab">read</span>
              <audio><source src="https://assets.languagepod101.com/audio/read.mp3"></audio>
            </div>
        """.trimIndent()
        val http = FakeHttp(postResponses = mapOf(postUrl to ok(html)))
        val result = AudioResolver(http).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.LANGUAGE_POD_101),
            term = "read",
            reading = "read",
            languageIso = "en",
        )
        http.postedForms.first().first shouldBe postUrl
        result shouldContainExactly listOf(
            AudioCandidate(
                url = "https://assets.languagepod101.com/audio/read.mp3",
                sourceType = AudioSourceTypes.LANGUAGE_POD_101,
            ),
        )
    }

    // --- lingua-libre --------------------------------------------------------------------------

    @Test
    fun `lingua-libre searches Commons for iso639-3 and validates filename`() = runBlocking {
        val searchUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search&srsearch=" +
            AudioResolver.urlEncode("intitle:/-bonjour.wav/i incategory:\"Lingua_Libre_pronunciation-fra\"") +
            "&srnamespace=6&origin=*"
        val fileTitle = "File:LL-Q150 (fra)-User1-bonjour.wav"
        val infoUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&titles=" +
            AudioResolver.urlEncode(fileTitle) +
            "&prop=imageinfo&iiprop=user|url&origin=*"

        val searchJson = """
            {"query":{"search":[{"title":"$fileTitle"},{"title":"File:Other.wav"}]}}
        """.trimIndent()
        val infoJson = """
            {"query":{"pages":{"123":{"imageinfo":[{"url":"https://upload.wikimedia.org/audio/bonjour.wav","user":"User1"}]}}}}
        """.trimIndent()

        val http = FakeHttp(responses = mapOf(searchUrl to ok(searchJson), infoUrl to ok(infoJson)))
        val result = AudioResolver(http).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.LINGUA_LIBRE),
            term = "bonjour",
            reading = "bonjour",
            languageIso = "fr",
        )

        result shouldContainExactly listOf(
            AudioCandidate(
                url = "https://upload.wikimedia.org/audio/bonjour.wav",
                name = "User1",
                sourceType = AudioSourceTypes.LINGUA_LIBRE,
            ),
        )
    }

    // --- wiktionary ----------------------------------------------------------------------------

    @Test
    fun `wiktionary searches Commons and formats regional display names`() = runBlocking {
        val searchUrl = "https://commons.wikimedia.org/w/api.php?action=query&format=json&list=search&srsearch=" +
            AudioResolver.urlEncode("intitle:/de(-[a-zA-Z]{2})?-Katze[0123456789]*.ogg/i") +
            "&srnamespace=6&origin=*"
        val titleAt = "File:de-AT-Katze.ogg"
        val titleDe = "File:de-Katze.ogg"
        val infoUrlAt = "https://commons.wikimedia.org/w/api.php?action=query&format=json&titles=" +
            AudioResolver.urlEncode(titleAt) +
            "&prop=imageinfo&iiprop=user|url&origin=*"
        val infoUrlDe = "https://commons.wikimedia.org/w/api.php?action=query&format=json&titles=" +
            AudioResolver.urlEncode(titleDe) +
            "&prop=imageinfo&iiprop=user|url&origin=*"

        val searchJson = """
            {"query":{"search":[{"title":"$titleAt"},{"title":"$titleDe"}]}}
        """.trimIndent()
        val infoAtJson = """
            {"query":{"pages":{"1":{"imageinfo":[{"url":"https://upload.wikimedia.org/at.ogg","user":"AustrianSpeaker"}]}}}}
        """.trimIndent()
        val infoDeJson = """
            {"query":{"pages":{"2":{"imageinfo":[{"url":"https://upload.wikimedia.org/de.ogg","user":"GermanSpeaker"}]}}}}
        """.trimIndent()

        val http = FakeHttp(
            responses = mapOf(
                searchUrl to ok(searchJson),
                infoUrlAt to ok(infoAtJson),
                infoUrlDe to ok(infoDeJson),
            ),
        )
        val result = AudioResolver(http).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.WIKTIONARY),
            term = "Katze",
            reading = "Katze",
            languageIso = "de",
        )

        result shouldContainExactly listOf(
            AudioCandidate(
                url = "https://upload.wikimedia.org/at.ogg",
                name = "(AT) AustrianSpeaker",
                sourceType = AudioSourceTypes.WIKTIONARY,
            ),
            AudioCandidate(
                url = "https://upload.wikimedia.org/de.ogg",
                name = "GermanSpeaker",
                sourceType = AudioSourceTypes.WIKTIONARY,
            ),
        )
    }

    // --- text-to-speech ------------------------------------------------------------------------

    @Test
    fun `text-to-speech builds synthetic tts url from term`() = runBlocking {
        val result = AudioResolver(FakeHttp()).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.TEXT_TO_SPEECH, voice = "ja-JP-Standard-A"),
            term = "猫",
            reading = "ねこ",
            languageIso = "ja",
        )
        result shouldContainExactly listOf(
            AudioCandidate(
                url = "tts://speak?text=%E7%8C%AB&lang=ja&voice=ja-JP-Standard-A",
                name = "ja-JP-Standard-A",
                sourceType = AudioSourceTypes.TEXT_TO_SPEECH,
            ),
        )
    }

    @Test
    fun `text-to-speech-reading builds synthetic tts url from reading`() = runBlocking {
        val result = AudioResolver(FakeHttp()).candidatesFor(
            AudioSourceConfig(AudioSourceTypes.TEXT_TO_SPEECH_READING),
            term = "猫",
            reading = "ねこ",
            languageIso = "ja",
        )
        result shouldContainExactly listOf(
            AudioCandidate(
                url = "tts://speak?text=%E3%81%AD%E3%81%93&lang=ja&voice=",
                name = null,
                sourceType = AudioSourceTypes.TEXT_TO_SPEECH_READING,
            ),
        )
    }

    @Test
    fun `unknown source type yields no candidates`() {
        runBlocking {
            AudioResolver(FakeHttp()).candidatesFor(
                AudioSourceConfig("unknown-type"),
                "a",
                "b",
                "ja",
            ) shouldBe emptyList()
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

    // --- streaming (first sample out before the slowest source finishes) ------------------------

    private val fastUrl = "http://fast/?term=猫&reading=ねこ"
    private val slowUrl = "http://slow/?term=猫&reading=ねこ"

    private fun streamingHttp(slowGate: CompletableDeferred<Unit>): FakeHttp = FakeHttp(
        responses = mapOf(
            fastUrl to AudioHttpResponse(
                ok = true,
                status = 200,
                bodyText = audioSourceList("Fast" to "http://fast/a.mp3"),
            ),
            slowUrl to AudioHttpResponse(
                ok = true,
                status = 200,
                bodyText = audioSourceList("Slow" to "http://slow/a.mp3"),
            ),
        ),
        gates = mapOf(slowUrl to slowGate),
    )

    @Test
    fun `stream emits the fast source before the slow source finishes`() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val http = streamingHttp(slowGate)
        val resolver = AudioResolver(http)
        val updates = Channel<AudioResolutionUpdate>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            resolver.stream(
                sources = listOf(
                    AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, fastUrl),
                    AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, slowUrl),
                ),
                term = "猫",
                reading = "ねこ",
                languageIso = "ja",
                enableDefaultSources = false,
            ).collect { updates.send(it) }
        }

        // Waiting for every source (the old `awaitAll` resolve) emits nothing until the gate opens,
        // so this receive times out on that implementation.
        val first = withTimeout(5_000) { updates.receive() }
        first.done shouldBe false
        first.resolution.sources.map { it.url } shouldContainExactly listOf(fastUrl, slowUrl)
        first.resolution.candidates.map { it.url } shouldContainExactly listOf("http://fast/a.mp3")
        first.resolution.candidates.single().sourceIndex shouldBe 0

        slowGate.complete(Unit)

        val last = withTimeout(5_000) { updates.receive() }
        last.done shouldBe true
        // The final snapshot is the complete `resolve()` result, in source order.
        last.resolution.candidates.map { it.url } shouldContainExactly listOf(
            "http://fast/a.mp3",
            "http://slow/a.mp3",
        )
        AudioResolver(http).resolve(
            sources = listOf(
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, fastUrl),
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, slowUrl),
            ),
            term = "猫",
            reading = "ねこ",
            languageIso = "ja",
            enableDefaultSources = false,
        ) shouldBe last.resolution
        collector.join()
    }

    @Test
    fun `stream keeps source order when a later source finishes first`() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val http = streamingHttp(slowGate)
        val resolver = AudioResolver(http)
        val updates = Channel<AudioResolutionUpdate>(Channel.UNLIMITED)
        // Slow source is configured FIRST, fast one second: arrival order is the reverse of the
        // configured order, and every snapshot must still be in configured order.
        val collector = launch(Dispatchers.Default) {
            resolver.stream(
                sources = listOf(
                    AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, slowUrl),
                    AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, fastUrl),
                ),
                term = "猫",
                reading = "ねこ",
                languageIso = "ja",
                enableDefaultSources = false,
            ).collect { updates.send(it) }
        }

        val first = withTimeout(5_000) { updates.receive() }
        first.done shouldBe false
        first.resolution.candidates.map { it.sourceIndex } shouldContainExactly listOf(1)

        slowGate.complete(Unit)

        val last = withTimeout(5_000) { updates.receive() }
        last.resolution.candidates.map { it.url } shouldContainExactly listOf(
            "http://slow/a.mp3",
            "http://fast/a.mp3",
        )
        last.resolution.candidates.map { it.sourceIndex } shouldContainExactly listOf(0, 1)
        collector.join()
    }

    @Test
    fun `stream emits a single done update on a cache hit`() {
        runBlocking {
            val http = streamingHttp(CompletableDeferred())
            val resolver = AudioResolver(http)
            val sources = listOf(AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, fastUrl))
            val resolved = resolver.resolve(sources, "猫", "ねこ", "ja", enableDefaultSources = false)
            val requestsAfterResolve = http.requested.size

            val updates = resolver.stream(sources, "猫", "ねこ", "ja", enableDefaultSources = false).toList()

            updates.size shouldBe 1
            updates.single().done shouldBe true
            updates.single().resolution shouldBe resolved
            // A cache hit resolves nothing again.
            http.requested.size shouldBe requestsAfterResolve
        }
    }

    /**
     * Serves every URL only once *all* of them have arrived, so the sources finish together — the
     * interleaving in which a partial snapshot built before the last source could be sent after the
     * `done` one.
     */
    private class BarrierHttp(private val bodies: Map<String, String>) : AudioHttp {
        private val arrived = AtomicInteger()
        private val release = CompletableDeferred<Unit>()

        override suspend fun get(url: String): AudioHttpResponse {
            if (arrived.incrementAndGet() >= bodies.size) release.complete(Unit)
            release.await()
            return AudioHttpResponse(ok = true, status = 200, bodyText = bodies.getValue(url))
        }

        override suspend fun postForm(url: String, formFields: Map<String, String>): AudioHttpResponse =
            get(url)
    }

    @Test
    fun `the done snapshot is always emitted last when sources finish simultaneously`() {
        runBlocking {
            // A permutation/ordering check, not a race reproduction: the sources are all released
            // at the same instant (so their completion order is whatever the scheduler picks) and
            // the assertion is that every round yields exactly one snapshot per source, in
            // configured order, with the single `done` snapshot last. The guarantee that makes that
            // hold — building a snapshot and emitting it as one serialised step — is structural in
            // `AudioResolver.stream`; a JVM test cannot force the pre-fix interleaving.
            repeat(50) { round ->
                val bodies = (0 until 6).associate { index ->
                    "http://barrier/$index/?term=t$round" to
                        audioSourceList("Sample" to "http://barrier/$index/a$round.mp3")
                }
                val resolver = AudioResolver(BarrierHttp(bodies))
                val updates = withContext(Dispatchers.Default) {
                    resolver.stream(
                        sources = bodies.keys.map { AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, it) },
                        term = "t$round",
                        reading = "t$round",
                        languageIso = "ja",
                        enableDefaultSources = false,
                    ).toList()
                }

                // One snapshot per source, no duplicates, no gaps.
                updates.size shouldBe 6
                updates.dropLast(1).forEach { it.done shouldBe false }
                updates.count { it.done } shouldBe 1
                updates.last().done shouldBe true
                // Each snapshot carries a distinct, cumulative set of source samples...
                updates.map { it.resolution.candidates.size } shouldContainExactly
                    listOf(1, 2, 3, 4, 5, 6)
                // ...and the final one holds every source's sample in configured order.
                updates.last().resolution.candidates.map { it.url } shouldContainExactly
                    (0 until 6).map { index -> "http://barrier/$index/a$round.mp3" }
                updates.last().resolution.sources.size shouldBe 6
            }
        }
    }

    @Test
    fun `a source that yields no candidates still emits its own update`() {
        runBlocking {
            val emptyUrl = "http://empty/?term=猫"
            val filledUrl = "http://filled/?term=猫"
            val gate = CompletableDeferred<Unit>()
            // The source that yields nothing is not gated, so the first snapshot can only come from
            // it; the one with a sample is held open. An implementation that only emits when it has
            // candidates would swallow that first update and go straight to a single `done` one.
            val http = FakeHttp(
                responses = mapOf(filledUrl to ok(audioSourceList("Filled" to "http://filled/a.mp3"))),
                gates = mapOf(filledUrl to gate),
            )
            val resolver = AudioResolver(http)
            val updates = Channel<AudioResolutionUpdate>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) {
                resolver.stream(
                    sources = listOf(
                        AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, emptyUrl),
                        AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, filledUrl),
                    ),
                    term = "猫",
                    reading = "猫",
                    languageIso = "ja",
                    enableDefaultSources = false,
                ).collect { updates.send(it) }
            }

            val first = withTimeout(5_000) { updates.receive() }
            first.done shouldBe false
            first.resolution.candidates shouldBe emptyList()
            first.resolution.sources.size shouldBe 2

            gate.complete(Unit)

            val last = withTimeout(5_000) { updates.receive() }
            last.done shouldBe true
            last.resolution.candidates.map { it.url } shouldContainExactly listOf("http://filled/a.mp3")
            collector.join()
        }
    }

    @Test
    fun `a cancelled stream never writes the resolution cache`() {
        runBlocking {
            val slowGate = CompletableDeferred<Unit>()
            val http = streamingHttp(slowGate)
            val resolver = AudioResolver(http)
            val sources = listOf(
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, fastUrl),
                AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, slowUrl),
            )

            // Stops the stream after its first (partial) snapshot: no `done` emission is produced,
            // so nothing may be cached for this lookup.
            val first = resolver.stream(sources, "猫", "ねこ", "ja", enableDefaultSources = false).first()
            first.done shouldBe false

            slowGate.complete(Unit)
            val resolved = resolver.resolve(sources, "猫", "ねこ", "ja", enableDefaultSources = false)
            resolved.candidates.map { it.url } shouldContainExactly listOf(
                "http://fast/a.mp3",
                "http://slow/a.mp3",
            )
            // The fast source is fetched once by the cancelled stream and once by `resolve`: had the
            // partial stream cached its snapshot, `resolve` would not have asked again.
            http.requested.count { it == fastUrl } shouldBe 2
        }
    }
}
// KUN <--
