// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import mihon.core.dictionary.audio.WordAudioBridge
import mihon.core.dictionary.model.LookupResult
import java.net.URLEncoder

/**
 * Prepares a dictionary WebView with the shell HTML, the JS bridges and the `komikun://`
 * URL-scheme router (02 §3.3).
 *
 * Settings: JS + DOM storage on, `blockNetworkLoads=true`, file/content access **off** (the page is
 * `loadDataWithBaseURL`'d from an `https` base and media arrive as `data:` URIs, so nothing needs it;
 * markup from a third-party dictionary must not be able to read local files), no scrollbars,
 * force-dark off, safe-browsing disabled. State lives on `webView.tag`.
 */
@SuppressLint("SetJavaScriptEnabled")
fun prepareDictionaryWebViewShell(
    context: Context,
    webView: WebView = WebView(context),
    bootstrapHtml: String,
    backgroundColor: Int = android.graphics.Color.TRANSPARENT,
): WebView {
    (webView.tag as? DictionaryWebViewState)?.let { state ->
        if (state.bootstrapHtml != bootstrapHtml) state.reloadShell(webView, bootstrapHtml)
        return webView
    }

    val state = DictionaryWebViewState(context, webViewProvider = { webView }, bootstrapHtml = bootstrapHtml)
    webView.apply {
        alpha = 0f
        setBackgroundColor(backgroundColor)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.setSupportZoom(true)
        settings.displayZoomControls = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = false
        }

        disableSafeBrowsingForDictionary(this)

        addJavascriptInterface(state.readyBridge, "DictionaryReadyBridge")
        addJavascriptInterface(state.payloadBridge, "PayloadBridge")
        addJavascriptInterface(state.ankiJsBridge, "AnkiJsBridge")
        // KUN -->
        // Native half of the renderer's existing word-audio play button.
        addJavascriptInterface(state.audioBridge, "WordAudioBridge")
        // KUN <--

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val s = view?.tag as? DictionaryWebViewState ?: return
                s.pageReady = true
                val enableRecursive = s.onRecursiveLookup != null
                view.evaluateJavascript(
                    "window.DictionaryRenderer && window.DictionaryRenderer.setRecursiveLookupEnabled($enableRecursive);",
                    null,
                )
                s.injectFontSize(view)
                s.injectCustomCss(view)
                s.flush(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                val s = view?.tag as? DictionaryWebViewState
                // A definition link (`<a href="https://…">`) is common in Jitendex/Wiktionary-style
                // dictionaries. The shell is loaded with `blockNetworkLoads=true` and is only
                // reloaded when `bootstrapHtml` changes, so letting the WebView navigate itself
                // would leave every later lookup rendering into a dead document until process
                // death: nothing but a `komikun://` URL may reach this document.
                when (classifyDictionaryNavigation(url.toString())) {
                    DictionaryNavigation.EXTERNAL -> {
                        openExternalUrl(view?.context ?: context, url)
                        return true
                    }
                    DictionaryNavigation.DROP -> return true
                    DictionaryNavigation.INTERNAL -> Unit
                }
                when (url.host) {
                    KOMIKUN_HOST_LOOKUP -> {
                        val q = url.getQueryParameter("q") ?: return true
                        val sentence = url.getQueryParameter("sentence")
                        val sentenceOffset = url.getQueryParameter("offset")?.toIntOrNull()
                        val x = url.getQueryParameter("x")?.toFloatOrNull()
                        val y = url.getQueryParameter("y")?.toFloatOrNull()
                        if (q.isNotBlank()) s?.onRecursiveLookup?.invoke(q, sentence, sentenceOffset, x, y, null)
                        return true
                    }
                    KOMIKUN_HOST_TAB -> {
                        url.getQueryParameter("index")?.toIntOrNull()?.let { s?.onTabSelect?.invoke(it) }
                        return true
                    }
                    KOMIKUN_HOST_BACK -> {
                        s?.onBack?.invoke()
                        return true
                    }
                    KOMIKUN_HOST_KANJI -> {
                        val q = url.getQueryParameter("q") ?: return true
                        if (q.isNotBlank()) s?.onRecursiveLookup?.invoke(q, null, null, null, null, "kanji")
                        return true
                    }
                }
                return true
            }
        }

        tag = state
        state.reloadShell(this, bootstrapHtml)
    }
    return webView
}

