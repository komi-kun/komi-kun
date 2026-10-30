// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.core.dictionary.anki.AnkiScreenshotProvider
import mihon.core.dictionary.model.DictionaryPaths
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.getDictionaryPaths
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.LookupPath
import mihon.core.dictionary.ui.TabInfo
import mihon.core.dictionary.ui.buildKanjiEntryJsonStrings
import mihon.core.dictionary.ui.orderLookupResultsForDisplay
import tachiyomi.core.common.util.system.logcat

/** One frame (term + results) of the recursive-lookup path (02 §3.2). */
internal data class LookupFrame(
    val query: String,
    val results: List<LookupResult>,
    val styles: List<DictionaryStyle>,
    val mediaDataUris: Map<String, String>,
    val existingExpressions: Set<String> = emptySet(),
    val entryJsons: List<String>? = null,
)

internal fun isKanji(c: Char): Boolean {
    val code = c.code
    return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
}

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

/** Executes the full dictionary lookup pipeline (kanji, term, breakdown, media). */
internal suspend fun executeDictionaryLookup(
    context: Context,
    repository: DictionaryRepository,
    prefs: DictionaryPreferences,
    profile: DictionaryProfile,
    paths: DictionaryPaths,
    query: String,
    lookupType: String? = null,
): LookupFrame {
    // Kanji lookup path
    if (lookupType == "kanji" || (query.length == 1 && isKanji(query.first()) && paths.kanjiPaths.isNotEmpty() && lookupType != "term")) {
        val kanjiChar = query.first().toString()
        val kanjiResult = repository.queryKanji(kanjiChar)
        if (kanjiResult != null && kanjiResult.entries.isNotEmpty()) {
            val entryJsons = buildKanjiEntryJsonStrings(kanjiResult)
            val mediaDataUris = withContext(Dispatchers.IO) {
                repository.loadMediaAsync(emptyList(), kanjiResult)
            }
            return LookupFrame(
                query = query,
                results = emptyList(),
                styles = emptyList(),
                mediaDataUris = mediaDataUris,
                entryJsons = entryJsons,
            )
        }
    }

    val lookup = repository.lookup(query, paths, profile.languageCode)
    val ordered = orderLookupResultsForDisplay(
        lookup.results,
        profile,
        context,
        displayNameProvider = { prefs.getDisplayName(it) },
    )
    // Fallback to kanji if single kanji character yielded no term results
    if (ordered.isEmpty() && query.length == 1 && isKanji(query.first()) && paths.kanjiPaths.isNotEmpty()) {
        val kanjiResult = repository.queryKanji(query.first().toString())
        if (kanjiResult != null && kanjiResult.entries.isNotEmpty()) {
            val entryJsons = buildKanjiEntryJsonStrings(kanjiResult)
            val mediaDataUris = withContext(Dispatchers.IO) {
                repository.loadMediaAsync(emptyList(), kanjiResult)
            }
            return LookupFrame(
                query = query,
                results = emptyList(),
                styles = emptyList(),
                mediaDataUris = mediaDataUris,
                entryJsons = entryJsons,
            )
        }
    }

    val breakdown = findCompoundBreakdown(query, ordered, paths, repository, profile.languageCode)
    val finalResults = if (breakdown.isNotEmpty()) ordered + breakdown else ordered
    val mediaDataUris = if (finalResults.isNotEmpty()) {
        withContext(Dispatchers.IO) { repository.loadMediaAsync(finalResults) }
    } else {
        emptyMap()
    }
    return LookupFrame(query, finalResults, lookup.styles, mediaDataUris)
}

/**
 * State holder managing recursive-lookup history ([LookupPath]), loading state, tab models,
 * and asynchronous execution.
 */
