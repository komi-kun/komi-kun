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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.dictionary.model.getDictionaryPaths
import mihon.core.dictionary.ui.KanaConverter
import mihon.core.dictionary.ui.orderLookupResultsForDisplay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Full-screen dictionary search tab (02 §6.3), the manga-only adaptation of Chimahon's
 * `DictionaryTab`: search field + lookup stack + the shared [DictionaryEntryWebView]. The
 * camera-OCR button is dropped.
 *
 * Lookup history is kept in-memory (the popup's tab stack reuses the same mechanism); persistent
 * search history can be layered on later without changing this contract.
 */
data object DictionaryTab : Tab {

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
        val repository = remember(context) { context.appGraph.dictionaryRepository }
        val prefs = remember(context) { context.appGraph.dictionaryPreferences }
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current

        var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
        var frame by remember { mutableStateOf<LookupFrame?>(null) }
        var loading by remember { mutableStateOf(false) }
        val profile = remember { prefs.profileStore.getActiveProfile() }

        fun search(query: String) {
            if (query.isBlank()) return
            focusManager.clearFocus()
            val effective = if (profile.languageCode == "ja" && prefs.autoKanaConversion().get()) {
                KanaConverter.convertAlphabeticToKana(query)
            } else {
                query
            }
            loading = true
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    val paths = getDictionaryPaths(context, profile)
                    val lookup = repository.lookup(effective, paths, profile.languageCode)
                    val ordered = orderLookupResultsForDisplay(
                        lookup.results,
                        profile,
                        context,
                        displayNameProvider = { prefs.getDisplayName(it) },
                    )
                    LookupFrame(effective, ordered, lookup.styles, emptyMap())
                }
                frame = result
                loading = false
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
                onValueChange = { textFieldValue = it },
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
                    isLoading = loading,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
