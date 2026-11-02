// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Native `WordAudioBridge` for the dictionary WebView. The renderer (`renderer.js`,
// `createAudioButton`) already knows this contract: it calls
// `WordAudioBridge.fetchAudio(expression, reading, callbackId)` — only on a play tap or when the
// long-press picker opens, never at render time — and receives the candidates on
// `window.DictionaryRenderer.onAudioResults(callbackId, {sources, candidates}, done)`, once per
// source that finishes plus a final `done = true` snapshot, then calls
// `WordAudioBridge.playCandidates(urlsJson)` / `playAudio(url)`. A lookup the renderer drops (a new
// search replaced the word) is abandoned with `WordAudioBridge.cancelAudio(callbackId)`. This class
// replaces Yomitan's browser-side `DisplayAudio`: it resolves the configured sources (via
// [AudioResolver]) and plays the chosen URL through [WordAudioPlayer].
//
// Rendering/UX is untouched — the play button, autoplay and error states are already in the
// existing renderer; this only supplies the missing native half.
package mihon.core.dictionary.audio

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class WordAudioBridge(
    private val webViewProvider: () -> WebView?,
    // KUN --> Same shape as `AnkiJsBridge`: the JVM has no main looper, so tests pass their own
    // dispatcher and a mocked player. Production always uses the defaults.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val player: WordAudioPlayer = WordAudioPlayer(),
    // KUN <--
) {
    /** Supplies the live audio configuration (from prefs). `null` disables resolution. */
    var configProvider: (() -> AudioPreferences)? = null

    /** The active profile's language ISO code; defaults to `ja` when unset. */
    var languageIsoProvider: (() -> String)? = null

    /** Injectable for tests; defaults to the OkHttp-backed implementation. */
    var http: AudioHttp = OkHttpAudioHttp()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var cachedResolver: Pair<AudioHttp, AudioResolver>? = null

    // KUN --> One streaming job per in-flight request, so the renderer can abandon a lookup it no
    // longer shows instead of waiting for every configured source. Entries are removed when the job
    // completes, so a finished request never leaves a cancellable entry (or a stale job) behind.
    private val fetchJobs = ConcurrentHashMap<String, Job>()
    // KUN <--

    init {
        player.onPlaybackError = {
            val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
            if (config.enabled) {
                player.playFallback(config.fallbackSoundType, config.volumeFraction)
            }
        }
    }

    private fun updateAllowedLoopbackPorts(config: AudioPreferences) {
        val customPorts = config.sources.mapNotNull { src ->
            runCatching {
                val parsed = src.url.replace("{term}", "a").replace("{reading}", "a").toHttpUrlOrNull()
                if (parsed != null && (parsed.host == "localhost" || parsed.host.endsWith(".localhost") || parsed.host == "127.0.0.1" || parsed.host == "::1")) {
                    parsed.port
                } else {
                    null
                }
            }.getOrNull()
        }.toSet()
        allowedLoopbackPorts = DEFAULT_ALLOWED_LOOPBACK_PORTS + customPorts
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
        // KUN --> Teardown only: a gone WebView cannot receive the payload, and the renderer page it
        // would fall back to is gone with it, so there is nothing to answer.
        val webView = webViewProvider() ?: return
        // KUN <--
        val config = configProvider?.invoke()
        if (config == null) {
            // No audio configuration at all (`null` disables resolution). Answer with an empty,
            // final resolution: the renderer's "Loading audio…" row only clears on `done`, so
            // returning silently here would leave it up forever.
            deliver(webView, callbackId, AudioResolution(emptyList(), emptyList()), done = true, playbackReady = false)
            return
        }
        updateAllowedLoopbackPorts(config)
        if (!config.enabled) {
            deliver(webView, callbackId, AudioResolution(emptyList(), emptyList()), done = true, playbackReady = false)
            return
        }
        val language = (languageIsoProvider?.invoke() ?: "ja").ifBlank { "ja" }
        // KUN --> Nothing is resolved at render time any more: the renderer asks for audio only on a
        // play tap or when the long-press picker opens. Each snapshot is delivered as its source
        // finishes and carries `playbackReady` (the highest-priority source with a sample is known),
        // so playback starts in configured priority order rather than on the fastest answer; the
        // remaining candidates are prefetched by the player once playback starts, not here.
        player.attachContext(webView.context)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            getResolver().stream(
                sources = config.sources,
                term = expression,
                reading = reading,
                languageIso = language,
                enableDefaultSources = config.enableDefaultAudioSources,
            ).flowOn(Dispatchers.IO).collect { update ->
                deliver(webView, callbackId, update.resolution, update.done, update.playbackReady)
            }
        }
        // Registered before the first emission (the job is lazy), so a `cancelAudio` that arrives
        // right after `fetchAudio` always finds it. A finished or cancelled job removes itself.
        fetchJobs[callbackId] = job
        job.invokeOnCompletion { fetchJobs.remove(callbackId, job) }
        job.start()
        // KUN <--
    }

    /**
     * Abandons the stream started for [callbackId] (the renderer drops a lookup whose word is no
     * longer shown): no *new* emission is made for it. A delivery that was already handed to
     * `webView.post` can still reach the page, and is ignored there — the renderer dropped the
     * callback id. Unknown or already-finished ids are a no-op, so a prune that races a terminal
     * snapshot — or arrives after [destroy] — is harmless.
     */
    @JavascriptInterface
    fun cancelAudio(callbackId: String) {
        val job = fetchJobs.remove(callbackId) ?: return
        job.cancel()
    }

    /** Number of streaming requests still tracked (diagnostics/test seam). */
    internal fun activeAudioRequestCount(): Int = fetchJobs.size

    @JavascriptInterface
    fun playAudio(url: String) {
        val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
        updateAllowedLoopbackPorts(config)
        if (!isAllowedWordAudioUrl(url)) return
        if (!config.enabled) return
        val ctx = webViewProvider()?.context
        if (ctx != null) player.attachContext(ctx)
        player.play(url, config.volumeFraction)
    }

    @JavascriptInterface
    fun playCandidates(urlsJson: String) {
        val config = configProvider?.invoke() ?: AudioPreferences.DEFAULT
        updateAllowedLoopbackPorts(config)
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
        configProvider?.invoke()?.let { updateAllowedLoopbackPorts(it) }
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

    private fun deliver(
        webView: WebView,
        callbackId: String,
        resolution: AudioResolution,
        done: Boolean,
        playbackReady: Boolean,
    ) {
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
            // KUN --> Playback may only start once the highest-priority source with a sample is
            // known; the renderer gates on this flag instead of on "the first candidate arrived".
            put("playbackReady", playbackReady)
            // KUN <--
        }
        val idJson = JSONObject.quote(callbackId)
        // KUN --> `done` is the third argument: false while sources are still resolving, true on the
        // final snapshot. A renderer that predates it treats a missing third argument as "done".
        val js = "window.DictionaryRenderer && window.DictionaryRenderer.onAudioResults(" +
            "$idJson, ${payload.toString().escapeLineSeparators()}, ${if (done) "true" else "false"});"
        // KUN <--
        webView.post { webView.evaluateJavascript(js, null) }
    }
}

private fun String.escapeLineSeparators(): String =
    replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

// The renderer's URL fence (scheme *and* host, so a page cannot reach LAN/metadata endpoints) lives
// in `WordAudioUrlPolicy.kt` next to `isAllowedWordAudioUrl`.
// KUN <--
