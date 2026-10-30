// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.dictionary.anki.AnkiMiningController
import mihon.core.dictionary.anki.AnkiPreferences
import mihon.core.dictionary.anki.AnkiScreenshotProvider
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.ui.DictionaryRenderSignature
import mihon.core.dictionary.ui.DictionaryWebViewState
import mihon.core.dictionary.ui.TabInfo
import mihon.core.dictionary.ui.buildConfigPayload
import mihon.core.dictionary.ui.buildResultEntryJsonStrings
import mihon.core.dictionary.ui.prepareDictionaryWebViewShell
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Renders dictionary lookup results in a WebView that emits the Yomitan DOM contract
 * (02 §3.2, docs/design/yomitan-spec/05-rendering.md).
 *
 * The heavy entry payload is built off the main thread and pushed to JS through
 * `PayloadBridge` (02 §3.4); the render signature diffing decides whether to re-render or patch
 * existing content.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DictionaryEntryWebView(
    results: List<LookupResult>,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    activeProfile: DictionaryProfile,
    fontSize: Int = 16,
    existingExpressions: Set<String> = emptySet(),
    tabs: List<TabInfo> = emptyList(),
    entryJsons: List<String>? = null,
    customCss: String = "",
    modifier: Modifier = Modifier,
    webViewProvider: ((Context) -> WebView)? = null,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null,
    onTabSelect: ((Int) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onContentReadyChange: ((Boolean) -> Unit)? = null,
    hideOnContentInvalidated: Boolean = true,
    forceDefaultTheme: Boolean = false,
    requestFocusOnMount: Boolean = false,
    // KUN -->
    /**
     * Whether volume keys may navigate the entry (scroll to the previous/next result group).
     * Evaluated on every key event so a caller can tie it to live state. Defaults to `false` so
     * physical volume keys control device media volume (word pronunciation audio) by default.
     */
    volumeKeyNavigationAllowed: () -> Boolean = { false },
    // KUN <--
    isLoading: Boolean = false,
    // KUN -->
    // Anki mining: the renderer's `addToAnki` reaches `AnkiCardCreator` either through the
    // caller-supplied [onAnkiLookup] or, by default, through a controller built from the live
    // Anki settings. [ankiSentence]/[ankiSentenceOffset] carry the OCR lookup context so
    // `{sentence}`/`{cloze-*}` populate; [ankiScreenshotProvider] supplies the cropped page
    // bytes for `{screenshot}` (the reader owns the capture; see plan 05 §B4/T4).
    onAnkiLookup: ((LookupResult, Int?, String?, String?, Boolean) -> Unit)? = null,
    ankiSentence: String = "",
    ankiSentenceOffset: Int = -1,
    ankiScreenshotProvider: AnkiScreenshotProvider? = null,
    // KUN <--
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.appGraph.dictionaryPreferences }
    // KUN -->
    val ankiPreferences = remember(context) { AnkiPreferences(AndroidPreferenceStore(context)) }
    val ankiMining = remember(context, ankiPreferences) { AnkiMiningController(context, ankiPreferences) }
    val ankiScope = rememberCoroutineScope()
    val ankiEnabled by ankiPreferences.enabled().collectAsState()
    val ankiDupAction by ankiPreferences.duplicateAction().collectAsState()
    val audioEnabled by prefs.audioEnabled().collectAsState()
    val audioAutoPlay by prefs.audioAutoPlay().collectAsState()
    // KUN <--
    val themeMode by prefs.themeMode().collectAsState()
    val compactGlossary by prefs.compactGlossary().collectAsState()
    val customColor by prefs.customColor().collectAsState()
    val fontFamily by prefs.fontFamily().collectAsState()
    val eInkMode by prefs.eInkMode().collectAsState()
    val paginatedScrolling by prefs.paginatedScrolling().collectAsState()
    val paginatedScrollStepSize by prefs.paginatedScrollStepSize().collectAsState()
    val scrollBehavior by prefs.scrollBehavior().collectAsState()
    val showFrequencyHarmonic by prefs.showFrequencyHarmonic().collectAsState()
    val showFrequencyAverage by prefs.showFrequencyAverage().collectAsState()
    val groupTerms by prefs.groupTerms().collectAsState()
    val showPitchDiagram by prefs.showPitchDiagram().collectAsState()
    val showPitchNumber by prefs.showPitchNumber().collectAsState()
    val showPitchText by prefs.showPitchText().collectAsState()
    val groupPitches by prefs.groupPitches().collectAsState()
    val showNavigationButtons by prefs.showNavigationButtons().collectAsState()
    val recursiveNavMode by prefs.recursiveLookupMode().collectAsState()
    val uiPreferences = remember(context) { context.appGraph.uiPreferences }
    val appAmoled by uiPreferences.themeDarkAmoled.collectAsState()
    val appThemeMode by uiPreferences.themeMode.collectAsState()
    val appColorTheme by uiPreferences.colorTheme.collectAsState()

    val systemIsDark = isSystemInDarkTheme()
    val resolvedTheme = remember(
        themeMode,
        customColor,
        eInkMode,
        forceDefaultTheme,
        systemIsDark,
        appThemeMode,
        appAmoled,
        appColorTheme,
    ) {
        resolveDictionaryTheme(
            themeMode = themeMode,
            customColor = customColor,
            eInkMode = eInkMode,
            forceDefaultTheme = forceDefaultTheme,
            systemIsDark = systemIsDark,
            appThemeMode = appThemeMode,
            appAmoled = appAmoled,
            appColorTheme = appColorTheme,
        )
    }
    val isDark = resolvedTheme.isDark
    val isAmoled = resolvedTheme.isAmoled
    val seedColor = resolvedTheme.seedColor
    val colorScheme = if (resolvedTheme.useAppTheme) {
        MaterialTheme.colorScheme
    } else {
        remember(isDark, isAmoled, seedColor) { getDictionaryColorScheme(isDark, isAmoled, seedColor) }
    }
    val backgroundColor = remember(isDark, isAmoled, colorScheme) {
        if (isAmoled && isDark) Color.Black else colorScheme.surface
    }

    val renderSignature = remember(
        results, styles, placeholder, isDark,
        showFrequencyHarmonic, showFrequencyAverage, groupTerms,
        showPitchDiagram, showPitchNumber, showPitchText,
        activeProfile, tabs, recursiveNavMode, showNavigationButtons, groupPitches,
        entryJsons, compactGlossary, ankiEnabled, ankiDupAction, audioEnabled, audioAutoPlay,
    ) {
        DictionaryRenderSignature(
            results = results, styles = styles, placeholder = placeholder, isDark = isDark,
            showFrequencyHarmonic = showFrequencyHarmonic,
            showFrequencyAverage = showFrequencyAverage,
            groupTerms = groupTerms,
            showPitchDiagram = showPitchDiagram, showPitchNumber = showPitchNumber,
            showPitchText = showPitchText, activeProfile = activeProfile, tabs = tabs,
            recursiveNavMode = recursiveNavMode, renderRecursiveChrome = true,
            wordAudioEnabled = audioEnabled, wordAudioAutoplay = audioEnabled && audioAutoPlay,
            showNavigationButtons = showNavigationButtons, groupPitches = groupPitches,
            ankiEnabled = ankiEnabled, ankiDupAction = ankiDupAction,
        )
    }

    var configPayloadPair by remember { mutableStateOf<Pair<String, DictionaryRenderSignature>?>(null) }
    var entryJsonsPair by remember { mutableStateOf<Pair<List<String>, DictionaryRenderSignature>?>(null) }
    // KUN -->
    /**
     * Whether the surface currently admits to being painted. `false` from the moment a flush
     * invalidates the content until the renderer's `contentReady` post arrives; the surface is
     * `alpha = 0` for that window.
     */
    var contentReady by remember { mutableStateOf(true) }

    /**
     * A renderer failure worth telling the user about: the JS renderer's own caught exception text,
     * or [RENDER_STALL_MARKER] when the ready post never came at all.
     *
     * The notice is written *into the page* (`showRenderNotice`) rather than drawn by Compose: a
     * Compose `Text` under an `AndroidView` is painted before the interop `WebView` child, so the
     * WebView covers it — verified on device, the text was there and invisible. Writing it into the
     * document also keeps whatever partial content the renderer did draw on screen.
     */
    var renderFailure by remember { mutableStateOf<String?>(null) }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    val renderErrorMessage = stringResource(KUNMR.strings.dictionary_render_error)
    val currentConfigPayload = configPayloadPair
    val currentEntryJsons = entryJsonsPair
    // A flush is only dispatched once both payloads for the current signature exist; before that
    // the transparent surface is the expected "nothing sent yet" state, not a failure.
    val flushDispatched = currentConfigPayload?.second == renderSignature &&
        currentEntryJsons?.second == renderSignature
    LaunchedEffect(contentReady, isLoading, renderSignature, flushDispatched) {
        // Backstop for a renderer that never reports back. `renderer.js` posts `contentReady` from
        // a `finally` now, but a document whose scripts failed to load at all cannot post anything,
        // and that used to be indistinguishable from "still rendering": a permanently transparent
        // surface with no message. Restore it and say what happened. `flushDispatched` (not "has
        // entries") is the trigger, so the idle placeholder text is covered too.
        if (contentReady || isLoading || !flushDispatched) return@LaunchedEffect
        delay(RENDER_READY_BACKSTOP_MS)
        if (!contentReady && !isLoading) {
            val webView = webViewHolder.value
            webView?.alpha = 1f
            renderFailure = RENDER_STALL_MARKER
            if (webView != null) showRenderNotice(webView, renderErrorMessage)
            Log.w(
                "DictionaryRender",
                "ready_backstop timeout_ms=$RENDER_READY_BACKSTOP_MS results=${results.size} " +
                    "entry_jsons=${entryJsons?.size ?: 0} alpha=${webView?.alpha}",
            )
        }
    }
    LaunchedEffect(renderSignature) {
        val buildStart = SystemClock.elapsedRealtime()
        val built = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val config = buildConfigPayload(
                context, styles, emptyMap(), placeholder, isDark,
                showFrequencyHarmonic, showFrequencyAverage, groupTerms,
                showPitchDiagram, showPitchNumber, showPitchText,
                audioEnabled && audioAutoPlay, activeProfile, emptySet(), tabs, recursiveNavMode,
                renderRecursiveChrome = true,
                wordAudioEnabled = audioEnabled,
                showNavigationButtons = showNavigationButtons,
                groupPitches = groupPitches,
                compactGlossary = compactGlossary,
                ankiEnabled = ankiEnabled,
                ankiDupAction = ankiDupAction,
                displayNameProvider = { prefs.getDisplayName(it) },
            )
            val entries = entryJsons ?: buildResultEntryJsonStrings(
                results,
                activeProfile,
                context,
                groupPitches,
                displayNameProvider = { prefs.getDisplayName(it) },
            )
            config.toString() to entries
        }
        Log.i(
            "DictionaryRender",
            "payload_build_ms=${SystemClock.elapsedRealtime() - buildStart} results=${results.size} tabs=${tabs.size}",
        )
        configPayloadPair = built.first to renderSignature
        entryJsonsPair = built.second to renderSignature
    }

    val bootstrapHtml = remember(
        context, isDark, isAmoled, colorScheme, fontFamily, eInkMode,
        paginatedScrolling, paginatedScrollStepSize, scrollBehavior, activeProfile.languageCode, compactGlossary,
    ) {
        getDictionaryBootstrapHtml(
            context = context, colorScheme = colorScheme, isDark = isDark,
            isAmoled = isAmoled, fontFamily = fontFamily,
            eInkMode = eInkMode, paginatedScrolling = paginatedScrolling,
            paginatedScrollStepSize = paginatedScrollStepSize, scrollBehavior = scrollBehavior,
            languageCode = activeProfile.languageCode,
            compactGlossary = compactGlossary,
        )
    }

    Box(modifier = modifier.background(backgroundColor)) {
        AndroidView<WebView>(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                val webView = webViewProvider?.invoke(ctx) ?: WebView(ctx)
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)

                if (webView.tag !is DictionaryWebViewState) {
                    prepareDictionaryWebViewShell(ctx, webView, bootstrapHtml, backgroundColor.toArgb())
                }
                webViewHolder.value = webView
                (webView.tag as? DictionaryWebViewState)?.let { state ->
                    state.onContentInvalidated = {
                        onContentReadyChange?.invoke(false)
                        contentReady = false
                        // KUN --> A notice belongs to the render that failed: every new render starts
                        // without it. (Clearing it on `onContentReady` instead removed it immediately,
                        // because the failing render still posts ready from its `finally`.)
                        if (renderFailure != null) {
                            renderFailure = null
                            removeRenderNotice(webView)
                        }
                        // KUN <--
                        if (hideOnContentInvalidated) webView.alpha = 0f
                    }
                    state.onContentReady = {
                        onContentReadyChange?.invoke(true)
                        contentReady = true
                        webView.alpha = 1f
                    }
                    // KUN --> A JS render throw is now reported, not just printed to a console
                    // nobody can read on a device.
                    state.onRenderError = { message ->
                        Log.e("DictionaryRender", "js_render_error=$message")
                        renderFailure = message
                        webView.alpha = 1f
                        showRenderNotice(webView, renderErrorMessage)
                    }
                    // KUN <--
                    if (state.pageReady) state.flush(webView)
                }

                webView.isClickable = true
                webView.isLongClickable = true
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
                // KUN -->
                // `factory` runs before the WebView is attached to a window, and `requestFocus()`
                // before attach is a no-op. Re-request it once the first attach pass has run, so the
                // WebView really does hold focus and a long-press starts text selection (and the
                // system selection toolbar) rather than being handed to another focused view.
                if (requestFocusOnMount) {
                    webView.requestFocus()
                    webView.post {
                        if (webView.isAttachedToWindow && !webView.hasFocus()) webView.requestFocus()
                    }
                }
                // KUN <--
                // KUN -->
                // No `setOnLongClickListener`: a View-level long-click listener makes the WebView
                // swallow the long-press before its internal gesture detector can start text
                // selection, so the gloss text becomes unselectable. Leaving the listener unset
                // lets the WebView's own long-press selection (and its context menu) work.
                // KUN <--
                webView.setOnKeyListener { v, keyCode, event ->
                    // KUN -->
                    // While the reader OCR overlay is on, the dictionary's own volume-key navigation
                    // stands down too: the key must fall through to the system and change the media
                    // volume (the dictionary plays word audio) instead of scrolling the entry.
                    val overlayAllowsVolumeKeys = volumeKeyNavigationAllowed()
                    // KUN <--
                    if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                        overlayAllowsVolumeKeys &&
                        prefs.volumeKeyNavigation().get()
                    ) {
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                                (v as? WebView)?.evaluateJavascript(
                                    "window.DictionaryRenderer && window.DictionaryRenderer.navigate(-1);",
                                    null,
                                )
                                true
                            }
                            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                (v as? WebView)?.evaluateJavascript(
                                    "window.DictionaryRenderer && window.DictionaryRenderer.navigate(1);",
                                    null,
                                )
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }

                webView
            },
            update = { webView: WebView ->
                val state = webView.tag as? DictionaryWebViewState ?: return@AndroidView
                if (state.bootstrapHtml != bootstrapHtml) state.reloadShell(webView, bootstrapHtml)
                state.onRecursiveLookup = onRecursiveLookup
                state.onTabSelect = onTabSelect
                state.onBack = onBack
                // KUN -->
                state.ankiJsBridge.onAnkiLookup = { index, glossaryIndex, selectedDict, popupSelection, forceOpen ->
                    results.getOrNull(index)?.let { result ->
                        if (onAnkiLookup != null) {
                            onAnkiLookup(result, glossaryIndex, selectedDict, popupSelection, forceOpen)
                        } else {
                            ankiScope.launch {
                                ankiMining.mine(
                                    result = result,
                                    glossaryIndex = glossaryIndex,
                                    selectedDict = selectedDict,
                                    popupSelection = popupSelection,
                                    forceOpen = forceOpen,
                                    sentence = ankiSentence,
                                    offset = ankiSentenceOffset,
                                    screenshotBytes = ankiScreenshotProvider?.capture(),
                                )
                            }
                        }
                    }
                }
                // KUN <--
                // KUN -->
                // Native word-audio: the renderer's play button resolves through this bridge.
                state.audioBridge.configProvider = { prefs.audioPreferences() }
                state.audioBridge.languageIsoProvider = { activeProfile.languageCode }
                // KUN <--
                state.customCss = customCss
                state.fontSize = fontSize
                state.onContentInvalidated = {
                    onContentReadyChange?.invoke(false)
                    contentReady = false
                    // KUN --> A notice belongs to the render that failed: see the factory's copy.
                    if (renderFailure != null) {
                        renderFailure = null
                        removeRenderNotice(webView)
                    }
                    // KUN <--
                    if (hideOnContentInvalidated) webView.alpha = 0f
                }
                state.onContentReady = {
                    onContentReadyChange?.invoke(true)
                    contentReady = true
                    webView.alpha = 1f
                }
                // KUN -->
                state.onRenderError = { message ->
                    Log.e("DictionaryRender", "js_render_error=$message")
                    renderFailure = message
                    webView.alpha = 1f
                    showRenderNotice(webView, renderErrorMessage)
                }
                webViewHolder.value = webView
                // KUN <--

                webView.setBackgroundColor(backgroundColor.toArgb())

                if (state.pageReady) {
                    val enableRecursive = onRecursiveLookup != null
                    webView.evaluateJavascript(
                        "window.DictionaryRenderer && window.DictionaryRenderer.setRecursiveLookupEnabled($enableRecursive);",
                        null,
                    )
                    state.injectCustomCss(webView)
                    state.injectFontSize(webView)
                    if (isLoading) {
                        state.clear(webView)
                    } else {
                        val (configPayload, configSig) = currentConfigPayload ?: (null to null)
                        val (builtEntryJsons, entriesSig) = currentEntryJsons ?: (null to null)
                        if (configPayload != null && builtEntryJsons != null &&
                            configSig == renderSignature && entriesSig == renderSignature
                        ) {
                            state.flush(
                                webView,
                                results,
                                existingExpressions,
                                mediaDataUris,
                                renderSignature,
                                configPayload,
                                builtEntryJsons,
                            )
                        }
                    }
                } else {
                    val (configPayloadVal, configPayloadSig) = currentConfigPayload ?: (null to null)
                    val (builtEntryJsonsVal, entryJsonsSig) = currentEntryJsons ?: (null to null)
                    if (configPayloadVal != null && builtEntryJsonsVal != null &&
                        configPayloadSig == renderSignature && entryJsonsSig == renderSignature
                    ) {
                        state.pendingPayload = configPayloadVal
                        state.pendingEntryJsons = builtEntryJsonsVal
                        state.pendingResults = results
                        state.pendingExistingExpressions = existingExpressions
                        state.pendingMediaDataUris = mediaDataUris
                        state.pendingRenderSignature = renderSignature
                    }
                }
            },
            onRelease = { webView ->
                val state = webView.tag as? DictionaryWebViewState
                state?.clear(webView)
                state?.onContentInvalidated = null
                state?.onContentReady = null
                // KUN -->
                state?.onRenderError = null
                webViewHolder.value = null
                // KUN <--
                state?.lastPayload = null
                state?.lastResults = null
                state?.lastExistingExpressions = null
                state?.lastMediaDataUris = null
                state?.lastRenderSignature = null
                state?.pendingPayload = null
                state?.pendingEntryJsons = null
                state?.pendingResults = null
                state?.pendingExistingExpressions = null
                state?.pendingMediaDataUris = null
                state?.pendingRenderSignature = null
                // KUN -->
                // The entry WebView owns its WebView (the factory creates it), so leaving
                // composition means this page is gone: tear the bridges down instead of only
                // dropping the callback, which the next `update` pass would re-arm against a
                // document that no longer exists.
                state?.ankiJsBridge?.destroy()
                // `destroy()` (idempotent, and it stops playback itself) closes the bridge's scope
                // and its TextToSpeech engine; `stop()` alone left the TTS binding and the
                // `ServiceConnection` alive for the lifetime of the process.
                state?.audioBridge?.destroy()
                state?.audioBridge?.configProvider = null
                state?.audioBridge?.languageIsoProvider = null
                // KUN <--
                state?.payloadBridge?.rawPayloadJson = ""
                state?.payloadBridge?.rawEntryJsons = emptyList()
                // KUN -->
                // `onRelease` is the only place this WebView instance goes away, and nothing else
                // ever calls `destroy()` on it — the warmup's
                // `DictionaryPopupWebViewWarmup.recycle`, the only other destroy site, has no
                // callers — so every lookup leaked a WebView built with the reader Activity as its
                // `Context` (10-40 MB PSS plus its renderer process). Destroying per entry rather
                // than wiring the warmup's `acquire`/`recycle` leaves the WebView reuse behaviour
                // exactly as it is.
                webView.destroy()
                // KUN <--
            },
        )
        // KUN -->
        // A render failure is written into the page by `showRenderNotice` (see its KDoc for why it
        // is not a Compose overlay), so nothing is drawn here.
        // KUN <--
    }
}

