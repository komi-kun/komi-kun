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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanManager
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.Kind
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.OrphanedDownload
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Deselect
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.materialsymbols.rounded.SelectAll
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.core.common.i18n.pluralStringResource as contextPluralStringResource

/**
 * Lists the downloaded files no chapter can reach any more and lets the user delete the selected ones.
 *
 * The scan itself belongs to the application-scoped [OrphanedDownloadScanManager], not to this screen:
 * this entry only joins it, so the walk survives a pop and reopening the screen shows the scan that is
 * still running, or the results of the last one.
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

        // Opening the screen asks for a scan only when the manager holds nothing at all. An idle
        // manager means no scan was ever started, so it starts here and then runs on the app scope; a
        // running, finished, failed or stopped one is simply shown, and a ready list is never walked
        // again just because the screen was reopened. A stopped scan stays stopped — reopening or
        // rotating the screen must not quietly restart what the user stopped; rescan is the explicit
        // way to start it again.
        LaunchedEffect(viewModel) {
            viewModel.startScanIfIdle()
        }

        // The rows are a pure function of the entries and the two ordering choices, so they are
        // computed here, once per change of those three, instead of inside the ViewModel's state
        // transform: that one also runs for every checkbox tap, and a selection change neither
        // reorders nor re-groups anything.
        val rows = remember(state.items, state.sort, state.group) {
            rows(state.items, state.sort, state.group)
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(KUNMR.strings.orphaned_downloads_title),
                    navigateUp = navigator::pop,
                    actions = {
                        val scan = state.scan
                        val ready = scan as? OrphanedDownloadScanManager.State.Ready
                        var sortExpanded by remember { mutableStateOf(false) }
                        var groupExpanded by remember { mutableStateOf(false) }

                        if (ready != null && state.items.isNotEmpty()) {
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = { sortExpanded = false },
                            ) {
                                OrphanedDownloadSort.entries.forEach { sort ->
                                    RadioMenuItem(
                                        text = { Text(text = sortLabel(sort)) },
                                        isChecked = state.sort == sort,
                                        onClick = {
                                            viewModel.setSort(sort)
                                            sortExpanded = false
                                        },
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = groupExpanded,
                                onDismissRequest = { groupExpanded = false },
                            ) {
                                OrphanedDownloadGroup.entries.forEach { group ->
                                    RadioMenuItem(
                                        text = { Text(text = groupLabel(group)) },
                                        isChecked = state.group == group,
                                        onClick = {
                                            viewModel.setGroup(group)
                                            groupExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        AppBarActions(
                            actions = buildList {
                                if (ready != null && state.items.isNotEmpty()) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_sort),
                                            icon = MaterialSymbols.AutoMirroredRounded.Sort,
                                            onClick = { sortExpanded = true },
                                            enabled = !state.deleting,
                                        ),
                                    )
                                    add(
                                        AppBar.Action(
                                            title = stringResource(SYMR.strings.group),
                                            icon = MaterialSymbols.Rounded.Folder,
                                            onClick = { groupExpanded = true },
                                            enabled = !state.deleting,
                                        ),
                                    )
                                }
                                if (ready != null && state.selectableIds.isNotEmpty()) {
                                    val allSelected = state.allSelected
                                    add(
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
                                            enabled = !state.deleting,
                                        ),
                                    )
                                }
                                // A ready list can go stale (a library update adds chapters, a deletion
                                // elsewhere changes the tree), so it can be walked again without
                                // leaving the screen. An idle manager is offered the same action, since
                                // an unpacked scan would otherwise leave the screen empty.
                                if (ready != null || scan is OrphanedDownloadScanManager.State.Idle) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(KUNMR.strings.orphaned_downloads_refresh),
                                            icon = MaterialSymbols.Rounded.Refresh,
                                            onClick = viewModel::rescan,
                                            enabled = !state.deleting,
                                        ),
                                    )
                                }
                                // The scan is the app-scoped manager's, so leaving the screen no longer
                                // stops it: stopping is an explicit action, and it reports the manager
                                // as stopped.
                                if (scan is OrphanedDownloadScanManager.State.Running) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(KUNMR.strings.orphaned_downloads_stop),
                                            icon = MaterialSymbols.Rounded.Close,
                                            onClick = viewModel::cancelScan,
                                            enabled = !state.deleting,
                                        ),
                                    )
                                }
                            },
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when (val scan = state.scan) {
                is OrphanedDownloadScanManager.State.Idle,
                is OrphanedDownloadScanManager.State.Running,
                -> {
                    ScanningContent(
                        // Idle is what a fresh entry shows for the one frame before the scan starts:
                        // an indeterminate bar fits it, and a running scan reports its own progress.
                        scanned = (scan as? OrphanedDownloadScanManager.State.Running)?.scanned ?: 0,
                        total = (scan as? OrphanedDownloadScanManager.State.Running)?.total ?: 0,
                        modifier = Modifier.padding(contentPadding),
                    )
                }
                is OrphanedDownloadScanManager.State.Stopped -> {
                    // A stopped scan is not a scan in progress: it says so, and offers the rescan that
                    // starts a new one.
                    EmptyScreen(
                        message = stringResource(KUNMR.strings.orphaned_downloads_stopped),
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = KUNMR.strings.orphaned_downloads_refresh,
                                icon = MaterialSymbols.Rounded.Refresh,
                                onClick = viewModel::rescan,
                            ),
                        ),
                    )
                }
                is OrphanedDownloadScanManager.State.Failed -> {
                    // A revoked SAF grant or an unreadable SD card lands here instead of leaving the
                    // screen spinning: say so, and offer the scan again.
                    EmptyScreen(
                        message = stringResource(KUNMR.strings.orphaned_downloads_scan_failed),
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(
                                stringRes = MR.strings.action_retry,
                                icon = MaterialSymbols.Rounded.Refresh,
                                onClick = viewModel::rescan,
                            ),
                        ),
                    )
                }
                is OrphanedDownloadScanManager.State.Ready -> {
                    // Hoisted here so the busy branch below (a delete in flight) and
                    // [LazyColumnWithAction] share one state: swapping between them must not jump the
                    // list back to the top.
                    val listState = rememberLazyListState()

                    if (state.showConfirmation) {
                        DeleteConfirmationDialog(
                            itemCount = state.selection.size,
                            totalSize = state.selectedSizeBytes,
                            onConfirm = viewModel::deleteSelected,
                            onDismiss = viewModel::hideConfirmation,
                        )
                    }

                    when {
                        state.deleteFailed -> {
                            EmptyScreen(
                                message = stringResource(KUNMR.strings.orphaned_downloads_delete_failed),
                                modifier = Modifier.padding(contentPadding),
                                actions = listOf(
                                    EmptyScreenAction(
                                        stringRes = MR.strings.action_retry,
                                        icon = MaterialSymbols.Rounded.Refresh,
                                        onClick = viewModel::rescan,
                                    ),
                                ),
                            )
                        }
                        state.items.isEmpty() -> {
                            EmptyScreen(
                                message = stringResource(KUNMR.strings.orphaned_downloads_empty),
                                modifier = Modifier.padding(contentPadding),
                            )
                        }
                        else -> {
                            // The list keeps rendering while the deletion runs, so the user sees what
                            // is going away, but nothing on it can be acted on again until the delete
                            // finishes: a second Delete would run over the same files, and a rescan
                            // would walk a tree that is being emptied under it.
                            val listContent: LazyListScope.() -> Unit = {
                                item(key = "row:summary") {
                                    Text(
                                        text = stringResource(
                                            KUNMR.strings.orphaned_downloads_summary,
                                            state.items.size,
                                            Formatter.formatFileSize(context, state.items.sumOf { it.sizeBytes }),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                                items(
                                    items = rows,
                                    key = { row ->
                                        when (row) {
                                            is OrphanedDownloadRow.Header -> row.key
                                            is OrphanedDownloadRow.Item -> "item:${row.item.id}"
                                        }
                                    },
                                    contentType = { row ->
                                        if (row is OrphanedDownloadRow.Header) "header" else "item"
                                    },
                                ) { row ->
                                    when (row) {
                                        is OrphanedDownloadRow.Header -> ListGroupHeader(text = headerLabel(row.label))
                                        is OrphanedDownloadRow.Item -> OrphanedDownloadItem(
                                            item = row.item,
                                            isSelected = row.item.id in state.selection,
                                            enabled = !state.deleting,
                                            onClickSelect = { viewModel.toggleSelection(row.item.id) },
                                        )
                                    }
                                }
                            }
                            if (state.deleting) {
                                // An indeterminate bar takes the bottom action bar's place while the
                                // deletion runs: there is nothing to press, and the list is unchanged
                                // until the rescan that follows it.
                                Column(
                                    modifier = Modifier
                                        .padding(contentPadding)
                                        .fillMaxSize(),
                                ) {
                                    LazyColumn(
                                        modifier = Modifier.weight(1f),
                                        content = listContent,
                                        state = listState,
                                    )
                                    HorizontalDivider()
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 22.dp)
                                            .fillMaxWidth(),
                                    )
                                }
                            } else {
                                LazyColumnWithAction(
                                    contentPadding = contentPadding,
                                    actionLabel = stringResource(MR.strings.action_delete),
                                    actionEnabled = state.selection.isNotEmpty(),
                                    onClickAction = viewModel::showConfirmation,
                                    content = listContent,
                                    state = listState,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun sortLabel(sort: OrphanedDownloadSort): String = stringResource(
        when (sort) {
            OrphanedDownloadSort.PATH -> KUNMR.strings.orphaned_downloads_sort_path
            OrphanedDownloadSort.SIZE_DESC -> KUNMR.strings.orphaned_downloads_sort_size_desc
            OrphanedDownloadSort.SIZE_ASC -> KUNMR.strings.orphaned_downloads_sort_size_asc
            OrphanedDownloadSort.KIND -> KUNMR.strings.orphaned_downloads_sort_kind
        },
    )

    @Composable
    private fun groupLabel(group: OrphanedDownloadGroup): String = stringResource(
        when (group) {
            OrphanedDownloadGroup.NONE -> KUNMR.strings.orphaned_downloads_group_none
            OrphanedDownloadGroup.SOURCE -> KUNMR.strings.orphaned_downloads_group_source
            OrphanedDownloadGroup.MANGA -> KUNMR.strings.orphaned_downloads_group_manga
            OrphanedDownloadGroup.KIND -> KUNMR.strings.orphaned_downloads_group_kind
        },
    )

    /**
     * The text of a section header.
     *
     * A kind section reuses the same labels the entries carry, so what a section holds reads exactly
     * like the entries describe themselves.
     */
    @Composable
    private fun headerLabel(label: OrphanedDownloadRow.Label): String = when (label) {
        is OrphanedDownloadRow.Label.Source -> label.name
        is OrphanedDownloadRow.Label.Manga -> label.path
        is OrphanedDownloadRow.Label.Type -> when (label.kind) {
            Kind.CHAPTER -> stringResource(MR.strings.chapters)
            Kind.MANGA -> stringResource(KUNMR.strings.orphaned_downloads_kind_manga)
            Kind.SOURCE -> stringResource(KUNMR.strings.orphaned_downloads_kind_source)
        }
    }

    @Composable
    private fun ScanningContent(
        scanned: Int,
        total: Int,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { scanned.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (total > 0) {
                    stringResource(KUNMR.strings.orphaned_downloads_progress, scanned, total)
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
        enabled: Boolean,
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
                .clickable(enabled = enabled, onClick = onClickSelect)
                .padding(horizontal = 8.dp)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
                enabled = enabled,
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
    private val manager: OrphanedDownloadScanManager,
    private val scanner: OrphanedDownloadScanner,
) : ViewModel() {

    /** The parts of the screen this entry owns; the scan itself is the [manager]'s. */
    private data class UiState(
        val sort: OrphanedDownloadSort = OrphanedDownloadSort.SIZE_DESC,
        val group: OrphanedDownloadGroup = OrphanedDownloadGroup.NONE,
        val selection: Set<String> = emptySet(),
        val showConfirmation: Boolean = false,
        val deleteFailed: Boolean = false,
        /** A delete is in flight: the manager still holds the old [State.Ready] list until it ends. */
        val deleting: Boolean = false,
    )

    private val uiState = MutableStateFlow(UiState())

    private val _deleted = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val deleted = _deleted.asSharedFlow()

    // What the selection was last pruned against, by identity: state changes that only touch the
    // selection cannot have added or removed an entry, so the O(items) prune is skipped for them.
    private var prunedFrom: List<OrphanedDownload>? = null
    private var prunedFor: Set<String>? = null
    private var prunedSelection: Set<String> = emptySet()

    val state: StateFlow<ScreenState> = combine(manager.state, uiState) { scan, ui -> screenState(scan, ui) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = screenState(manager.state.value, uiState.value),
        )

    /**
     * Joins the app-scoped scan, starting one only when the manager holds nothing.
     *
     * A stopped scan is not "nothing": the user stopped it on purpose, so re-entering the screen or
     * rotating it must not start it again — only [rescan] does.
     */
    fun startScanIfIdle() {
        if (manager.state.value is OrphanedDownloadScanManager.State.Idle) manager.start()
    }

    /**
     * Walks the downloads tree again, replacing whatever is shown.
     *
     * The scan is the manager's, so it keeps running after this screen is popped; only the delete
     * below is this ViewModel's.
     */
    fun rescan() {
        uiState.update { it.copy(showConfirmation = false, deleteFailed = false) }
        manager.start()
    }

    /** Stops the app-scoped scan; the manager reports it as stopped and keeps no partial result. */
    fun cancelScan() {
        manager.cancel()
    }

    fun setSort(sort: OrphanedDownloadSort) {
        uiState.update { it.copy(sort = sort) }
    }

    fun setGroup(group: OrphanedDownloadGroup) {
        uiState.update { it.copy(group = group) }
    }

    fun toggleSelection(id: String) {
        uiState.update { ui ->
            ui.copy(selection = if (id in ui.selection) ui.selection - id else ui.selection + id)
        }
    }

    fun toggleSelectAll() {
        val current = state.value
        // Only single chapter entries are taken: a whole manga folder that matches no manga, or a
        // source folder whose extension is merely uninstalled right now, is selected one by one.
        uiState.update { ui ->
            ui.copy(
                selection = if (isAllSelected(current.items, current.selection)) {
                    emptySet()
                } else {
                    selectableIds(current.items)
                },
            )
        }
    }

    fun showConfirmation() {
        uiState.update { it.copy(showConfirmation = true) }
    }

    fun hideConfirmation() {
        uiState.update { it.copy(showConfirmation = false) }
    }

    /**
     * Deletes the selected entries and then asks the manager for a rescan, so the list always shows
     * what is left — and that rescan outlives the screen like any other.
     *
     * The delete itself is this ViewModel's work, so a failure is reported here rather than as a scan
     * failure: the tree was readable enough to list these files, it is the deletion that failed.
     *
     * The manager stays [OrphanedDownloadScanManager.State.Ready] for the whole deletion, so [deleting]
     * marks it busy here instead: without it the list, the Delete button, Rescan and the selection stay
     * live, and a second Delete would run over the same files while a Rescan walked the tree that is
     * being emptied.
     */
    fun deleteSelected() {
        // The disabled action is not a guard: `deleting` reaches the UI through the asynchronous
        // `state` flow, so two confirms in one frame both see it false. Claim it synchronously here;
        // the call that loses the race returns before reading the selection or starting a walk.
        if (!beginDelete()) return

        val current = state.value
        val selected = current.items.filter { it.id in current.selection }
        if (selected.isEmpty()) {
            // The selection was emptied between opening the dialog and confirming it: there is
            // nothing to delete, and the flag just claimed must not be left behind.
            uiState.update { it.copy(deleting = false) }
            return
        }

        viewModelScope.launch {
            try {
                val deletedCount = runCatching { scanner.delete(selected) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        xLogE("Failed to delete orphaned downloads", e)
                    }
                    .getOrNull()
                if (deletedCount == null) {
                    uiState.update { it.copy(deleteFailed = true) }
                    return@launch
                }
                _deleted.tryEmit(deletedCount)
                uiState.update { it.copy(selection = emptySet()) }
                manager.start()
            } finally {
                // Cleared on every path, including a cancellation of this scope: a stuck busy state
                // would leave the screen permanently unresponsive.
                uiState.update { it.copy(deleting = false) }
            }
        }
    }

    /**
     * Synchronously claims [UiState.deleting] for one delete, closing the confirmation, and returns
     * false when another call already holds it.
     *
     * The claim has to be made here, on the underlying flow, rather than by reading [state]: that one
     * is a `stateIn` collector, so it still shows `deleting = false` while the first delete is already
     * running, and two confirms in one frame would both pass a check made against it.
     */
    private fun beginDelete(): Boolean {
        while (true) {
            val current = uiState.value
            if (current.deleting) return false
            if (uiState.compareAndSet(current, current.copy(showConfirmation = false, deleting = true))) return true
        }
    }

    private fun screenState(
        scan: OrphanedDownloadScanManager.State,
        ui: UiState,
    ): ScreenState {
        val items = (scan as? OrphanedDownloadScanManager.State.Ready)?.items.orEmpty()
        return ScreenState(
            scan = scan,
            items = items,
            selection = retainedSelection(ui.selection, items),
            sort = ui.sort,
            group = ui.group,
            showConfirmation = ui.showConfirmation,
            deleteFailed = ui.deleteFailed,
            deleting = ui.deleting,
        )
    }

    /**
     * [retainSelection] memoised on the identity of both inputs.
     *
     * The scan is the only source of ids, so a selection made before a rescan or a deletion can name
     * entries that are gone and is pruned on every item list change. This transform also runs for
     * every checkbox tap, though, and a tap keeps both the list and its entries: while both are the
     * very objects the last prune saw, its result is reused instead of walking the list again.
     */
    private fun retainedSelection(selection: Set<String>, items: List<OrphanedDownload>): Set<String> {
        if (items === prunedFrom && selection === prunedFor) return prunedSelection
        return retainSelection(selection, items).also { pruned ->
            prunedFrom = items
            prunedFor = selection
            prunedSelection = pruned
        }
    }

    /** What the screen renders: the manager's scan plus this entry's ordering and selection. */
    @Immutable
    data class ScreenState(
        val scan: OrphanedDownloadScanManager.State,
        val items: List<OrphanedDownload>,
        val selection: Set<String>,
        val sort: OrphanedDownloadSort,
        val group: OrphanedDownloadGroup,
        val showConfirmation: Boolean,
        val deleteFailed: Boolean,
        val deleting: Boolean,
    ) {
        // Derived once per state rather than by every reader: the app bar asked for all three on each
        // recomposition, and each call rebuilt a HashSet over the whole list.
        val selectableIds: Set<String> = selectableIds(items)
        val allSelected: Boolean = isAllSelected(items, selection)
        val selectedSizeBytes: Long = selectedSizeBytes(items, selection)
    }
}
