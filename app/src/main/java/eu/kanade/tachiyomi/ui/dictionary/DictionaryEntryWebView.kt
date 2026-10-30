// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    // A render failed but drew something: the JS renderer's own caught exception text.
    //
    // The notice is written *into the page* (`showRenderNotice`) rather than drawn by Compose: a
    // Compose `Text` under an `AndroidView` is painted before the interop `WebView` child, so the
    // WebView covers it — verified on device, the text was there and invisible. Writing it into the
    // document also keeps whatever partial content the renderer did draw on screen. A render that
    // never reaches the screen at all is not this path: it is the Compose failure surface below.
    var renderFailure by remember { mutableStateOf<String?>(null) }

    // The `contentReadyGeneration` the notice belongs to. The renderer posts `contentReady` from its
    // `finally`, so the ready post that follows a caught render error is that error's own and must
    // keep its notice; any later ready belongs to a render that succeeded and clears it.
    var renderFailureGeneration by remember { mutableStateOf(-1L) }

    // How far the *current WebView instance* has got, for the creation-time failure surface below.
    // `pageReady` is the shell document's `onPageFinished`; without it nothing in the document can be
    // scripted at all. `renderDispatched` means a payload was handed to this instance and
    // `renderReachedReady` that one of those renders actually reached the screen.
    var pageReadySeen by remember { mutableStateOf(false) }
    var renderDispatched by remember { mutableStateOf(false) }
    var renderReachedReady by remember { mutableStateOf(false) }
    var readyPostCount by remember { mutableIntStateOf(0) }

    // Non-null when the surface has given up on this WebView (`SURFACE_REASON_NOT_READY`,
    // `SURFACE_REASON_RENDER_STALLED` or, with `rendererExhausted`, a dead renderer). It is drawn by
    // Compose *instead of* the `AndroidView`: a Compose `Text` composed under the interop `WebView`
    // is painted first and covered by it, so a message can only be trusted if no WebView is composed
    // at all. Retry recreates the WebView and re-dispatches the payload exactly once.
    var surfaceFailure by remember { mutableStateOf<String?>(null) }

    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    // KUN -->
    /**
     * Renderer-process recovery. A WebView whose renderer process died (or stopped responding) is
     * unusable for the rest of its life: every later `evaluateJavascript` is a no-op and the view
     * stays whatever it last painted — usually nothing. The only recovery is a new WebView, so
     * [webViewGeneration] is bumped (re-creating the `AndroidView` and with it the view) and the
     * payload is re-dispatched from the remembered pair by the new state's first `flush`.
     */
    val webViewGeneration = remember { mutableIntStateOf(0) }
    val rendererRecoveries = remember { mutableIntStateOf(0) }

    /** Recoveries exhausted: draw the failure natively instead of another unusable WebView. */
    val rendererExhausted = remember { mutableStateOf(false) }

    /**
     * What the surface does when its renderer is lost, shared by the API 29+
     * `WebViewRenderProcessClient` and the shell's `WebViewClient.onRenderProcessGone`,
     * and both `AndroidView` passes. Logs the reason and the numbers that make the death
     * diagnosable, then recreates the WebView (or, after [MAX_RENDERER_RECOVERIES], draws the
     * failure in Compose).
     */
    fun onRendererLost(reason: String) {
        val webView = webViewHolder.value
        val state = webView?.tag as? DictionaryWebViewState
        // The two callbacks can both fire for one death; recover once.
        if (state?.rendererDead == true) return
        state?.rendererDead = true
        Log.e(
            "DictionaryRender",
            "js_render_dead reason=$reason recoveries=${rendererRecoveries.intValue} " +
                "generation=${webViewGeneration.intValue} results=${results.size} " +
                "entry_jsons=${entryJsons?.size ?: 0}",
        )
        if (rendererRecoveries.intValue >= MAX_RENDERER_RECOVERIES) {
            rendererExhausted.value = true
            return
        }
        rendererRecoveries.intValue++
        webViewGeneration.intValue++
    }

    /**
     * `contentReady` handling for both `AndroidView` passes: restore the surface, then measure it.
     * "The renderer finished" is not "the user can see it" — see [verifyRenderedVisibility].
     */
    fun onRenderReady(webView: WebView) {
        onContentReadyChange?.invoke(true)
        renderReachedReady = true
        readyPostCount++
        webView.alpha = 1f
        // A notice belongs to the render that failed. The renderer posts `contentReady` from its
        // `finally`, so the ready post of a render that threw still carries that render's
        // generation and keeps its notice; any later ready ends clean instead of leaving a
        // "could not render this entry" above entries that did render.
        val generation = (webView.tag as? DictionaryWebViewState)?.contentReadyGeneration ?: -1L
        if (renderFailure != null && generation != renderFailureGeneration) {
            renderFailure = null
            removeRenderNotice(webView)
        }
        verifyRenderedVisibility(webView)
    }

    /**
     * Recover from the failure surface: drop the flags that produced it, recreate the WebView through
     * [webViewGeneration] and let its first `flush` re-dispatch the remembered payload — once, from
     * `onPageFinished`, exactly like the first creation.
     */
    fun retrySurface() {
        Log.i(
            "DictionaryRender",
            "webview_retry reason=${surfaceFailure ?: RENDER_REASON_EXHAUSTED} " +
                "results=${results.size} entry_jsons=${entryJsons?.size ?: 0} " +
                "recoveries=${rendererRecoveries.intValue}",
        )
        rendererExhausted.value = false
        rendererRecoveries.intValue = 0
        renderFailure = null
        renderFailureGeneration = -1L
        surfaceFailure = null
        webViewGeneration.intValue++
    }
    // KUN <--
    val renderErrorMessage = stringResource(KUNMR.strings.dictionary_render_error)

    val currentConfigPayload = configPayloadPair
    val currentEntryJsons = entryJsonsPair
    // A flush is only dispatched once both payloads for the current signature exist; before that
    // the transparent surface is the expected "nothing sent yet" state, not a failure.
    val loadingNow by rememberUpdatedState(isLoading)
    // Armed from WebView *creation*, not from a flush. The old backstop started at the flush and
    // skipped while `flushDispatched` was false — which is exactly the case of a shell whose
    // `onPageFinished` never runs (a document that failed to load), so the worst blank pane, a
    // permanently transparent surface with no message, was the one case nothing reported.
    //
    // The shell's `onPageFinished` is not reported to Compose (it lives in `:core:dictionary`), so
    // this polls the WebView instance's own `pageReady` for the length of the timeout and stops as
    // soon as it is proven that this surface can paint.
    LaunchedEffect(webViewGeneration.intValue) {
        pageReadySeen = false
        renderDispatched = false
        renderReachedReady = false
        readyPostCount = 0
        surfaceFailure = null
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            val state = webViewHolder.value?.tag as? DictionaryWebViewState
            if (state?.pageReady == true) pageReadySeen = true
            // `flush` is the only thing that records a signature, so this is "a payload went to
            // this WebView" and not merely "the shell reloaded".
            if (state != null &&
                (state.pendingRenderSignature != null || state.lastRenderSignature != null)
            ) {
                renderDispatched = true
            }
            if (elapsed >= RENDER_NOT_READY_TIMEOUT_MS) {
                val reason = renderNotReadyReason(
                    pageReady = pageReadySeen,
                    renderDispatched = renderDispatched,
                    renderReachedReady = renderReachedReady,
                    elapsedMs = elapsed,
                    timeoutMs = RENDER_NOT_READY_TIMEOUT_MS,
                )
                // A lookup in flight is a stated blank (the caller draws its own loading state),
                // not a broken WebView.
                if (reason == null || loadingNow) return@LaunchedEffect
                Log.w(
                    "DictionaryRender",
                    "webview_not_ready elapsed_ms=$elapsed page_ready=$pageReadySeen " +
                        "content_ready=$renderReachedReady ready_posts=$readyPostCount " +
                        "reason=$reason results=${results.size} " +
                        "entry_jsons=${entryJsons?.size ?: 0} " +
                        "generation=${webViewGeneration.intValue}",
                )
                surfaceFailure = reason
                return@LaunchedEffect
            }
            delay(RENDER_READY_POLL_MS)
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

    // KUN -->
    // One line per process for the WebView implementation — it is what differs between two devices
    // given the same payload — and one line per distinct custom-CSS length, the one input that can
    // hide the whole document. "Reset custom CSS" in Settings shows up here as `custom_css_len=0`.
    LaunchedEffect(Unit) {
        logWebViewEnvironmentOnce(context)
    }
    LaunchedEffect(customCss) {
        logCustomCssLength(customCss.length)
    }
    // KUN <--

    Box(modifier = modifier.background(backgroundColor)) {
        // KUN -->
        if (surfaceFailure != null || rendererExhausted.value) {
            // The WebView can never show this entry: its document never loaded, its render never
            // reached the screen, or every recreation ended with the renderer gone again. Another
            // `WebView` would be blank in exactly the same way, so say it in Compose — which is only
            // visible because no interop `WebView` child is composed over it — and offer a retry
            // that builds a fresh WebView and re-dispatches the payload.
            RenderFailureSurface(
                message = renderErrorMessage,
                onRetry = { retrySurface() },
            )
            return@Box
        }
        // A lost renderer cannot be revived: recreating the `AndroidView` under a new key builds a
        // new WebView, and the remembered payload pair is re-dispatched by its first `flush`.
        key(webViewGeneration.intValue) {
            // KUN <--
            AndroidView<WebView>(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx: Context ->
                    val webView = webViewProvider?.invoke(ctx) ?: WebView(ctx)
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)

                    if (webView.tag !is DictionaryWebViewState) {
                        prepareDictionaryWebViewShell(ctx, webView, bootstrapHtml, backgroundColor.toArgb())
                    }
                    webViewHolder.value = webView
                    // KUN --> A new WebView starts out usable, whatever the previous one did, and it
                    // gets its own renderer-death listeners.
                    (webView.tag as? DictionaryWebViewState)?.rendererDead = false
                    installRenderProcessClient(webView, ::onRendererLost)
                    // KUN <--
                    (webView.tag as? DictionaryWebViewState)?.let { state ->
                        state.onContentInvalidated = {
                            onContentReadyChange?.invoke(false)
                            // KUN --> A notice belongs to the render that failed: every new render starts
                            // without it. (Clearing it on `onContentReady` instead removed it immediately,
                            // because the failing render still posts ready from its `finally`.)
                            if (renderFailure != null) {
                                renderFailure = null
                                renderFailureGeneration = -1L
                                removeRenderNotice(webView)
                            }
                            // Each render is judged on the document's own styles again; the visibility
                            // guard is re-applied after ready if the page is still hiding its entries.
                            removeVisibilityGuard(webView)
                            // KUN <--
                            if (hideOnContentInvalidated) webView.alpha = 0f
                        }
                        // KUN --> Restores the surface, then checks that what it restored is visible.
                        state.onContentReady = { onRenderReady(webView) }
                        // KUN --> A JS render throw is now reported, not just printed to a console
                        // nobody can read on a device.
                        state.onRenderError = { message ->
                            Log.e("DictionaryRender", "js_render_error=$message")
                            renderFailure = message
                            renderFailureGeneration = state.contentReadyGeneration
                            webView.alpha = 1f
                            showRenderNotice(webView, renderErrorMessage)
                        }
                        // KUN <--
                        // KUN --> Renderer death arrives through the shell's `WebViewClient` on every
                        // API level; the 29+ client above adds the unresponsive case.
                        state.onRenderProcessGone = { crashed ->
                            onRendererLost(if (crashed) RENDER_REASON_CRASHED else RENDER_REASON_KILLED)
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
                        // KUN --> A notice belongs to the render that failed: see the factory's copy.
                        if (renderFailure != null) {
                            renderFailure = null
                            renderFailureGeneration = -1L
                            removeRenderNotice(webView)
                        }
                        removeVisibilityGuard(webView)
                        // KUN <--
                        if (hideOnContentInvalidated) webView.alpha = 0f
                    }
                    // KUN --> See the factory's copy: restore, then verify the render is visible.
                    state.onContentReady = { onRenderReady(webView) }
                    // KUN -->
                    state.onRenderError = { message ->
                        Log.e("DictionaryRender", "js_render_error=$message")
                        renderFailure = message
                        renderFailureGeneration = state.contentReadyGeneration
                        webView.alpha = 1f
                        showRenderNotice(webView, renderErrorMessage)
                    }
                    state.onRenderProcessGone = { crashed ->
                        onRendererLost(if (crashed) RENDER_REASON_CRASHED else RENDER_REASON_KILLED)
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
                    // KUN --> Stop the renderer listeners before the WebView goes away, and do not
                    // script a document whose renderer is already gone (only destroy it).
                    clearRenderProcessClient(webView)
                    state?.onRenderProcessGone = null
                    if (state?.rendererDead != true) state?.clear(webView)
                    // KUN <--
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
            // KUN --> `key(webViewGeneration.intValue)`, opened above, closes here.
        }
        // KUN <--
        // KUN -->
        // A render failure is written into the page by `showRenderNotice` (see its KDoc for why it
        // is not a Compose overlay), so nothing is drawn here.
        // KUN <--
    }
}

// KUN -->
/**
 * How long a freshly created WebView has to prove it can paint: its shell's `onPageFinished` (the
 * document loaded) and, if a payload was dispatched to it, one render reaching `contentReady`.
 */
private const val RENDER_NOT_READY_TIMEOUT_MS = 3_000L

/** Granularity of the readiness poll; the shell's `onPageFinished` is not reported to Compose. */
private const val RENDER_READY_POLL_MS = 100L

private const val RENDER_NOTICE_ID = "komikun-render-error"

/**
 * Compose failure surface for a WebView that cannot show the entry at all.
 *
 * It is composed *instead of* the `AndroidView`, because `AndroidView` interop `View`s are children
 * of the Compose host and are drawn *after* the Compose canvas: a `Text` composed under the
 * `WebView` is invisible (verified on device — composed, laid out, covered). A broken WebView must
 * therefore never be composed at all, which is also why the message cannot be an overlay.
 */
@Composable
private fun RenderFailureSurface(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(KUNMR.strings.dictionary_retry))
        }
    }
}

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

