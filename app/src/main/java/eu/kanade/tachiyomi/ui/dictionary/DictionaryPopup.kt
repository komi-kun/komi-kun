// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import mihon.core.dictionary.ui.LookupPath
import mihon.core.dictionary.ui.TabInfo
import mihon.core.dictionary.ui.orderLookupResultsForDisplay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/** One frame (term + results) of the recursive-lookup path (02 §3.2). */
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
 * It is self-contained: it owns the lookup path, performs lookups through the
 * [mihon.core.dictionary.repository.DictionaryRepository], and renders results via
 * [DictionaryEntryWebView]. Recursive lookup follows browser-history (path) semantics over a single
 * tab trail — see [LookupPath] and docs/design/yomitan-spec/07-search-ux.md §f.
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

    // KUN --> Path semantics (browser history): tapping a tab only moves the active pointer; a new
    // lookup truncates everything after the active entry before appending. See [LookupPath].
    var path by remember { mutableStateOf(LookupPath<LookupFrame>()) }
    var loading by remember { mutableStateOf(false) }

    suspend fun performLookup(query: String, resetPath: Boolean) {
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
        if (resetPath) {
            path = path.start(frame)
        } else {
            // Truncate anything after the active tab, then append the new term (path semantics).
            path = path.push(frame)
        }
        // KUN <--
        loading = false
        // Media loads off the critical path (02 §3.5).
        scope.launch {
            val media = withContext(Dispatchers.IO) { repository.loadMediaAsync(frame.results) }
            if (media.isNotEmpty()) {
                path = path.copy(
                    entries = path.entries.map { if (it === frame) it.copy(mediaDataUris = media) else it },
                )
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
            performLookup(query, resetPath = true)
        }
    }

    if (!visible) return

    // System back closes the popup instead of leaving the reader (BUG 2c).
    BackHandler { onDismiss() }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val current = path.active
    val tabs = path.entries.mapIndexed { index, frame ->
        TabInfo(frame.query.take(16), index == path.activeIndex)
    }
    // KUN --> Size is a percentage of an adaptive, screen-relative baseline (see
    // DictionaryPopupSizing), so a tablet gets a bigger popup by default and the "Popup size"
    // preference scales it everywhere.
    val popupSize = remember(
        context,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        DictionaryPopupSizing.resolve(
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            percent = prefs.popupSizePercent().get(),
            overrideWidthDp = prefs.popupWidth().get(),
            overrideHeightDp = prefs.popupHeight().get(),
        )
    }
    val popupWidth = popupSize.widthDp.dp
    val popupHeight = popupSize.heightDp.dp
    // KUN <--

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
                // KUN -->
                // Discoverability affordance: the entry text *is* selectable (the WebView's own
                // long-press starts a selection and shows the system selection toolbar), but the
                // popup gave no sign of it, so the reasonable conclusion was that nothing could be
                // selected. Show a one-line hint until the user dismisses it.
                var selectionHintDismissed by
                    remember { mutableStateOf(prefs.selectionHintDismissed().get()) }
                if (!selectionHintDismissed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 2.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(KMR.strings.dictionary_selection_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                selectionHintDismissed = true
                                prefs.selectionHintDismissed().set(true)
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Close,
                                contentDescription =
                                stringResource(KMR.strings.dictionary_selection_hint_dismiss),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                // KUN <--
                // KUN -->
                // The recursive-lookup stack (tabs) is drawn by the renderer itself: `renderer.js`
                // emits a `.lookup-tabs` bar of `.lookup-tab` pills whenever more than one frame is
                // on the stack, and those pills are the ones that actually navigate (async
                // `onTabSelect`). A second, native Compose tab row used to be rendered here, which
                // duplicated the pills with no working handler of its own, so it was removed.
                // KUN <--

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
                            if (query.isNotBlank()) scope.launch { performLookup(query, resetPath = false) }
                        },
                        onTabSelect = { index -> path = path.select(index) },
                        onBack = { if (path.canGoBack) path = path.back() else onDismiss() },
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
