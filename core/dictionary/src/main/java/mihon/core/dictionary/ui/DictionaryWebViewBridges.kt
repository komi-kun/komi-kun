// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.ui

import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * JavaScript bridge that signals content readiness via `postVisualStateCallback`, so
 * `onContentReady` fires only after the frame is actually painted (02 §3.3).
 */
class DictionaryReadyBridge(
    private val webViewProvider: () -> WebView?,
    private val stateProvider: () -> DictionaryWebViewState?,
) {
    @JavascriptInterface
    fun contentReady() {
        val webView = webViewProvider() ?: return
        val state = stateProvider() ?: return
        val generation = state.contentReadyGeneration
        val requestId = ++state.nextContentReadyRequestId
        // @JavascriptInterface methods run on the JavaBridge thread; post back to the main thread.
        webView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                webView.postVisualStateCallback(
                    requestId,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (state.contentReadyGeneration != generation ||
                                state.nextContentReadyRequestId != requestId
                            ) {
                                return
                            }
                            stateProvider()?.onContentReady?.invoke()
                            // KUN --> One line per ready post, logged after the callback restored the
                            // surface: the entry count that was drawn and the alpha it now has.
                            Log.i(
                                "DictionaryRender",
                                "webview_ready entry_count=${state.payloadBridge.rawEntryJsons.size} " +
                                    "payload_len=${state.payloadBridge.rawPayloadJson.length} " +
                                    "alpha=${webView.alpha}",
                            )
                            // KUN <--
                        }
                    },
                )
            } else {
                stateProvider()?.onContentReady?.invoke()
            }
        }
    }

    // KUN -->
    /**
     * The renderer's caught render exception, handed to Kotlin instead of dying in a JS console no
     * device can show. Runs on the JavaBridge thread; the callback is posted to the main thread
     * because it updates Compose state and the WebView's alpha.
     */
    @JavascriptInterface
    fun reportRenderError(message: String?) {
        val text = message?.take(2_000).orEmpty()
        Log.e("DictionaryRender", "js_render_error=$text")
        val webView = webViewProvider() ?: return
        val state = stateProvider() ?: return
        webView.post { stateProvider()?.onRenderError?.invoke(text) }
    }
    // KUN <--
}

/**
 * Passes large payloads (JSON) from Kotlin to JS without embedding them in
 * `evaluateJavascript()` strings: JS calls `getPayloadJson()` / `getEntry(i)` to pull the data
 * natively (02 §3.3).
 */
class PayloadBridge {
    @Volatile var rawPayloadJson: String = ""

    @Volatile var rawEntryJsons: List<String> = emptyList()

    @JavascriptInterface fun getPayloadJson(): String = rawPayloadJson

    @JavascriptInterface fun getEntryCount(): Int = rawEntryJsons.size

    @JavascriptInterface fun getEntry(index: Int): String? = rawEntryJsons.getOrNull(index)
}

/**
 * JavaScript bridge for Anki card creation (wired in the Anki phase, W5). Kept so the renderer
 * shell's `AnkiBridge` shim always resolves to a real native object (02 §3.3, §5).
 */
class AnkiJsBridge(
    private val webViewProvider: () -> WebView?,
    // KUN --> lookup coroutine dispatcher: the main thread in the app, an immediate one in JVM tests.
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    // KUN <--
) {
    var onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null

    private val scopeJob = Job()
    private val scope = CoroutineScope(dispatcher + scopeJob)

    /**
     * Idempotent teardown for a discarded WebView: cancels in-flight lookups and drops the callback,
     * so a recycled (or `destroy()`ed) popup cannot complete a card creation for its dead document.
     * Safe to call twice, and never re-arms the bridge: a later `addToAnki` is a no-op.
     */
    fun destroy() {
        onAnkiLookup = null
        scopeJob.cancel()
    }

    @JavascriptInterface
    fun addToAnki(index: String, glossary: String, selectedDict: String, popupSelection: String) {
        val idx = index.toIntOrNull() ?: return
        val gloss = glossary.toIntOrNull()
        scope.launch { onAnkiLookup?.invoke(idx, gloss, selectedDict, popupSelection, false) }
    }

    @JavascriptInterface
    fun openInAnki(index: String, glossary: String, selectedDict: String, popupSelection: String) {
        val idx = index.toIntOrNull() ?: return
        val gloss = glossary.toIntOrNull()
        scope.launch { onAnkiLookup?.invoke(idx, gloss, selectedDict, popupSelection, true) }
    }
}