/**
 * Listen for a renderer that stops responding, API 29+.
 *
 * `WebViewRenderProcessClient` is only the *unresponsiveness* half: renderer death always arrives
 * through the shell's `WebViewClient.onRenderProcessGone` (API 26+, and the only report of a dead
 * renderer at any level), which calls the same handler through
 * [DictionaryWebViewState.onRenderProcessGone]. Below API 29 no client is registered at all, so a
 * hung renderer there is left to the ready backstop and no 29+ API is touched.
 */
private fun installRenderProcessClient(webView: WebView, onRendererLost: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    webView.webViewRenderProcessClient = object : WebViewRenderProcessClient() {
        override fun onRenderProcessUnresponsive(
            view: WebView,
            renderer: WebViewRenderProcess?,
        ) {
            // An unresponsive renderer is already unrecoverable for this view: nothing can be
            // scripted into it again, so recreating the WebView is the only way back to a page.
            onRendererLost(RENDER_REASON_UNRESPONSIVE)
        }

        override fun onRenderProcessResponsive(
            view: WebView,
            renderer: WebViewRenderProcess?,
        ) {
            // The renderer answered again before it was declared lost: nothing to repair, but the
            // pair of lines is what tells a "hung then recovered" report from a crash.
            Log.i("DictionaryRender", "js_render_responsive")
        }
    }
}

/** Unregister [installRenderProcessClient]'s client before the WebView is cleared and destroyed. */
private fun clearRenderProcessClient(webView: WebView) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching { webView.webViewRenderProcessClient = null }
}
// KUN <--