class DictionaryWebViewState(
    val context: Context,
    webViewProvider: () -> WebView?,
    var bootstrapHtml: String = "",
) {
    val readyBridge: DictionaryReadyBridge = DictionaryReadyBridge(webViewProvider) { this }
    val payloadBridge: PayloadBridge = PayloadBridge()
    val ankiJsBridge: AnkiJsBridge = AnkiJsBridge(webViewProvider)
    // KUN --> Word-audio resolution/playback; the app layer sets `configProvider`/`languageIsoProvider`.
    val audioBridge: WordAudioBridge = WordAudioBridge(webViewProvider)
    // KUN <--
    var pageReady: Boolean = false
    var fontSize: Int = 16

    @Volatile var contentReadyGeneration: Long = 0

    @Volatile var nextContentReadyRequestId: Long = 0
    var onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null
    var onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null
    var onTabSelect: ((Int) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onContentInvalidated: (() -> Unit)? = null
    var onContentReady: (() -> Unit)? = null
    // KUN -->
    /** The renderer's caught render exception (bridge: `DictionaryReadyBridge.reportRenderError`). */
    var onRenderError: ((String) -> Unit)? = null
    // KUN <--
    var lastPayload: String? = null
    var lastResults: List<LookupResult>? = null
    var lastExistingExpressions: Set<String>? = null
    var lastMediaDataUris: Map<String, String>? = null
    var lastRenderSignature: DictionaryRenderSignature? = null
    var pendingPayload: String? = null
    var pendingEntryJsons: List<String>? = null
    var pendingResults: List<LookupResult>? = null
    var pendingExistingExpressions: Set<String>? = null
    var pendingMediaDataUris: Map<String, String>? = null
    var pendingRenderSignature: DictionaryRenderSignature? = null
    var customCss: String = ""

    fun injectCustomCss(webView: WebView) {
        if (customCss.isEmpty()) {
            webView.evaluateJavascript(
                "var el = document.getElementById('komikun-custom-css'); if (el) el.textContent = '';",
                null,
            )
            return
        }
        val encoded = URLEncoder.encode(customCss, "UTF-8").replace("+", "%20")
        webView.evaluateJavascript(
            "(function(v) {" +
                "var el = document.getElementById('komikun-custom-css');" +
                "if (el) el.textContent = v;" +
                "})(decodeURIComponent('$encoded'));",
            null,
        )
    }

    fun injectFontSize(webView: WebView) {
        webView.evaluateJavascript(
            "(function(v) {" +
                "v = v + 'px';" +
                "document.documentElement.style.fontSize = v;" +
                "document.body.style.fontSize = v;" +
                "document.documentElement.style.setProperty('--font-size-no-units', '$fontSize');" +
                "})('$fontSize');",
            null,
        )
    }

    fun clear(webView: WebView) {
        onContentInvalidated?.invoke()
        // KUN --> Never leave a clip playing after the popup content is gone.
        audioBridge.stop()
        // KUN <--
        payloadBridge.rawPayloadJson = ""
        payloadBridge.rawEntryJsons = emptyList()
        webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.clear();", null)
        resetState()
    }

    private fun resetState() {
        lastPayload = null
        lastResults = null
        lastExistingExpressions = null
        lastMediaDataUris = null
        lastRenderSignature = null
        clearPendingPayload()
    }

    fun reloadShell(webView: WebView, html: String) {
        bootstrapHtml = html
        pageReady = false
        lastPayload = null
        lastResults = null
        lastExistingExpressions = null
        lastMediaDataUris = null
        lastRenderSignature = null
        contentReadyGeneration++
        nextContentReadyRequestId++
        clearPendingPayload()
        onContentInvalidated?.invoke()
        webView.loadDataWithBaseURL("https://komikun.local/popup/", html, "text/html", "UTF-8", null)
    }

    fun flush(
        webView: WebView,
        results: List<LookupResult>? = null,
        existingExpressions: Set<String>? = null,
        mediaDataUris: Map<String, String>? = null,
        renderSignature: DictionaryRenderSignature? = null,
        configPayload: String? = null,
        entryJsons: List<String>? = null,
    ) {
        val p = configPayload ?: pendingPayload ?: return
        val entries = entryJsons ?: pendingEntryJsons ?: return
        val renderResults = results ?: pendingResults
        val renderExistingExpressions = existingExpressions ?: pendingExistingExpressions
        val renderMediaDataUris = mediaDataUris ?: pendingMediaDataUris
        val signature = renderSignature ?: pendingRenderSignature

        val canPatchExistingRender = lastResults != null &&
            renderResults != null &&
            lastResults === renderResults &&
            lastRenderSignature == signature
        if (canPatchExistingRender) {
            if (lastExistingExpressions != renderExistingExpressions && renderExistingExpressions != null) {
                updateAnkiStatus(webView, renderExistingExpressions)
                lastExistingExpressions = renderExistingExpressions
            }
            if (lastMediaDataUris != renderMediaDataUris && renderMediaDataUris != null) {
                updateMedia(webView, renderMediaDataUris)
                lastMediaDataUris = renderMediaDataUris
            }
            if (p != lastPayload) lastPayload = p
            clearPendingPayload()
            return
        }

        if (p == lastPayload && lastRenderSignature == signature) {
            if (lastExistingExpressions != renderExistingExpressions && renderExistingExpressions != null) {
                updateAnkiStatus(webView, renderExistingExpressions)
                lastExistingExpressions = renderExistingExpressions
            }
            if (lastMediaDataUris != renderMediaDataUris && renderMediaDataUris != null) {
                updateMedia(webView, renderMediaDataUris)
                lastMediaDataUris = renderMediaDataUris
            }
            clearPendingPayload()
            return
        }

        lastPayload = p
        lastResults = renderResults
        lastExistingExpressions = renderExistingExpressions
        lastMediaDataUris = renderMediaDataUris
        lastRenderSignature = signature
        contentReadyGeneration++
        nextContentReadyRequestId++
        clearPendingPayload()

        val renderStart = SystemClock.elapsedRealtime()
        onContentInvalidated?.invoke()
        injectFontSize(webView)

        // Keep the JS call tiny; the renderer pulls the heavier entry payloads through PayloadBridge.
        payloadBridge.rawPayloadJson = p
        payloadBridge.rawEntryJsons = entries

        val js = "if(window.DictionaryRenderer){" +
            "window.DictionaryRenderer.replacePopupResults();" +
            renderExistingExpressions?.let {
                "window.DictionaryRenderer.updateAnkiStatus(${org.json.JSONArray(it).toString().toJavascriptExpression()});"
            }.orEmpty() +
            renderMediaDataUris?.takeIf { it.isNotEmpty() }?.let {
                "window.DictionaryRenderer.updateMediaDataUris(${org.json.JSONObject(it).toString().toJavascriptExpression()});"
            }.orEmpty() +
            "}"
        webView.evaluateJavascript(js, null)

        // KUN --> One line per flush (flush only runs when the render signature changes): how much
        // went out and whether the surface was still transparent when it did.
        Log.i(
            "DictionaryRender",
            "webview_flush entry_count=${entries.size} payload_len=${p.length} " +
                "alpha=${webView.alpha} dispatch_ms=${SystemClock.elapsedRealtime() - renderStart}",
        )
        // KUN <--
    }

    private fun updateAnkiStatus(webView: WebView, expressions: Set<String>) {
        val json = org.json.JSONArray(expressions).toString()
        webView.evaluateJavascript(
            "if(window.DictionaryRenderer){window.DictionaryRenderer.updateAnkiStatus(${json.toJavascriptExpression()});}",
            null,
        )
    }

    private fun updateMedia(webView: WebView, mediaDataUris: Map<String, String>) {
        val json = org.json.JSONObject(mediaDataUris).toString()
        webView.evaluateJavascript(
            "if(window.DictionaryRenderer){window.DictionaryRenderer.updateMediaDataUris(${json.toJavascriptExpression()});}",
            null,
        )
    }

    private fun clearPendingPayload() {
        pendingPayload = null
        pendingEntryJsons = null
        pendingResults = null
        pendingExistingExpressions = null
        pendingMediaDataUris = null
        pendingRenderSignature = null
    }
}

private fun disableSafeBrowsingForDictionary(webView: WebView) {
    val settings = webView.settings
    val disabledViaAndroidX = runCatching {
        val featureClass = Class.forName("androidx.webkit.WebViewFeature")
        val featureValue = featureClass.getField("SAFE_BROWSING_ENABLE").get(null) as String
        val supported = featureClass.getMethod("isFeatureSupported", String::class.java)
            .invoke(null, featureValue) as? Boolean ?: false
        if (!supported) return@runCatching false

        val compatClass = Class.forName("androidx.webkit.WebSettingsCompat")
        val setter = compatClass.getMethod(
            "setSafeBrowsingEnabled",
            WebSettings::class.java,
            Boolean::class.javaPrimitiveType,
        )
        setter.invoke(null, settings, false)
        true
    }.getOrDefault(false)

    if (!disabledViaAndroidX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = false
    }
}

private const val KOMIKUN_SCHEME = "komikun"
private const val KOMIKUN_HOST_LOOKUP = "lookup"
private const val KOMIKUN_HOST_TAB = "tab"
private const val KOMIKUN_HOST_BACK = "back"
private const val KOMIKUN_HOST_KANJI = "kanji"

/** What the popup shell does with a URL a click inside the WebView produced. */
internal enum class DictionaryNavigation {
    /** A `komikun://` URL, routed to the app (lookup/tab/back/kanji). */
    INTERNAL,

    /** A web link: handed to an external app, never to the popup. */
    EXTERNAL,

    /** Anything else: dropped, leaving the loaded shell untouched. */
    DROP,
}

private val EXTERNAL_NAVIGATION_SCHEMES = setOf("http", "https")

/** RFC 3986 `scheme` = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ). */
private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")

/**
 * Decide how the popup shell treats [url]. No [WebView] is involved, so the decision is testable on
 * the JVM: only `komikun://` is the popup's own, http(s) goes to an external browser, and anything
 * else is dropped — the loaded document must never be navigated away from.
 */
internal fun classifyDictionaryNavigation(url: String?): DictionaryNavigation {
    val scheme = url?.substringBefore(':').orEmpty()
    if (!URL_SCHEME.matches(scheme)) return DictionaryNavigation.DROP
    return when {
        scheme.equals(KOMIKUN_SCHEME, ignoreCase = true) -> DictionaryNavigation.INTERNAL
        EXTERNAL_NAVIGATION_SCHEMES.any { it.equals(scheme, ignoreCase = true) } -> DictionaryNavigation.EXTERNAL
        else -> DictionaryNavigation.DROP
    }
}

/**
 * Hand [url] to the app that owns it. The popup usually runs on the application context, hence
 * `NEW_TASK`; a device with no handler must not crash the reader.
 */
private fun openExternalUrl(context: Context?, url: Uri) {
    val target = context ?: return
    try {
        target.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: the popup stays on its current document.
    }
}