// KUN -->
/** How long the surface waits for `contentReady` before restoring itself and saying so. */
private const val RENDER_READY_BACKSTOP_MS = 1_000L

/** `renderFailure` value for a renderer that never reported ready (as opposed to one that threw). */
private const val RENDER_STALL_MARKER = "render_stalled"

private const val RENDER_NOTICE_ID = "komikun-render-error"

/**
 * Show [message] inside the entry document, below whatever did render.
 *
 * A Compose `Text` cannot do this job: `AndroidView` interop `View`s are children of the Compose
 * host and are drawn *after* the Compose canvas, so a `Text` under the `WebView` is invisible (this
 * was verified on device — the text was composed, laid out, and covered). Injecting into the
 * document also leaves genuinely partial content visible, which a scrim or an alpha swap would not.
 */
private fun showRenderNotice(webView: WebView, message: String) {
    val text = org.json.JSONObject.quote(message)
    webView.evaluateJavascript(
        "(function(){" +
            "var e=document.getElementById('$RENDER_NOTICE_ID');" +
            "if(!e){e=document.createElement('div');e.id='$RENDER_NOTICE_ID';" +
            "e.setAttribute('style','padding:24px;font-size:14px;line-height:1.5;opacity:.85;');" +
            "(document.getElementById('entries')||document.body).appendChild(e);}" +
            "e.textContent=$text;" +
            "})();",
        null,
    )
}

/** Drop a previously injected [showRenderNotice] notice (idempotent). */
private fun removeRenderNotice(webView: WebView) {
    webView.evaluateJavascript(
        "var e=document.getElementById('$RENDER_NOTICE_ID');if(e)e.remove();",
        null,
    )
}
// KUN <--
