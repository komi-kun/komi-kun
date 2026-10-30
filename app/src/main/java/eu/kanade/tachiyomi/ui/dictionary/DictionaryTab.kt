// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import mihon.app.di.appGraph
import mihon.core.dictionary.ui.KanaConverter
import mihon.core.dictionary.ui.KanaInputConversion
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
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        val focusManager = LocalFocusManager.current

        var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
        val profile = remember { prefs.profileStore.getActiveProfile() }
        val autoKanaConversion by prefs.autoKanaConversion().collectAsState()
        val isJapanese = profile.languageCode == "ja" || profile.languageCode.isEmpty()
        val autoKana = isJapanese && autoKanaConversion

        // KUN -->
        val lookupState = rememberDictionaryLookupState(profile, logTag = "tab")

        BackHandler(enabled = lookupState.canGoBack) {
            lookupState.back()
        }

        var lastSubmittedQuery by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(lookupState.current?.query) {
            val activeQuery = lookupState.current?.query
            if (activeQuery != null && activeQuery != textFieldValue.text) {
                // If a lookup completes while the user is typing a new query in the search field,
                // do not clobber their in-progress input.
                if (!lookupState.loading || textFieldValue.text == lastSubmittedQuery || textFieldValue.text.isEmpty()) {
                    textFieldValue = TextFieldValue(activeQuery, TextRange(activeQuery.length))
                    lastSubmittedQuery = activeQuery
                }
            }
        }

        fun search(query: String) {
            if (query.isBlank()) return
            focusManager.clearFocus()
            val effective = if (autoKana) {
                KanaConverter.convertAlphabeticToKana(query)
            } else {
                query
            }
            lastSubmittedQuery = effective
            if (effective != textFieldValue.text) {
                textFieldValue = TextFieldValue(effective, TextRange(effective.length))
            }
            lookupState.lookup(effective, resetPath = true)
        }
        // KUN <--

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { newVal ->
                    // KUN -->
                    // Convert only when the edit is ours to convert: while an IME composes
                    // (composition != null) the IME owns the text and a write-back would cancel
                    // its composition on every keystroke. KanaInputConversion is pure, so the rule
                    // is unit-tested away from Compose.
                    val converted = if (autoKana) {
                        KanaInputConversion.convert(
                            newText = newVal.text,
                            cursor = newVal.selection.start,
                            previousText = textFieldValue.text,
                            isComposing = newVal.composition != null,
                        )
                    } else {
                        null
                    }
                    textFieldValue = if (converted != null) {
                        TextFieldValue(converted.text, TextRange(converted.cursor))
                    } else {
                        newVal
                    }
                    // KUN <--
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
                                    // KUN -->
                                    lookupState.clear()
                                    // KUN <--
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
            // KUN -->
            DictionaryLookupPane(
                state = lookupState,
                profile = profile,
                modifier = Modifier.weight(1f),
                onRecursiveLookupInitiated = { focusManager.clearFocus() },
                volumeKeyNavigationAllowed = { false },
            )
            // KUN <--
        }
    }
}
