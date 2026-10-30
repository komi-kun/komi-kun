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
 * no text — is therefore reachable only when a lookup is in flight (the WebView is cleared and a
 * progress indicator covers it) or when the frame already draws entries; every other state says
 * something. [Idle] is what keeps a tab the user has not searched yet — the pane that used to be
 * blank with the text still sitting in the search field — from saying nothing at all.
 */
internal sealed interface DictionaryPlaceholder {
    /** Nothing has been looked up yet (or the query is blank): invite the user to search. */
    data object Idle : DictionaryPlaceholder

    /** A lookup is in flight (the caller draws a progress indicator) or the frame draws content. */
    data object None : DictionaryPlaceholder

    /** No dictionary is installed (or none is enabled), so no lookup can ever hit. */
    data object NoDictionaries : DictionaryPlaceholder

    /** The lookup finished and [query] matched nothing. */
    data class NoResults(val query: String) : DictionaryPlaceholder
}

/**
 * The single empty-surface decision, shared by the reader popup and the dictionary tab (BUG 2a):
 * "nothing searched yet", "no dictionaries installed" and "this word is in no installed
 * dictionary" are different states and none of them may resolve to a blank surface.
 *
 * [hasContent] is whether the active frame actually draws something — which includes the kanji
 * entry JSONs a kanji lookup produces with an empty term-result list (see
 * [hasRenderableContent]).
 */
internal fun dictionaryPlaceholder(
    hasDictionaries: Boolean,
    loading: Boolean,
    query: String?,
    hasContent: Boolean,
): DictionaryPlaceholder = when {
    !hasDictionaries -> DictionaryPlaceholder.NoDictionaries
    loading -> DictionaryPlaceholder.None
    query.isNullOrBlank() -> DictionaryPlaceholder.Idle
    !hasContent -> DictionaryPlaceholder.NoResults(query)
    else -> DictionaryPlaceholder.None
}

/**
 * Whether [this] frame draws anything: term results, or the entry JSONs a kanji lookup produces
 * with an empty term-result list. Both surfaces must use this — testing `results` alone reported a
 * kanji-only frame as "No results" while the renderer was drawing it.
 */
internal fun LookupFrame?.hasRenderableContent(): Boolean =
    this != null && (results.isNotEmpty() || !entryJsons.isNullOrEmpty())

/** Whether a lookup could hit anything: any of the four dictionary types has a directory. */
internal fun DictionaryPaths.hasAnyDictionaries(): Boolean =
    termPaths.isNotEmpty() || freqPaths.isNotEmpty() || pitchPaths.isNotEmpty() || kanjiPaths.isNotEmpty()

/** Resource to render for this state; `null` only for [DictionaryPlaceholder.None]. */
internal val DictionaryPlaceholder.resource: StringResource?
    get() = when (this) {
        DictionaryPlaceholder.None -> null
        DictionaryPlaceholder.Idle -> KUNMR.strings.dictionary_idle_hint
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
