// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The native half of word audio: what the bridge actually hands the renderer.
//
// `WordAudioBridge` is a `@JavascriptInterface`, so this drives it through `fetchAudio` — the same
// entry point the renderer calls — with a mocked `WebView` (its `post` runs the runnable, and
// `evaluateJavascript` is captured), a mocked `Context`, a mocked player and a fake `AudioHttp`.
// What is asserted is the *payload* the renderer parses: `done` (the third argument) and
// `playbackReady` (the source-priority flag), plus the terminal empty payload when there is no
// audio configuration. `android.jar`'s `org.json` is a stub on the JVM, so the payload text is
// parsed with kotlinx-serialization (already a test dependency of this module). Not covered: the
// JS side of the payload (see `AudioLazyResolutionGuardTest`) and real WebView evaluation.
package mihon.core.dictionary.audio

import android.content.Context
import android.webkit.WebView
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class WordAudioBridgePayloadTest {

    private val delivered = mutableListOf<String>()

    private fun bridge(
        http: AudioHttp,
        config: (() -> AudioPreferences)? = null,
    ): WordAudioBridge {
        val context = mockk<Context>(relaxed = true)
        val webView = mockk<WebView>(relaxed = true)
        every { webView.context } returns context
        every { webView.post(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
        every { webView.evaluateJavascript(any(), any()) } answers {
            delivered += firstArg<String>()
        }
        return WordAudioBridge(
            webViewProvider = { webView },
            dispatcher = Dispatchers.Unconfined,
            player = mockk(relaxed = true),
        ).apply {
            this.http = http
            configProvider = config
            languageIsoProvider = { "ja" }
        }
    }

    private class Delivery(val callbackId: String, val payload: JsonObject, val done: Boolean)

    /** Decodes the captured `onAudioResults(<id>, <payload>, <done>)` call. */
    private fun delivery(index: Int): Delivery {
        val js = delivered[index]
        js shouldContain "onAudioResults("
        val start = js.indexOf("onAudioResults(") + "onAudioResults(".length
        val idEnd = js.indexOf(',', start)
        val callbackId = js.substring(start, idEnd).trim().removeSurrounding("\"")
        val jsonStart = js.indexOf('{', idEnd)
        val jsonEnd = js.lastIndexOf('}')
        val payload = Json.parseToJsonElement(js.substring(jsonStart, jsonEnd + 1)) as JsonObject
        val done = js.substring(jsonEnd + 1).let { it.contains("true") }
        return Delivery(callbackId, payload, done)
    }

    private suspend fun awaitDeliveries(count: Int) {
        withTimeout(5_000) {
            while (delivered.size < count) delay(10)
        }
    }

    private fun JsonObject.candidates(): JsonArray = this["candidates"] as JsonArray

    @Test
    fun `snapshots carry done and the source-priority flag`() {
        runBlocking {
            val gated = "http://slow/"
            val gate = CompletableDeferred<Unit>()
            val http = object : AudioHttp {
                override suspend fun get(url: String): AudioHttpResponse {
                    if (url == gated) gate.await()
                    return AudioHttpResponse(
                        ok = true,
                        status = 200,
                        bodyText = """{"type":"audioSourceList","audioSources":[{"url":"$url/a.mp3"}]}""",
                    )
                }

                override suspend fun postForm(url: String, formFields: Map<String, String>): AudioHttpResponse =
                    get(url)
            }
            // Source 0 is pinned open, source 1 answers at once: the sample belongs to source 1, but
            // source 0 could still outrank it, so the first payload must not be playback-ready.
            val audio = bridge(http) {
                AudioPreferences(
                    enabled = true,
                    sources = listOf(
                        AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, gated),
                        AudioSourceConfig(AudioSourceTypes.CUSTOM_JSON, "http://fast/"),
                    ),
                    enableDefaultAudioSources = false,
                )
            }

            audio.fetchAudio("猫", "ねこ", "cb1")
            awaitDeliveries(1)

            val partial = delivery(0)
            partial.callbackId shouldBe "cb1"
            partial.done shouldBe false
            partial.payload["playbackReady"]!!.jsonPrimitive.boolean shouldBe false
            (partial.payload.candidates()[0] as JsonObject)["sourceIndex"]!!.jsonPrimitive.int shouldBe 1

            gate.complete(Unit)
            awaitDeliveries(2)

            val final = delivery(1)
            final.done shouldBe true
            // Terminal and non-empty: whatever the priority rule did before, this is playable.
            final.payload["playbackReady"]!!.jsonPrimitive.boolean shouldBe true
            final.payload.candidates().size shouldBe 2
        }
    }

    @Test
    fun `a missing audio configuration still delivers a terminal empty payload`() {
        // `configProvider` is `(() -> AudioPreferences)?`, so "no configuration" is a null provider.
        val audio = bridge(mockk(relaxed = true), config = null)

        audio.fetchAudio("猫", "ねこ", "cb2")

        delivered.size shouldBe 1
        val only = delivery(0)
        only.callbackId shouldBe "cb2"
        only.done shouldBe true
        only.payload["playbackReady"]!!.jsonPrimitive.boolean shouldBe false
        only.payload.candidates().size shouldBe 0
        (only.payload["sources"] as JsonArray).size shouldBe 0
    }
}
// KUN <--
