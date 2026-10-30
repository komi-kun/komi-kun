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
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.ui.KanaConverter
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

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
    val prefs = remember(context) { context.appGraph.dictionaryPreferences }
    val popupAnchor = remember(context) { context.appGraph.readerOcrPopupAnchor }
    // KUN -->
    // The popup only ever renders inside the reader's OCR overlay; reading the reader preference
    // lets it keep the volume keys out of the way while that overlay is on.
    val readerPreferences = remember(context) { context.appGraph.readerPreferences }
    val scope = rememberCoroutineScope()
    val lookupState = rememberDictionaryLookupState(profile, logTag = "popup")

    LaunchedEffect(request.query, profile.id) {
        if (visible && request.query.isNotBlank()) {
            val query = if ((profile.languageCode == "ja" || profile.languageCode.isEmpty()) && prefs.autoKanaConversion().get()) {
                KanaConverter.convertAlphabeticToKana(request.query)
            } else {
                request.query
            }
            lookupState.lookup(query, resetPath = true)
        }
    }

    if (!visible) return

    // System back navigates back through lookup history or closes the popup when at the root.
    BackHandler {
        if (lookupState.canGoBack) {
            lookupState.back()
        } else {
            onDismiss()
        }
    }
    // KUN <--

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
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
                DictionaryLookupPane(
                    state = lookupState,
                    profile = profile,
                    modifier = Modifier.fillMaxSize(),
                    onDismiss = onDismiss,
                    requestFocusOnMount = true,
                    volumeKeyNavigationAllowed = { !readerPreferences.ocrOverlayEnabled.get() },
                    ankiSentence = request.sentence,
                    ankiSentenceOffset = request.sentenceOffset,
                    ankiScreenshotProvider = popupAnchor.screenshot.value,
                )
                // KUN <--
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