@Stable
internal class DictionaryLookupState(
    private val context: Context,
    private val repository: DictionaryRepository,
    private val prefs: DictionaryPreferences,
    private val coroutineScope: CoroutineScope,
    private val profileProvider: () -> DictionaryProfile,
    val logTag: String = "lookup",
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var path by mutableStateOf(LookupPath<LookupFrame>())
        internal set

    var loading by mutableStateOf(false)
        private set

    private var currentLookupJob: Job? = null

    val current: LookupFrame?
        get() = path.active

    val canGoBack: Boolean
        get() = path.canGoBack

    val tabs: List<TabInfo>
        get() = path.entries.mapIndexed { index, frame ->
            TabInfo(frame.query.take(16), index == path.activeIndex)
        }

    fun selectTab(index: Int) {
        path = path.select(index)
    }

    fun back(): Boolean {
        if (path.canGoBack) {
            path = path.back()
            return true
        }
        return false
    }

    fun clear() {
        currentLookupJob?.cancel()
        currentLookupJob = null
        loading = false
        path = LookupPath()
    }

    fun lookup(query: String, resetPath: Boolean, lookupType: String? = null) {
        if (query.isBlank()) return
        val profile = profileProvider()
        val startedAt = System.currentTimeMillis()
        currentLookupJob?.cancel()
        loading = true
        val job = coroutineScope.launch {
            try {
                var resolvedPaths = DictionaryPaths()
                val frame = withContext(defaultDispatcher) {
                    val paths = getDictionaryPaths(context, profile)
                    resolvedPaths = paths
                    executeDictionaryLookup(
                        context = context,
                        repository = repository,
                        prefs = prefs,
                        profile = profile,
                        paths = paths,
                        query = query,
                        lookupType = lookupType,
                    )
                }
                if (resetPath) {
                    path = path.start(frame)
                } else {
                    path = path.push(frame)
                }
                loading = false
                logcat(LogPriority.INFO) {
                    "${logTag}_lookup query=$query results=${frame.results.size} " +
                        "entry_jsons=${frame.entryJsons?.size ?: 0} " +
                        "term_paths=${resolvedPaths.termPaths.size} " +
                        "kanji_paths=${resolvedPaths.kanjiPaths.size} " +
                        "elapsed_ms=${System.currentTimeMillis() - startedAt}"
                }
                if (frame.results.isNotEmpty()) {
                    val media = withContext(ioDispatcher) { repository.loadMediaAsync(frame.results) }
                    if (media.isNotEmpty()) {
                        path = path.copy(
                            entries = path.entries.map { if (it === frame) it.copy(mediaDataUris = media) else it },
                        )
                    }
                }
            } finally {
                if (currentLookupJob === coroutineContext[Job]) {
                    loading = false
                }
            }
        }
        currentLookupJob = job
    }
}

@Composable
internal fun rememberDictionaryLookupState(
    profile: DictionaryProfile,
    logTag: String = "lookup",
): DictionaryLookupState {
    val context = LocalContext.current
    val repository = remember(context) { context.appGraph.dictionaryRepository }
    val prefs = remember(context) { context.appGraph.dictionaryPreferences }
    val scope = rememberCoroutineScope()
    val currentProfile by rememberUpdatedState(profile)
    return remember(context, repository, prefs, scope, logTag) {
        DictionaryLookupState(
            context = context,
            repository = repository,
            prefs = prefs,
            coroutineScope = scope,
            profileProvider = { currentProfile },
            logTag = logTag,
        )
    }
}

/**
 * Shared dictionary lookup UI pane: renders the [DictionaryEntryWebView] with recursive-lookup tabs,
 * empty-state placeholder diagnostics, and loading progress indicator.
 *
 * Shared between [DictionaryTab] and [DictionaryPopup].
 */
@Composable
internal fun DictionaryLookupPane(
    state: DictionaryLookupState,
    profile: DictionaryProfile,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onRecursiveLookupInitiated: ((String) -> Unit)? = null,
    requestFocusOnMount: Boolean = false,
    volumeKeyNavigationAllowed: () -> Boolean = { false },
    ankiSentence: String = "",
    ankiSentenceOffset: Int = -1,
    ankiScreenshotProvider: AnkiScreenshotProvider? = null,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.appGraph.dictionaryPreferences }
    val current = state.current

    val dictionaryPaths = remember(context, profile) { getDictionaryPaths(context, profile) }
    val hasDictionaries = dictionaryPaths.hasAnyDictionaries()
    val hasDrawableContent = current.hasRenderableContent()
    val placeholder = dictionaryPlaceholder(
        hasDictionaries = hasDictionaries,
        loading = state.loading,
        query = current?.query,
        hasContent = hasDrawableContent,
    )
    val placeholderDiagnostics = "state=$placeholder has_dictionaries=$hasDictionaries " +
        "loading=${state.loading} has_content=$hasDrawableContent"
    LaunchedEffect(placeholderDiagnostics) {
        logcat(LogPriority.INFO) { "DictionaryLookup ${state.logTag}_placeholder $placeholderDiagnostics" }
    }

    Box(modifier = modifier) {
        DictionaryEntryWebView(
            results = current?.results.orEmpty(),
            styles = current?.styles.orEmpty(),
            mediaDataUris = current?.mediaDataUris.orEmpty(),
            placeholder = placeholder.text(),
            activeProfile = profile,
            fontSize = prefs.fontSize().get(),
            existingExpressions = current?.existingExpressions.orEmpty(),
            tabs = state.tabs,
            entryJsons = current?.entryJsons,
            customCss = prefs.customCss().get(),
            modifier = Modifier.fillMaxSize(),
            onRecursiveLookup = { query, _, _, _, _, type ->
                if (query.isNotBlank()) {
                    onRecursiveLookupInitiated?.invoke(query)
                    state.lookup(query, resetPath = false, lookupType = type)
                }
            },
            onTabSelect = { index -> state.selectTab(index) },
            onBack = {
                if (state.canGoBack) {
                    state.back()
                } else {
                    onDismiss?.invoke()
                }
            },
            isLoading = state.loading,
            requestFocusOnMount = requestFocusOnMount,
            volumeKeyNavigationAllowed = volumeKeyNavigationAllowed,
            ankiSentence = ankiSentence,
            ankiSentenceOffset = ankiSentenceOffset,
            ankiScreenshotProvider = ankiScreenshotProvider,
        )
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}
// KUN <--
