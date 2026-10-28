// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import mihon.app.di.appGraph
import mihon.core.dictionary.ui.DictionaryWebViewState
import mihon.core.dictionary.ui.prepareDictionaryWebViewShell

/**
 * Keeps a dictionary popup WebView warmed without attaching it to a reader view tree (02 §8.3).
 *
 * Android's ViewGroup attach pass assumes its child array is stable while dispatching attached
 * state, so warming via a hidden WebView added to a reader root can race with it on Android 16;
 * the warm shell therefore lives here until a popup actually mounts.
 */
internal object DictionaryPopupWebViewWarmup {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var warmedWebView: WebView? = null

    fun warm(context: Context, languageCode: String = "") {
        val appContext = context.applicationContext
        runOnMain {
            val webView = warmedWebView?.takeIf { it.parent == null } ?: createWebView(appContext).also {
                warmedWebView = it
            }
            webView.setBaseContext(appContext)
            val shell = buildWarmShell(appContext, languageCode)
            prepareDictionaryWebViewShell(appContext, webView, shell.bootstrapHtml, shell.backgroundColor)
        }
    }

    fun acquire(context: Context, languageCode: String = ""): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Dictionary popup WebView must be acquired on the main thread"
        }

        val appContext = context.applicationContext
        val webView = warmedWebView?.takeIf { it.parent == null }?.also { warmedWebView = null }
            ?: createWebView(appContext)

        webView.setBaseContext(context)
        val shell = buildWarmShell(appContext, languageCode)
        prepareDictionaryWebViewShell(appContext, webView, shell.bootstrapHtml, shell.backgroundColor)
        return webView
    }

    fun recycle(context: Context, webView: WebView?) {
        if (webView == null) return
        val appContext = context.applicationContext
        runOnMain {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.alpha = 0f
            webView.isClickable = false
            webView.isLongClickable = false
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.setOnKeyListener(null)
            webView.setOnLongClickListener(null)
            webView.resetDictionaryCallbacks()
            webView.setBaseContext(appContext)

            if (warmedWebView == null || warmedWebView === webView) {
                warmedWebView = webView
            } else {
                // A discarded WebView must not keep its JS bridges alive: `destroy()` alone left the
                // audio bridge's TextToSpeech binding and its in-flight audio downloads running.
                (webView.tag as? DictionaryWebViewState)?.audioBridge?.destroy()
                // KUN --> Same for the Anki bridge's coroutine scope: a recycled popup must not
                // complete a card creation for the document it no longer shows.
                (webView.tag as? DictionaryWebViewState)?.ankiJsBridge?.destroy()
                // KUN <--
                webView.destroy()
            }
        }
    }

    private fun createWebView(context: Context): WebView = WebView(MutableContextWrapper(context.applicationContext))

    private fun WebView.setBaseContext(context: Context) {
        (this.context as? MutableContextWrapper)?.baseContext = context
    }

    private fun buildWarmShell(context: Context, languageCode: String): WarmShell {
        val dictionaryPreferences = context.appGraph.dictionaryPreferences
        val uiPreferences = context.appGraph.uiPreferences
        val resolved = resolveDictionaryTheme(
            themeMode = dictionaryPreferences.themeMode().get(),
            customColor = dictionaryPreferences.customColor().get(),
            eInkMode = dictionaryPreferences.eInkMode().get(),
            forceDefaultTheme = false,
            systemIsDark = context.isSystemDark(),
            appThemeMode = uiPreferences.themeMode.get(),
            appAmoled = uiPreferences.themeDarkAmoled.get(),
            appColorTheme = uiPreferences.colorTheme.get(),
        )
        val colorScheme = getDictionaryColorScheme(resolved.isDark, resolved.isAmoled, resolved.seedColor)
        val backgroundColor = if (resolved.isAmoled && resolved.isDark) Color.Black else colorScheme.surface
        val bootstrapHtml = getDictionaryBootstrapHtml(
            context = context,
            colorScheme = colorScheme,
            isDark = resolved.isDark,
            isAmoled = resolved.isAmoled,
            fontFamily = dictionaryPreferences.fontFamily().get(),
            eInkMode = dictionaryPreferences.eInkMode().get(),
            paginatedScrolling = dictionaryPreferences.paginatedScrolling().get(),
            paginatedScrollStepSize = dictionaryPreferences.paginatedScrollStepSize().get(),
            scrollBehavior = dictionaryPreferences.scrollBehavior().get(),
            languageCode = languageCode,
        )
        return WarmShell(bootstrapHtml, backgroundColor.toArgb())
    }

    private fun Context.isSystemDark(): Boolean {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun WebView.resetDictionaryCallbacks() {
        val state = tag as? DictionaryWebViewState ?: return
        state.onAnkiLookup = null
        state.ankiJsBridge.onAnkiLookup = null
        state.onRecursiveLookup = null
        state.onTabSelect = null
        state.onBack = null
        state.onContentInvalidated = null
        state.onContentReady = null
        state.lastPayload = null
        state.lastResults = null
        state.lastExistingExpressions = null
        state.lastMediaDataUris = null
        state.lastRenderSignature = null
        state.pendingPayload = null
        state.pendingEntryJsons = null
        state.pendingResults = null
        state.pendingExistingExpressions = null
        state.pendingMediaDataUris = null
        state.pendingRenderSignature = null
        state.payloadBridge.rawPayloadJson = ""
        state.payloadBridge.rawEntryJsons = emptyList()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class WarmShell(val bootstrapHtml: String, val backgroundColor: Int)
}
