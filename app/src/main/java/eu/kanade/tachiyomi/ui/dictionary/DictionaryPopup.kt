// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import eu.kanade.tachiyomi.ui.reader.viewer.popup.popupTopFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.getDictionaryPaths
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.KanaConverter
import mihon.core.dictionary.ui.LookupPath
import mihon.core.dictionary.ui.TabInfo
import mihon.core.dictionary.ui.buildKanjiEntryJsonStrings
import mihon.core.dictionary.ui.orderLookupResultsForDisplay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/** One frame (term + results) of the recursive-lookup path (02 §3.2). */
internal data class LookupFrame(
    val query: String,
    val results: List<LookupResult>,
    val styles: List<DictionaryStyle>,
    val mediaDataUris: Map<String, String>,
    val existingExpressions: Set<String> = emptySet(),
    // KUN -->
    val entryJsons: List<String>? = null,
    // KUN <--
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
    // KUN -->
    // The popup only ever renders inside the reader's OCR overlay; reading the reader preference
    // lets it keep the volume keys out of the way while that overlay is on.
    val readerPreferences = remember(context) { context.appGraph.readerPreferences }
    // KUN <--
    val scope = rememberCoroutineScope()

    // KUN --> Path semantics (browser history): tapping a tab only moves the active pointer; a new
    // lookup truncates everything after the active entry before appending. See [LookupPath].
    var path by remember { mutableStateOf(LookupPath<LookupFrame>()) }
    var loading by remember { mutableStateOf(false) }

    suspend fun performLookup(query: String, resetPath: Boolean, lookupType: String? = null) {
        loading = true
        val frame = withContext(Dispatchers.Default) {
            val paths = getDictionaryPaths(context, profile)
            // KUN --> Kanji lookup path
            if (lookupType == "kanji" || (query.length == 1 && isKanji(query.first()) && paths.kanjiPaths.isNotEmpty() && lookupType != "term")) {
                val kanjiChar = query.first().toString()
                val kanjiResult = repository.queryKanji(kanjiChar)
                if (kanjiResult != null && kanjiResult.entries.isNotEmpty()) {
                    val entryJsons = buildKanjiEntryJsonStrings(kanjiResult)
                    val mediaDataUris = withContext(Dispatchers.IO) {
                        repository.loadMediaAsync(emptyList(), kanjiResult)
                    }
                    return@withContext LookupFrame(
                        query = query,
                        results = emptyList(),
                        styles = emptyList(),
                        mediaDataUris = mediaDataUris,
                        entryJsons = entryJsons,
                    )
                }
            }
            // KUN <--
            val lookup = repository.lookup(query, paths, profile.languageCode)
            val ordered = orderLookupResultsForDisplay(
                lookup.results,
                profile,
                context,
                displayNameProvider = { prefs.getDisplayName(it) },
            )
            // KUN --> Fallback to kanji if single kanji character yielded no term results
            if (ordered.isEmpty() && query.length == 1 && isKanji(query.first()) && paths.kanjiPaths.isNotEmpty()) {
                val kanjiResult = repository.queryKanji(query.first().toString())
                if (kanjiResult != null && kanjiResult.entries.isNotEmpty()) {
                    val entryJsons = buildKanjiEntryJsonStrings(kanjiResult)
                    val mediaDataUris = withContext(Dispatchers.IO) {
                        repository.loadMediaAsync(emptyList(), kanjiResult)
                    }
                    return@withContext LookupFrame(
                        query = query,
                        results = emptyList(),
                        styles = emptyList(),
                        mediaDataUris = mediaDataUris,
                        entryJsons = entryJsons,
                    )
                }
            }
            // KUN --> Compound word breakdown for multi-word / compound expressions
            val breakdown = findCompoundBreakdown(query, ordered, paths, repository, profile.languageCode)
            val finalResults = if (breakdown.isNotEmpty()) ordered + breakdown else ordered
            val mediaDataUris = if (finalResults.isNotEmpty()) {
                withContext(Dispatchers.IO) { repository.loadMediaAsync(finalResults) }
            } else {
                emptyMap()
            }
            // KUN <--
            LookupFrame(query, finalResults, lookup.styles, mediaDataUris)
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
        if (frame.results.isNotEmpty()) {
            scope.launch {
                val media = withContext(Dispatchers.IO) { repository.loadMediaAsync(frame.results) }
                if (media.isNotEmpty()) {
                    path = path.copy(
                        entries = path.entries.map { if (it === frame) it.copy(mediaDataUris = media) else it },
                    )
                }
            }
        }
    }

    LaunchedEffect(request.query, profile.id) {
        if (visible && request.query.isNotBlank()) {
            val query = if ((profile.languageCode == "ja" || profile.languageCode.isEmpty()) && prefs.autoKanaConversion().get()) {
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
        !hasDictionaries -> stringResource(KUNMR.strings.dictionary_no_dictionaries)
        !loading && current != null && current.results.isEmpty() ->
            stringResource(KUNMR.strings.dictionary_no_results, current.query)
        else -> ""
    }

    val bg = MaterialTheme.colorScheme.surface
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val popupWidthPx = with(density) { popupWidth.toPx() }
    val popupHeightPx = with(density) { popupHeight.toPx() }

    // KUN --> Swipe-to-dismiss gesture support
    val swipeToDismiss = prefs.popupSwipeToDismiss().get()
    val thresholdPx = with(density) { prefs.popupSwipeThreshold().get().dp.toPx() }
    val dragOffsetY = remember { Animatable(0f) }
    // KUN <--

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
                    // KUN --> The popup is anchored by its top-left. Its top edge is chosen so the
                    // popup sits below the block when the lower region has room, and above it when
                    // the block is low on the screen (see popupSide) - the bottom-edge case that
                    // used to force a degenerate zoom. Then it is clamped fully on screen.
                    val live = popupAnchor.rect.value
                    val anchorX = live?.left ?: request.anchorX
                    val anchorY = if (live != null && screenHeightPx > 0f) {
                        popupTopFor(live.top, live.bottom, screenHeightPx, popupHeightPx)
                    } else {
                        live?.bottom ?: request.anchorY
                    }
                    // KUN <--
                    IntOffset(
                        clampToScreen(anchorX, popupWidthPx, screenWidthPx),
                        clampToScreen(anchorY, popupHeightPx, screenHeightPx) + dragOffsetY.value.roundToInt(),
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
                .pointerInput(swipeToDismiss) {
                    if (swipeToDismiss) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffsetY.value > thresholdPx) {
                                    onDismiss()
                                } else {
                                    scope.launch { dragOffsetY.animateTo(0f) }
                                }
                            },
                            onDragCancel = { scope.launch { dragOffsetY.animateTo(0f) } },
                            onVerticalDrag = { change, dragAmount ->
                                if (dragAmount > 0 || dragOffsetY.value > 0f) {
                                    val newOffset = (dragOffsetY.value + dragAmount).coerceAtLeast(0f)
                                    scope.launch { dragOffsetY.snapTo(newOffset) }
                                    change.consume()
                                }
                            },
                        )
                    } else {
                        detectTapGestures { }
                    }
                },
            color = bg,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // KUN --> Visual drag handle indicator for swipe-to-dismiss
                if (swipeToDismiss) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp, bottom = 2.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .align(Alignment.CenterHorizontally)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
                // KUN <--
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
                            text = stringResource(KUNMR.strings.dictionary_selection_hint),
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
                                stringResource(KUNMR.strings.dictionary_selection_hint_dismiss),
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
                        // KUN -->
                        entryJsons = current?.entryJsons,
                        // KUN <--
                        customCss = prefs.customCss().get(),
                        modifier = Modifier.fillMaxSize(),
                        // KUN -->
                        onRecursiveLookup = { query, _, _, _, _, type ->
                            if (query.isNotBlank()) scope.launch { performLookup(query, resetPath = false, lookupType = type) }
                        },
                        // KUN <--
                        onTabSelect = { index -> path = path.select(index) },
                        onBack = { if (path.canGoBack) path = path.back() else onDismiss() },
                        isLoading = loading,
                        // KUN --> Give the entry WebView focus so long-press starts text selection.
                        requestFocusOnMount = true,
                        // KUN -->
                        // Keep the volume keys with the system while the reader's OCR overlay is on:
                        // this popup lives inside that overlay, and there the user is listening to
                        // word audio rather than scrolling the entry.
                        volumeKeyNavigationAllowed = { !readerPreferences.ocrOverlayEnabled.get() },
                        // KUN <--
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

// KUN -->
/** Scans subsequent substrings to discover sub-word / compound components for multi-character words. */
internal suspend fun findCompoundBreakdown(
    query: String,
    primaryResults: List<LookupResult>,
    paths: DictionaryPaths,
    repository: DictionaryRepository,
    languageCode: String,
): List<LookupResult> {
    if (query.length < 3) return emptyList()
    val existingKeys = primaryResults.map { it.term.expression to it.term.reading }.toMutableSet()
    val breakdown = mutableListOf<LookupResult>()

    for (startIdx in 1 until query.length) {
        val sub = query.substring(startIdx)
        if (sub.length < 2 && (sub.isEmpty() || !isKanji(sub.first()))) continue
        val subLookup = repository.lookup(sub, paths, languageCode)
        for (res in subLookup.results) {
            val key = res.term.expression to res.term.reading
            if (key !in existingKeys && res.term.glossaries.isNotEmpty()) {
                existingKeys.add(key)
                breakdown.add(res)
                if (breakdown.size >= 4) return breakdown
            }
        }
    }
    return breakdown
}

internal fun isKanji(c: Char): Boolean {
    val code = c.code
    return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
}
// KUN <--

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
