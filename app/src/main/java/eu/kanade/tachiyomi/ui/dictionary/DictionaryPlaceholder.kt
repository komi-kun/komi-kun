// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import mihon.core.dictionary.model.DictionaryPaths
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * What the shared [DictionaryEntryWebView] surface says when it has no entries to render.
 *
 * The renderer prints this verbatim (`renderer.js`: `empty.textContent = payload.placeholder`), so
 * a blank surface is only correct while there is nothing to report. [None] — the sole state with
 * no text — is unreachable once a lookup has finished without content, which is what keeps a
 * first-run tab (or a query with no hits) from going blank.
 */
internal sealed interface DictionaryPlaceholder {
    /** No lookup yet, or one in flight (the WebView draws its own spinner meanwhile). */
    data object None : DictionaryPlaceholder

    /** No dictionary is installed (or none is enabled), so no lookup can ever hit. */
    data object NoDictionaries : DictionaryPlaceholder

    /** The lookup finished and [query] matched nothing. */
    data class NoResults(val query: String) : DictionaryPlaceholder
}

/**
 * The single empty-surface decision, shared by the reader popup and the dictionary tab (BUG 2a):
 * "no dictionaries installed" and "this word is in no installed dictionary" are different states
 * and neither may resolve to a blank surface.
 *
 * [hasContent] is whether the active frame actually draws something — for the tab that includes
 * the kanji entry JSONs a kanji lookup produces with an empty term-result list.
 */
internal fun dictionaryPlaceholder(
    hasDictionaries: Boolean,
    loading: Boolean,
    query: String?,
    hasContent: Boolean,
): DictionaryPlaceholder = when {
    !hasDictionaries -> DictionaryPlaceholder.NoDictionaries
    !loading && query != null && !hasContent -> DictionaryPlaceholder.NoResults(query)
    else -> DictionaryPlaceholder.None
}

/** Whether a lookup could hit anything: any of the four dictionary types has a directory. */
internal fun DictionaryPaths.hasAnyDictionaries(): Boolean =
    termPaths.isNotEmpty() || freqPaths.isNotEmpty() || pitchPaths.isNotEmpty() || kanjiPaths.isNotEmpty()

/** Resource to render for this state; `null` only for [DictionaryPlaceholder.None]. */
internal val DictionaryPlaceholder.resource: StringResource?
    get() = when (this) {
        DictionaryPlaceholder.None -> null
        DictionaryPlaceholder.NoDictionaries -> KUNMR.strings.dictionary_no_dictionaries
        is DictionaryPlaceholder.NoResults -> KUNMR.strings.dictionary_no_results
    }

/** Format arguments for [resource] (the query for [DictionaryPlaceholder.NoResults]). */
internal val DictionaryPlaceholder.args: List<Any>
    get() = when (this) {
        is DictionaryPlaceholder.NoResults -> listOf(query)
        else -> emptyList()
    }

/** The text handed to the renderer's `placeholder`; empty only for [DictionaryPlaceholder.None]. */
@Composable
internal fun DictionaryPlaceholder.text(): String {
    val resource = resource ?: return ""
    return stringResource(resource, *args.toTypedArray())
}
// KUN <--
