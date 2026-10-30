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
import kotlinx.coroutines.cancel
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
    private var cachedResolver: Pair<AudioHttp, AudioResolver>? = null

    init {
        player.onPlaybackError = {
            val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
            if (config.enabled) {
                player.playFallback(config.fallbackSoundType, config.volumeFraction)
            }
        }
    }

    private fun getResolver(): AudioResolver {
        val currentHttp = http
        val cached = cachedResolver
        if (cached != null && cached.first === currentHttp) {
            return cached.second
        }
        val created = AudioResolver(currentHttp)
        cachedResolver = currentHttp to created
        return created
    }

    @JavascriptInterface
    fun fetchAudio(expression: String, reading: String, callbackId: String) {
        val webView = webViewProvider() ?: return
        val config = configProvider?.invoke() ?: return
        if (!config.enabled) {
            deliver(webView, callbackId, AudioResolution(emptyList(), emptyList()))
            return
        }
        val language = (languageIsoProvider?.invoke() ?: "ja").ifBlank { "ja" }
        scope.launch {
            val resolution = withContext(Dispatchers.IO) {
                getResolver().resolve(
                    sources = config.sources,
                    term = expression,
                    reading = reading,
                    languageIso = language,
                    enableDefaultSources = config.enableDefaultAudioSources,
                )
            }
            val ctx = webView.context
            player.attachContext(ctx)
            val candidateUrls = resolution.candidates.map { it.url }.filter { it.isNotBlank() }
            if (candidateUrls.isNotEmpty()) {
                player.prefetch(candidateUrls)
            }
            deliver(webView, callbackId, resolution)
        }
    }

    @JavascriptInterface
    fun playAudio(url: String) {
        if (!isAllowedWordAudioUrl(url)) return
        val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
        if (!config.enabled) return
        val ctx = webViewProvider()?.context
        if (ctx != null) player.attachContext(ctx)
        player.play(url, config.volumeFraction)
    }

    @JavascriptInterface
    fun playCandidates(urlsJson: String) {
        val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
        if (!config.enabled) return
        val ctx = webViewProvider()?.context
        if (ctx != null) player.attachContext(ctx)
        val urls = runCatching {
            val array = JSONArray(urlsJson)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList()).filter { isAllowedWordAudioUrl(it) }
        if (urls.isNotEmpty()) {
            player.playCandidateList(urls, config.volumeFraction)
        }
    }

    @JavascriptInterface
    fun prefetchAudio(urlsJson: String) {
        val ctx = webViewProvider()?.context ?: return
        player.attachContext(ctx)
        val urls = runCatching {
            val array = JSONArray(urlsJson)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList()).filter { isAllowedWordAudioUrl(it) }
        if (urls.isNotEmpty()) {
            player.prefetch(urls)
        }
    }

    @JavascriptInterface
    fun playFallback() {
        val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
        if (!config.enabled) return
        val ctx = webViewProvider()?.context
        if (ctx != null) player.attachContext(ctx)
        player.playFallback(config.fallbackSoundType, config.volumeFraction)
    }

    /** Stop any in-flight playback (called when the popup content is cleared/released). */
    fun stop() {
        player.stop()
    }

    @Volatile
    private var destroyed = false

    /**
     * Destroy player resources (called when the popup bridge is torn down). Idempotent: the WebView
     * teardown paths may reach it more than once, and it must not resurrect a cancelled scope.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        scope.cancel()
        player.destroy()
    }

    private fun deliver(webView: WebView, callbackId: String, resolution: AudioResolution) {
        val sources = JSONArray().apply {
            resolution.sources.forEachIndexed { index, source ->
                put(
                    JSONObject().apply {
                        put("index", index)
                        put("type", source.type)
                        put("label", AudioSourceTypes.displayName(source.type))
                        if (source.url.isNotBlank()) put("url", source.url)
                    },
                )
            }
        }
        val candidates = JSONArray().apply {
            resolution.candidates.forEach { candidate ->
                put(
                    JSONObject().apply {
                        put("url", candidate.url)
                        candidate.name?.let { put("name", it) }
                        put("sourceType", candidate.sourceType)
                        put("sourceIndex", candidate.sourceIndex)
                        if (candidate.sourceLabel.isNotBlank()) put("sourceLabel", candidate.sourceLabel)
                    },
                )
            }
        }
        val payload = JSONObject().apply {
            put("sources", sources)
            put("candidates", candidates)
        }
        val idJson = JSONObject.quote(callbackId)
        val js = "window.DictionaryRenderer && window.DictionaryRenderer.onAudioResults(" +
            "$idJson, ${payload.toString().escapeLineSeparators()});"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}

private fun String.escapeLineSeparators(): String =
    replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

// The renderer's URL fence (scheme *and* host, so a page cannot reach LAN/metadata endpoints) lives
// in `WordAudioUrlPolicy.kt` next to `isAllowedWordAudioUrl`.
// KUN <--
