// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Native `WordAudioBridge` for the dictionary WebView. The renderer (`renderer.js`,
// `createAudioButton`) already knows this contract: it calls
// `WordAudioBridge.fetchAudio(expression, reading, callbackId)` and expects the candidates back on
// `window.DictionaryRenderer.onAudioResults(callbackId, [{url,…}])`, then calls
// `WordAudioBridge.playAudio(url)`. This class replaces Yomitan's browser-side `DisplayAudio`:
// it resolves the configured sources (via [AudioResolver]) and plays the chosen URL through
// [WordAudioPlayer].
//
// Rendering/UX is untouched — the play button, autoplay and error states are already in the
// existing renderer; this only supplies the missing native half.
package mihon.core.dictionary.audio

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WordAudioBridge(
    private val webViewProvider: () -> WebView?,
) {
    /** Supplies the live audio configuration (from prefs). `null` disables resolution. */
    var configProvider: (() -> AudioPreferences)? = null

    /** The active profile's language ISO code; defaults to `ja` when unset. */
    var languageIsoProvider: (() -> String)? = null

    /** Injectable for tests; defaults to the OkHttp-backed implementation. */
    var http: AudioHttp = OkHttpAudioHttp()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player = WordAudioPlayer()

    @JavascriptInterface
    fun fetchAudio(expression: String, reading: String, callbackId: String) {
        val webView = webViewProvider() ?: return
        val config = configProvider?.invoke() ?: return
        if (!config.enabled) {
            deliver(webView, callbackId, emptyList())
            return
        }
        val language = (languageIsoProvider?.invoke() ?: "ja").ifBlank { "ja" }
        scope.launch {
            val candidates = withContext(Dispatchers.IO) {
                AudioResolver(http).resolveAll(
                    sources = config.sources,
                    term = expression,
                    reading = reading,
                    languageIso = language,
                    enableDefaultSources = config.enableDefaultAudioSources,
                )
            }
            deliver(webView, callbackId, candidates)
        }
    }

    @JavascriptInterface
    fun playAudio(url: String) {
        val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
        if (!config.enabled) return
        player.play(url, config.volumeFraction)
    }

    /** Stop any in-flight playback (called when the popup content is cleared/released). */
    fun stop() {
        player.stop()
    }

    private fun deliver(webView: WebView, callbackId: String, candidates: List<AudioCandidate>) {
        val array = JSONArray().apply {
            candidates.forEach { candidate ->
                put(
                    JSONObject().apply {
                        put("url", candidate.url)
                        candidate.name?.let { put("name", it) }
                        put("sourceType", candidate.sourceType)
                    },
                )
            }
        }
        val idJson = JSONObject.quote(callbackId)
        val js = "window.DictionaryRenderer && window.DictionaryRenderer.onAudioResults(" +
            "$idJson, ${array.toString().escapeLineSeparators()});"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}

private fun String.escapeLineSeparators(): String =
    replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
// KUN <--
