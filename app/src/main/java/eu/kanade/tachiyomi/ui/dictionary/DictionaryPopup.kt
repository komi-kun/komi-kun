// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.getDictionaryPaths
import mihon.core.dictionary.ui.KanaConverter
import mihon.core.dictionary.ui.TabInfo
import mihon.core.dictionary.ui.orderLookupResultsForDisplay
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/** One frame of the lookup-history stack (02 §3.2). */
internal data class LookupFrame(
    val query: String,
    val results: List<LookupResult>,
    val styles: List<DictionaryStyle>,
    val mediaDataUris: Map<String, String>,
    val existingExpressions: Set<String> = emptySet(),
)

/**
 * The dictionary popup (02 §3.2), extracted from Chimahon's `OcrLookupPopup`.
 *
 * It is self-contained: it owns the lookup-history stack, performs lookups through the
 * [mihon.core.dictionary.repository.DictionaryRepository], and renders results via
 * [DictionaryEntryWebView]. Recursive lookup is simplified to a single history stack of tabs
 * (docs/design/yomitan-spec/07-search-ux.md §f).
 *
 * Positioned absolutely (no `Popup` window) so it composites with the WebGPU external surface.
 */
@Composable
fun DictionaryPopup(
    visible: Boolean,
    request: PopupRequest,
    profile: DictionaryProfile,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { context.appGraph.dictionaryRepository }
    val prefs = remember(context) { context.appGraph.dictionaryPreferences }
    val popupAnchor = remember(context) { context.appGraph.readerOcrPopupAnchor }
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf<List<LookupFrame>>(emptyList()) }
    var activeIndex by remember { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }

    suspend fun performLookup(query: String, replaceStack: Boolean) {
        loading = true
        val frame = withContext(Dispatchers.Default) {
            val paths = getDictionaryPaths(context, profile)
            val lookup = repository.lookup(query, paths, profile.languageCode)
            val ordered = orderLookupResultsForDisplay(
                lookup.results,
                profile,
                context,
                displayNameProvider = { prefs.getDisplayName(it) },
            )
            LookupFrame(query, ordered, lookup.styles, emptyMap())
        }
        if (replaceStack) {
            stack = listOf(frame)
            activeIndex = 0
        } else {
            stack = stack + frame
            activeIndex = stack.lastIndex
        }
        loading = false
        // Media loads off the critical path (02 §3.5).
        scope.launch {
            val media = withContext(Dispatchers.IO) { repository.loadMediaAsync(frame.results) }
            if (media.isNotEmpty()) {
                stack = stack.map { if (it === frame) it.copy(mediaDataUris = media) else it }
            }
        }
    }

    LaunchedEffect(request.query, profile.id) {
        if (visible && request.query.isNotBlank()) {
            val query = if (profile.languageCode == "ja" && prefs.autoKanaConversion().get()) {
                KanaConverter.convertAlphabeticToKana(request.query)
            } else {
                request.query
            }
            performLookup(query, replaceStack = true)
        }
    }

    if (!visible) return

    // System back closes the popup instead of leaving the reader (BUG 2c).
    BackHandler { onDismiss() }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeight = configuration.screenHeightDp
    val current = stack.getOrNull(activeIndex)
    val tabs = stack.mapIndexed { index, frame -> TabInfo(frame.query.take(16), index == activeIndex) }
    val popupWidth = prefs.popupWidth().get().dp
    val popupHeight = resolvePopupHeight(prefs.popupHeight().get(), screenHeight)

    // A visible empty state instead of a blank/black box: distinguish "no dictionaries installed"
    // from "this word is in no installed dictionary" (BUG 2a).
    val hasDictionaries = getDictionaryPaths(context, profile).let {
        it.termPaths.isNotEmpty() || it.freqPaths.isNotEmpty() ||
            it.pitchPaths.isNotEmpty() || it.kanjiPaths.isNotEmpty()
    }
    val placeholder = when {
        !hasDictionaries -> stringResource(KMR.strings.dictionary_no_dictionaries)
        !loading && current != null && current.results.isEmpty() ->
            stringResource(KMR.strings.dictionary_no_results, current.query)
        else -> ""
    }

    val bg = MaterialTheme.colorScheme.surface
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val popupWidthPx = with(density) { popupWidth.toPx() }
    val popupHeightPx = with(density) { popupHeight.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Non-modal (plan 04 §2.1, revised): there is deliberately NO full-screen scrim here. A
        // scrim would swallow every touch and stop the page from scrolling/zooming/paging while the
        // popup is open. Instead the reader observes taps itself (a passive,
        // non-consuming detector) and dismisses on an outside tap, so the page stays fully usable
        // and the popup keeps tracking its block (the anchor below is refreshed every frame).
        Surface(
            modifier = Modifier
                .offset {
                    // Prefer the OCR host's live anchor so the popup follows the page; fall back to
                    // the anchor captured when the lookup opened.
                    val live = popupAnchor.rect.value
                    val anchorX = live?.left ?: request.anchorX
                    val anchorY = live?.bottom ?: request.anchorY
                    IntOffset(
                        clampToScreen(anchorX, popupWidthPx, screenWidthPx),
                        clampToScreen(anchorY, popupHeightPx, screenHeightPx),
                    )
                }
                .width(popupWidth)
                .height(popupHeight)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .background(bg)
                // KUN --> Publish the popup's own bounds so the reader can tell an outside tap from
                // a touch on the popup (used by the non-consuming outside-tap dismissal).
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInRoot()
                    popupAnchor.popupBounds.value =
                        RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
                // KUN <--
                // Consume taps on the popup chrome without dismissing; only outside taps dismiss.
                .pointerInput(Unit) { detectTapGestures { } },
            color = bg,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (tabs.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (tab.active) {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    DictionaryEntryWebView(
                        results = current?.results.orEmpty(),
                        styles = current?.styles.orEmpty(),
                        mediaDataUris = current?.mediaDataUris.orEmpty(),
                        placeholder = placeholder,
                        activeProfile = profile,
                        fontSize = prefs.fontSize().get(),
                        existingExpressions = current?.existingExpressions.orEmpty(),
                        tabs = tabs,
                        customCss = prefs.customCss().get(),
                        modifier = Modifier.fillMaxSize(),
                        onRecursiveLookup = { query, _, _, _, _, _ ->
                            if (query.isNotBlank()) scope.launch { performLookup(query, replaceStack = false) }
                        },
                        onTabSelect = { index -> if (index in stack.indices) activeIndex = index },
                        onBack = { if (activeIndex > 0) activeIndex -= 1 else onDismiss() },
                        isLoading = loading,
                        // KUN --> Give the entry WebView focus so long-press starts text selection.
                        requestFocusOnMount = true,
                        // KUN --> OCR context so the renderer's `addToAnki` builds sentence/cloze fields.
                        ankiSentence = request.sentence,
                        ankiSentenceOffset = request.sentenceOffset,
                        // KUN <--
                    )
                    // KUN --> A lookup in flight has no results yet; show progress rather than a
                    // blank (black) box while the repository works.
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    // KUN <--
                }
            }
        }
    }
}

private fun resolvePopupHeight(configured: Int, screenHeightDp: Int): androidx.compose.ui.unit.Dp {
    val maxHeight = (screenHeightDp - 24).coerceAtLeast(120)
    return configured.coerceAtMost(maxHeight).dp
}

/** Clamps the popup's anchor so the popup is always fully on screen. */
private fun clampToScreen(anchor: Float, sizePx: Float, screenPx: Float): Int {
    val max = (screenPx - sizePx).roundToInt().coerceAtLeast(0)
    return anchor.roundToInt().coerceIn(0, max)
}

/** Position (px) + lookup payload for the popup (contract §2.3 seam DTO adapter). */
data class PopupRequest(
    val query: String,
    val sentence: String,
    val sentenceOffset: Int,
    val anchorX: Float,
    val anchorY: Float,
    val vertical: Boolean,
    val language: String,
)
