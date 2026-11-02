package eu.kanade.presentation.more.settings.screen.advanced

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.Kind
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.OrphanedDownload
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Deselect
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.core.common.i18n.pluralStringResource as contextPluralStringResource

/**
 * Mutable holder for the job a screen entry owns, so assigning a declined delete cannot drop the
 * job already tracked (see [OrphanedDownloadsScreen.Content]).
 */
private class JobHolder {
    var job: Job? = null
}

/**
 * Lists the downloaded files no chapter can reach any more and lets the user delete the selected ones.
 */
class OrphanedDownloadsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<OrphanedDownloadsViewModel>()
        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) {
            viewModel.deleted.collect { count ->
                context.toast(
                    context.contextPluralStringResource(KUNMR.plurals.orphaned_downloads_deleted, count, count),
                )
            }
        }

        // The ViewModel is activity-scoped (Voyager has no per-entry ViewModelStoreOwner), so the work
        // it does lives past a pop unless the screen cancels it. The composition therefore owns the
        // jobs this entry started — the entry scan, and the delete that chains the rescan after it —
        // and cancels exactly those on dispose. Cancelling "whatever job is current" instead would
        // kill the next entry's scan, because Voyager composes the new entry before disposing this
        // one, which left a fast pop and reopen spinning forever.
        //
        // A plain holder, not a `MutableState`: assigning the result of `deleteSelected` over it would
        // overwrite the tracked job with `null` when that call declines (nothing selected, or no ready
        // list), and `onDispose` could then no longer cancel the scan still running.
        val work = remember(viewModel) { JobHolder() }
        DisposableEffect(viewModel) {
            work.job = viewModel.rescan()
            onDispose { work.job?.cancel() }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KUNMR.strings.orphaned_downloads_title),
                    navigateUp = navigator::pop,
                    actions = {
                        val ready = state as? OrphanedDownloadsViewModel.State.Ready
                        if (ready != null && ready.selectableIds.isNotEmpty()) {
                            val allSelected = ready.allSelected
                            AppBarActions(
                                actions = listOf(
                                    AppBar.Action(
                                        title = if (allSelected) {
                                            stringResource(KUNMR.strings.orphaned_downloads_clear_selection)
                                        } else {
                                            stringResource(MR.strings.action_select_all)
                                        },
                                        icon = if (allSelected) {
                                            MaterialSymbols.Rounded.Deselect
                                        } else {
                                            MaterialSymbols.Rounded.SelectAll
                                        },
                                        onClick = viewModel::toggleSelectAll,
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when (val s = state) {
                is OrphanedDownloadsViewModel.State.Scanning -> {
                    ScanningContent(
                        state = s,
                        modifier = Modifier.padding(contentPadding),
                    )
                }
                is OrphanedDownloadsViewModel.State.Ready -> {
                    if (s.showConfirmation) {
                        DeleteConfirmationDialog(
                            itemCount = s.selection.size,
                            totalSize = s.items.filter { it.id in s.selection }.sumOf { it.sizeBytes },
                            onConfirm = { viewModel.deleteSelected()?.let { work.job = it } },
                            onDismiss = viewModel::hideConfirmation,
                        )
                    }

                    if (s.error != null) {
                        // A revoked SAF grant or an unreadable SD card lands here instead of leaving
                        // the screen spinning: say so, and offer the scan again.
                        EmptyScreen(
                            message = stringResource(
                                when (s.error) {
                                    OrphanedDownloadsViewModel.Error.SCAN ->
                                        KUNMR.strings.orphaned_downloads_scan_failed
                                    OrphanedDownloadsViewModel.Error.DELETE ->
                                        KUNMR.strings.orphaned_downloads_delete_failed
                                },
                            ),
                            modifier = Modifier.padding(contentPadding),
                            actions = listOf(
                                EmptyScreenAction(
                                    stringRes = MR.strings.action_retry,
                                    icon = MaterialSymbols.Rounded.Refresh,
                                    onClick = { work.job = viewModel.rescan() },
                                ),
                            ),
                        )
                    } else if (s.items.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(KUNMR.strings.orphaned_downloads_empty),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumnWithAction(
                            contentPadding = contentPadding,
                            actionLabel = stringResource(MR.strings.action_delete),
                            actionEnabled = s.selection.isNotEmpty(),
                            onClickAction = viewModel::showConfirmation,
                        ) {
                            item(key = "summary") {
                                Text(
                                    text = stringResource(
                                        KUNMR.strings.orphaned_downloads_summary,
                                        s.items.size,
                                        Formatter.formatFileSize(context, s.items.sumOf { it.sizeBytes }),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                            items(s.items, key = { it.id }) { item ->
                                OrphanedDownloadItem(
                                    item = item,
                                    isSelected = item.id in s.selection,
                                    onClickSelect = { viewModel.toggleSelection(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScanningContent(
        state: OrphanedDownloadsViewModel.State.Scanning,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.scanned.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (state.total > 0) {
                    stringResource(KUNMR.strings.orphaned_downloads_progress, state.scanned, state.total)
                } else {
                    stringResource(KUNMR.strings.orphaned_downloads_scanning)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun OrphanedDownloadItem(
        item: OrphanedDownload,
        isSelected: Boolean,
        onClickSelect: () -> Unit,
    ) {
        val context = LocalContext.current
        val kindLabel = when (item.kind) {
            Kind.CHAPTER -> null
            Kind.MANGA -> stringResource(KUNMR.strings.orphaned_downloads_kind_manga)
            Kind.SOURCE -> stringResource(KUNMR.strings.orphaned_downloads_kind_source)
        }
        Row(
            modifier = Modifier
                .selectedBackground(isSelected)
                .clickable(onClick = onClickSelect)
                .padding(horizontal = 8.dp)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = item.file.name ?: item.relativePath.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(item.relativePath, kindLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = Formatter.formatFileSize(context, item.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    @Composable
    private fun DeleteConfirmationDialog(
        itemCount: Int,
        totalSize: Long,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val context = LocalContext.current
        AlertDialog(
            title = {
                Text(text = stringResource(KUNMR.strings.orphaned_downloads_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(
                        KUNMR.strings.orphaned_downloads_confirm_message,
                        itemCount,
                        Formatter.formatFileSize(context, totalSize),
                    ),
                )
            },
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(text = stringResource(MR.strings.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class OrphanedDownloadsViewModel(
    private val scanner: OrphanedDownloadScanner,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Scanning())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _deleted = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val deleted = _deleted.asSharedFlow()

    /**
     * The scan or delete in flight, so a new scan replaces the old one instead of running beside it.
     *
     * This ViewModel is activity-scoped, so the field is not what keeps work from outliving the
     * screen: every entry point returns the job it started, and the screen cancels exactly the jobs
     * its entry owns (see [OrphanedDownloadsScreen.Content]). Cancelling this field on dispose instead
     * would kill the successor entry's scan, because the new entry is composed before the old one is
     * disposed.
     */
    private var workJob: Job? = null

    /** Scans the downloads tree again, replacing whatever is currently shown. */
    fun rescan(): Job {
        workJob?.cancel()
        _state.value = State.Scanning()
        return viewModelScope.launch { scan() }.also { workJob = it }
    }

    private suspend fun scan() {
        val items = runCatching {
            scanner.scan { scanned, total ->
                _state.update { current ->
                    if (current is State.Scanning) current.copy(scanned = scanned, total = total) else current
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            xLogE("Failed to scan for orphaned downloads", e)
            _state.value = State.Ready(items = emptyList(), error = Error.SCAN)
            return
        }
        _state.value = State.Ready(items)
    }

    fun toggleSelection(id: String) = _state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(
            selection = if (id in state.selection) state.selection - id else state.selection + id,
        )
    }

    fun toggleSelectAll() = _state.update { state ->
        if (state !is State.Ready) return@update state
        // Only single chapter entries: a whole manga folder that matches no manga, or a source
        // folder whose extension is merely uninstalled right now, is selected one by one.
        val selectable = state.selectableIds
        state.copy(selection = if (state.allSelected) emptySet() else selectable)
    }

    fun showConfirmation() = _state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = true)
    }

    fun hideConfirmation() = _state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = false)
    }

    /**
     * Deletes the selected entries and then rescans, so the list always shows what is left.
     *
     * The returned job covers the delete *and* the rescan chained after it, so a screen that leaves
     * mid-delete cancels both: the delete stops between two items (the cache is still invalidated,
     * see [OrphanedDownloadScanner.delete]) and no walk of the tree starts for a screen that is gone.
     */
    fun deleteSelected(): Job? {
        val ready = _state.value as? State.Ready ?: return null
        val selected = ready.items.filter { it.id in ready.selection }
        if (selected.isEmpty()) return null

        // Cancel first, then replace the state: a late progress callback from the outgoing scan must
        // not overwrite the new state (same order as [rescan]).
        workJob?.cancel()
        _state.value = State.Scanning()
        return viewModelScope.launch {
            val deletedCount = runCatching { scanner.delete(selected) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    xLogE("Failed to delete orphaned downloads", e)
                }
                .getOrNull()
            if (deletedCount == null) {
                _state.value = State.Ready(items = emptyList(), error = Error.DELETE)
                return@launch
            }
            _deleted.tryEmit(deletedCount)
            // Same job on purpose: leaving the screen must cancel the rescan too.
            scan()
        }.also { workJob = it }
    }

    /** Which step failed, so the screen can say so and offer a retry. */
    enum class Error {
        SCAN,
        DELETE,
    }

    sealed interface State {
        @Immutable
        data class Scanning(
            val scanned: Int = 0,
            val total: Int = 0,
        ) : State

        @Immutable
        data class Ready(
            val items: List<OrphanedDownload>,
            val selection: Set<String> = emptySet(),
            val showConfirmation: Boolean = false,
            val error: Error? = null,
        ) : State {
            /**
             * The entries "select all" may pick: only single chapter files and folders.
             *
             * A body `val`, computed once per instance: the app bar reads it on every progress tick of
             * the scan, and a getter would rebuild the set each time.
             */
            val selectableIds: Set<String> = items.filter { it.kind == Kind.CHAPTER }.mapTo(mutableSetOf()) { it.id }

            val allSelected: Boolean
                get() = selectableIds.isNotEmpty() && selection.containsAll(selectableIds)
        }
    }
}
