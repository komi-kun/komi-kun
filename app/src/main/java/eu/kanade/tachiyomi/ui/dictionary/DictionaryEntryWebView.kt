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
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.dictionary.anki.AnkiMiningController
import mihon.core.dictionary.anki.AnkiPreferences
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
    isLoading: Boolean = false,
    // KUN -->
    // Anki mining: when no callback is supplied, the renderer's `addToAnki` reaches
    // `AnkiCardCreator` through a default controller built from the active profile.
    onAnkiLookup: ((LookupResult, Int?, String?, String?, Boolean) -> Unit)? = null,
    // KUN <--
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.appGraph.dictionaryPreferences }
    // KUN -->
    val ankiScope = rememberCoroutineScope()
    val ankiMining = remember(context) {
        AnkiMiningController(context, AnkiPreferences(AndroidPreferenceStore(context)))
    }
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
        entryJsons, compactGlossary,
    ) {
        DictionaryRenderSignature(
            results = results, styles = styles, placeholder = placeholder, isDark = isDark,
            showFrequencyHarmonic = showFrequencyHarmonic,
            showFrequencyAverage = showFrequencyAverage,
            groupTerms = groupTerms,
            showPitchDiagram = showPitchDiagram, showPitchNumber = showPitchNumber,
            showPitchText = showPitchText, activeProfile = activeProfile, tabs = tabs,
            recursiveNavMode = recursiveNavMode, renderRecursiveChrome = true,
            wordAudioEnabled = false, wordAudioAutoplay = false,
            showNavigationButtons = showNavigationButtons, groupPitches = groupPitches,
        )
    }

    var configPayloadPair by remember { mutableStateOf<Pair<String, DictionaryRenderSignature>?>(null) }
    var entryJsonsPair by remember { mutableStateOf<Pair<List<String>, DictionaryRenderSignature>?>(null) }
    val currentConfigPayload = configPayloadPair
    val currentEntryJsons = entryJsonsPair
    LaunchedEffect(renderSignature) {
        val buildStart = SystemClock.elapsedRealtime()
        val built = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val config = buildConfigPayload(
                context, styles, emptyMap(), placeholder, isDark,
                showFrequencyHarmonic, showFrequencyAverage, groupTerms,
                showPitchDiagram, showPitchNumber, showPitchText,
                false, activeProfile, emptySet(), tabs, recursiveNavMode,
                renderRecursiveChrome = true,
                wordAudioEnabled = false,
                showNavigationButtons = showNavigationButtons,
                groupPitches = groupPitches,
                compactGlossary = compactGlossary,
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
                (webView.tag as? DictionaryWebViewState)?.let { state ->
                    state.onContentInvalidated = {
                        onContentReadyChange?.invoke(false)
                        if (hideOnContentInvalidated) webView.alpha = 0f
                    }
                    state.onContentReady = {
                        onContentReadyChange?.invoke(true)
                        webView.alpha = 1f
                    }
                    if (state.pageReady) state.flush(webView)
                }

                webView.isClickable = true
                webView.isLongClickable = true
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
                if (requestFocusOnMount) webView.requestFocus()
                webView.setOnLongClickListener { false }
                webView.setOnKeyListener { v, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && prefs.volumeKeyNavigation().get()) {
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
                                )
                            }
                        }
                    }
                }
                // KUN <--
                state.customCss = customCss
                state.fontSize = fontSize
                state.onContentInvalidated = {
                    onContentReadyChange?.invoke(false)
                    if (hideOnContentInvalidated) webView.alpha = 0f
                }
                state.onContentReady = {
                    onContentReadyChange?.invoke(true)
                    webView.alpha = 1f
                }

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
                state?.ankiJsBridge?.onAnkiLookup = null
                state?.payloadBridge?.rawPayloadJson = ""
                state?.payloadBridge?.rawEntryJsons = emptyList()
            },
        )
    }
}
