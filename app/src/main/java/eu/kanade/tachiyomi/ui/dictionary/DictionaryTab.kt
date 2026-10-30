// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.home.DictionaryTabNavigationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.dictionary.model.getDictionaryPaths
import mihon.core.dictionary.ui.KanaConverter
import mihon.core.dictionary.ui.buildKanjiEntryJsonStrings
import mihon.core.dictionary.ui.orderLookupResultsForDisplay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Full-screen dictionary search tab (02 §6.3), the manga-only adaptation of Chimahon's
 * `DictionaryTab`: search field + lookup stack + the shared [DictionaryEntryWebView]. The
 * camera-OCR button is dropped.
 *
 * Lookup history is kept in-memory (the popup's tab stack reuses the same mechanism); persistent
 * search history can be layered on later without changing this contract.
 */
data object DictionaryTab : Tab {

    /**
     * Part of the navigation only while the user has opted into dictionaries/OCR
     * (`DictionaryOcrPreferences`). HomeScreen's single `fastFilter { it.isEnabled() }` applies this
     * to both the bottom bar and the navigation rail, so the tab disappears from either layout at
     * the same moment, and comes back the moment the settings switch is turned on.
     *
     * The rule itself lives in `DictionaryTabNavigationPolicy.isTabVisible`, which HomeScreen's
     * filter and its direct-tab fallback share (and which is unit-tested); this is the pref read.
     */
    @Composable
    override fun isEnabled(): Boolean {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val enabled = remember {
            context.appGraph.dictionaryOcrPreferences.enabled.asState(scope)
        }.value
        return DictionaryTabNavigationPolicy.isTabVisible(enabled)
    }

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 4u,
            title = stringResource(KUNMR.strings.label_dictionary),
            icon = rememberVectorPainter(MaterialSymbols.Rounded.Search),
        )

    @Composable
    override fun Content() {
        val context = LocalContext.current
        // KUN -->
        // Ensure volume keys adjust media (audio) volume while viewing the dictionary tab.
        androidx.compose.runtime.DisposableEffect(context) {
            val activity = context as? android.app.Activity
            val prevStream = activity?.volumeControlStream
            activity?.volumeControlStream = android.media.AudioManager.STREAM_MUSIC
            onDispose {
                if (prevStream != null) {
                    activity.volumeControlStream = prevStream
                }
            }
        }
        // KUN <--
        val repository = remember(context) { context.appGraph.dictionaryRepository }
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

        var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
        var frame by remember { mutableStateOf<LookupFrame?>(null) }
        var loading by remember { mutableStateOf(false) }
        val profile = remember { prefs.profileStore.getActiveProfile() }
        val autoKanaConversion by prefs.autoKanaConversion().collectAsState()
        val isJapanese = profile.languageCode == "ja" || profile.languageCode.isEmpty()
        val autoKana = isJapanese && autoKanaConversion

        fun search(query: String) {
            if (query.isBlank()) return
            focusManager.clearFocus()
            val effective = if (autoKana) {
                KanaConverter.convertAlphabeticToKana(query)
            } else {
                query
            }
            if (effective != textFieldValue.text) {
                textFieldValue = TextFieldValue(effective, TextRange(effective.length))
            }
            // KUN -->
            // Read once, outside the lookup, so the diagnostics line can report what the lookup
            // was actually allowed to search (it is cached and cheap; the composable already calls
            // it on every pass).
            val paths = getDictionaryPaths(context, profile)
            val startedAt = SystemClock.elapsedRealtime()
            // KUN <--
            loading = true
            scope.launch {
                // KUN -->
                // `loading` is all that stands between the user and a permanently blank pane: the
                // WebView is cleared (alpha 0) while it is set and only a finished lookup turns it
                // back on, so a cancellation (leaving the tab) or a throw used to strand the flag
                // at `true` — no entries, no spinner, no message. The `finally` is the guarantee;
                // the assignment after `frame = result` only ends the spinner before the off-path
                // media reload.
                // KUN <--
                try {
                    val result = withContext(Dispatchers.Default) {
                        // KUN --> Kanji lookup path
                        if (effective.length == 1 && isKanji(effective.first()) && paths.kanjiPaths.isNotEmpty()) {
                            val kanjiChar = effective.first().toString()
                            val kanjiResult = repository.queryKanji(kanjiChar)
                            if (kanjiResult != null && kanjiResult.entries.isNotEmpty()) {
                                val entryJsons = buildKanjiEntryJsonStrings(kanjiResult)
                                val mediaDataUris = withContext(Dispatchers.IO) {
                                    repository.loadMediaAsync(emptyList(), kanjiResult)
                                }
                                return@withContext LookupFrame(
                                    query = effective,
                                    results = emptyList(),
                                    styles = emptyList(),
                                    mediaDataUris = mediaDataUris,
                                    entryJsons = entryJsons,
                                )
                            }
                        }
                        // KUN <--
                        val lookup = repository.lookup(effective, paths, profile.languageCode)
                        val ordered = orderLookupResultsForDisplay(
                            lookup.results,
                            profile,
                            context,
                            displayNameProvider = { prefs.getDisplayName(it) },
                        )
                        // KUN --> Fallback to kanji if single kanji character yielded no term results
                        if (ordered.isEmpty() && effective.length == 1 && isKanji(effective.first()) && paths.kanjiPaths.isNotEmpty()) {
                            val kanjiResult = repository.queryKanji(effective.first().toString())
                            if (kanjiResult != null && kanjiResult.entries.isNotEmpty()) {
                                val entryJsons = buildKanjiEntryJsonStrings(kanjiResult)
                                val mediaDataUris = withContext(Dispatchers.IO) {
                                    repository.loadMediaAsync(emptyList(), kanjiResult)
                                }
                                return@withContext LookupFrame(
                                    query = effective,
                                    results = emptyList(),
                                    styles = emptyList(),
                                    mediaDataUris = mediaDataUris,
                                    entryJsons = entryJsons,
                                )
                            }
                        }
                        val breakdown = findCompoundBreakdown(effective, ordered, paths, repository, profile.languageCode)
                        val finalResults = if (breakdown.isNotEmpty()) ordered + breakdown else ordered
                        val mediaDataUris = if (finalResults.isNotEmpty()) {
                            withContext(Dispatchers.IO) { repository.loadMediaAsync(finalResults) }
                        } else {
                            emptyMap()
                        }
                        // KUN <--
                        LookupFrame(effective, finalResults, lookup.styles, mediaDataUris)
                    }
                    frame = result
                    loading = false
                    // KUN --> One line per lookup: what came back, what the lookup was allowed to
                    // search, and how long it took. The blank-pane report had no such evidence.
                    Log.i(
                        "DictionaryLookup",
                        "tab_lookup query=$effective results=${result.results.size} " +
                            "entry_jsons=${result.entryJsons?.size ?: 0} " +
                            "term_paths=${paths.termPaths.size} kanji_paths=${paths.kanjiPaths.size} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                    // KUN <--
                    // KUN --> Asynchronous media reload/update off critical path
                    if (result.results.isNotEmpty()) {
                        val media = withContext(Dispatchers.IO) { repository.loadMediaAsync(result.results) }
                        if (media.isNotEmpty() && media != result.mediaDataUris) {
                            frame = frame?.let { if (it === result) it.copy(mediaDataUris = media) else it }
                        }
                    }
                    // KUN <--
                } finally {
                    loading = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { newVal ->
                    if (autoKana && newVal.text != textFieldValue.text) {
                        if (newVal.text.length < textFieldValue.text.length) {
                            textFieldValue = newVal
                        } else {
                            val (converted, newCursor) = KanaConverter.toKanaIME(
                                newVal.text,
                                newVal.selection.start,
                            )
                            textFieldValue = TextFieldValue(
                                text = converted,
                                selection = TextRange(newCursor),
                            )
                        }
                    } else {
                        textFieldValue = newVal
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                singleLine = true,
                // KUN -->
                // A submit affordance that does not depend on the IME's Search key. The old field
                // swapped its only button for a clear (X) the moment anything was typed, so typed
                // text with a blank pane below it was a query that had never been submitted and
                // had no way to be. Search stays visible (disabled while the field is empty) and
                // the clear action is kept beside it; the IME action is untouched, and no icon
                // consumes a token from the text, so typing is unaffected.
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { search(textFieldValue.text) },
                            enabled = textFieldValue.text.isNotBlank(),
                        ) {
                            Icon(
                                MaterialSymbols.Rounded.Search,
                                contentDescription = stringResource(MR.strings.action_search),
                            )
                        }
                        if (textFieldValue.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    textFieldValue = TextFieldValue("")
                                    frame = null
                                },
                            ) {
                                Icon(
                                    MaterialSymbols.Rounded.Close,
                                    contentDescription = stringResource(MR.strings.action_close),
                                )
                            }
                        }
                    }
                },
                // KUN <--
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(textFieldValue.text) }),
            )
            Box(modifier = Modifier.weight(1f)) {
                // KUN -->
                // A first-run tab (no dictionary installed) or a query with no hits used to render a
                // blank surface because the placeholder was hard-coded to ""; share the popup's
                // decision instead. Kanji frames carry entry JSONs but no term results, and a tab
                // nobody has searched yet says how to search rather than nothing at all.
                val activeFrame = frame
                val dictionaryPaths = getDictionaryPaths(context, profile)
                val hasDictionaries = dictionaryPaths.hasAnyDictionaries()
                val hasDrawableContent = activeFrame.hasRenderableContent()
                val placeholder = dictionaryPlaceholder(
                    hasDictionaries = hasDictionaries,
                    loading = loading,
                    query = activeFrame?.query,
                    hasContent = hasDrawableContent,
                )
                // Rendered at composition time so the line reports the inputs the decision was
                // actually made from, not whatever `loading` had become by the time it ran.
                val placeholderDiagnostics = "state=$placeholder has_dictionaries=$hasDictionaries " +
                    "loading=$loading has_content=$hasDrawableContent"
                LaunchedEffect(placeholderDiagnostics) {
                    // One line per decision, not per frame.
                    Log.i("DictionaryLookup", "tab_placeholder $placeholderDiagnostics")
                }
                // KUN <--
                DictionaryEntryWebView(
                    results = frame?.results.orEmpty(),
                    styles = frame?.styles.orEmpty(),
                    mediaDataUris = frame?.mediaDataUris.orEmpty(),
                    // KUN --> A real empty state instead of the hard-coded "".
                    placeholder = placeholder.text(),
                    // KUN <--
                    activeProfile = profile,
                    // KUN -->
                    fontSize = prefs.fontSize().get(),
                    entryJsons = frame?.entryJsons,
                    customCss = prefs.customCss().get(),
                    onRecursiveLookup = { q, _, _, _, _, _ ->
                        textFieldValue = TextFieldValue(q, TextRange(q.length))
                        search(q)
                    },
                    // On the dictionary search screen, volume keys must adjust media volume (for word audio),
                    // not scroll the entry list.
                    volumeKeyNavigationAllowed = { false },
                    // KUN <--
                    isLoading = loading,
                    modifier = Modifier.fillMaxSize(),
                )
                // KUN --> A lookup in flight has no entries yet; the pane is cleared meanwhile, so
                // show progress instead of the blank it would otherwise be.
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                // KUN <--
            }
        }
    }
}
