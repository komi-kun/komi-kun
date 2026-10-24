// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
     */
    @Composable
    override fun isEnabled(): Boolean {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        return remember {
            context.appGraph.dictionaryOcrPreferences.enabled.asState(scope)
        }.value
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
            loading = true
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    val paths = getDictionaryPaths(context, profile)
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
                // KUN --> Asynchronous media reload/update off critical path
                if (result.results.isNotEmpty()) {
                    val media = withContext(Dispatchers.IO) { repository.loadMediaAsync(result.results) }
                    if (media.isNotEmpty() && media != result.mediaDataUris) {
                        frame = frame?.let { if (it === result) it.copy(mediaDataUris = media) else it }
                    }
                }
                // KUN <--
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
                trailingIcon = {
                    if (textFieldValue.text.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                textFieldValue = TextFieldValue("")
                                frame = null
                            },
                        ) {
                            Icon(MaterialSymbols.Rounded.Close, contentDescription = null)
                        }
                    } else {
                        IconButton(onClick = { search(textFieldValue.text) }) {
                            Icon(MaterialSymbols.Rounded.Search, contentDescription = null)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(textFieldValue.text) }),
            )
            Box(modifier = Modifier.weight(1f)) {
                DictionaryEntryWebView(
                    results = frame?.results.orEmpty(),
                    styles = frame?.styles.orEmpty(),
                    mediaDataUris = frame?.mediaDataUris.orEmpty(),
                    placeholder = "",
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
            }
        }
    }
}
