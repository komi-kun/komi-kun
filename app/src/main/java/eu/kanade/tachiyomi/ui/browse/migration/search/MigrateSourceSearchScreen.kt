package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation3.runtime.result.LocalResultEventBus
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.bulkSelectionButton
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.home.TabEvent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrationListRoute
import mihon.core.navigation.WebViewRoute
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.feature.migration.list.MatchOverrideEvent
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
@Composable
fun MigrateSourceSearchScreen(
    currentMangaId: Long,
    sourceId: Long,
    query: String?,
) {
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.current
    val resultEventBus = LocalResultEventBus.current

    val viewModel = assistedMetroViewModel<BrowseSourceViewModel, BrowseSourceViewModel.Factory> {
        create(
            sourceId = sourceId,
            listingQuery = query,
            filtersJson = null,
            savedSearch = null,
        )
    }
    val state by viewModel.state.collectAsState()

    val source = state.source
    if (source == null) {
        LoadingScreen()
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // KMK -->
    val context = LocalContext.current

    val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteViewModel.backHandler()
    }

    val mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()
    // KMK <--

    Scaffold(
        topBar = { scrollBehavior ->
            // KMK -->
            if (bulkFavoriteState.selectionMode) {
                BulkSelectionToolbar(
                    selectedCount = bulkFavoriteState.selection.size,
                    isRunning = bulkFavoriteState.isRunning,
                    onClickClearSelection = bulkFavoriteViewModel::toggleSelectionMode,
                    onChangeCategoryClick = bulkFavoriteViewModel::addFavorite,
                    onSelectAll = {
                        mangaList.itemSnapshotList.items
                            .map { it.value.first }
                            .forEach { bulkFavoriteViewModel.select(it) }
                    },
                    onReverseSelection = {
                        mangaList.itemSnapshotList.items
                            .map { it.value.first }
                            .let { bulkFavoriteViewModel.reverseSelection(it) }
                    },
                )
            } else {
                // KMK <--
                SearchToolbar(
                    searchQuery = state.toolbarQuery,
                    onChangeSearchQuery = viewModel::setToolbarQuery,
                    onClickCloseSearch = navigator::pop,
                    onSearch = viewModel::search,
                    scrollBehavior = scrollBehavior,
                    // KMK -->
                    actions = {
                        AppBarActions(
                            actions = listOf(
                                bulkSelectionButton(
                                    isRunning = bulkFavoriteState.isRunning,
                                    toggleSelectionMode = bulkFavoriteViewModel::toggleSelectionMode,
                                ),
                            ),
                        )
                    },
                    // KMK <--
                )
            }
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                text = { Text(text = stringResource(MR.strings.action_filter)) },
                icon = { Icon(MaterialSymbols.Rounded.FilterList, contentDescription = null) },
                onClick = viewModel::openFilterSheet,
                modifier = Modifier.animateFloatingActionButton(
                    visible = state.filters.isNotEmpty(),
                    alignment = Alignment.BottomEnd,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        val openMigrateDialog: (Manga) -> Unit = {
            val migrateListRoute = navigator
                .routes.filterIsInstance<MigrationListRoute>()
                .lastOrNull()

            if (migrateListRoute == null) {
                viewModel.showMigrateDialog(currentMangaId = currentMangaId, target = it)
            } else {
                resultEventBus.sendResult(
                    result = MatchOverrideEvent(current = currentMangaId, target = it.id),
                )
                navigator.popTo { screen -> screen is MigrationListRoute }
            }
        }
        BrowseSourceContent(
            source = source,
            mangaList = mangaList,
            columns = viewModel.getColumnsPreference(LocalConfiguration.current.orientation),
            // SY -->
            ehentaiBrowseDisplayMode = viewModel.ehentaiBrowseDisplayMode,
            // SY <--
            displayMode = viewModel.displayMode,
            snackbarHostState = snackbarHostState,
            contentPadding = paddingValues,
            onWebViewClick = {
                val httpSource = source as? HttpSource ?: return@BrowseSourceContent
                navigator.push(
                    WebViewRoute(
                        url = httpSource.getHomeUrl(),
                        initialTitle = httpSource.name,
                        sourceId = httpSource.id,
                    ),
                )
            },
            onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
            onLocalSourceHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) },
            onMangaClick = { manga ->
                // KMK -->
                if (bulkFavoriteState.selectionMode) {
                    bulkFavoriteViewModel.toggleSelection(manga)
                } else {
                    // KMK <--
                    openMigrateDialog(manga)
                }
            },
            onMangaLongClick = { navigator.push(MangaRoute(it.id, true)) },
            // KMK -->
            selection = bulkFavoriteState.selection,
            // KMK <--
        )
    }

    val onDismissRequest = { viewModel.setDialog(null) }
    when (val dialog = state.dialog) {
        is BrowseSourceViewModel.Dialog.Filter -> {
            SourceFilterDialog(
                onDismissRequest = onDismissRequest,
                filters = state.filters,
                onReset = viewModel::resetFilters,
                onFilter = { viewModel.search(filters = state.filters) },
                onUpdate = viewModel::setFilters,
                // SY -->
                startExpanded = viewModel.startExpanded,
                onSave = {},
                // KMK -->
                // KUN -->
                // BrowseSourceViewModel.savedSearches is a flow of its own now
                savedSearches = viewModel.savedSearches.collectAsState().value,
                // KUN <--
                onSavedSearch = { search ->
                    viewModel.onSavedSearch(search) {
                        context.toast(it)
                    }
                },
                onSavedSearchPressDesc = stringResource(SYMR.strings.saved_searches),
                shouldShowSavingButton = false,
                // KMK <--
                onSavedSearchPress = {},
                openMangaDexRandom = null,
                openMangaDexFollows = null,
                // SY <--
            )
        }
        is BrowseSourceViewModel.Dialog.Migrate -> {
            MigrateMangaDialog(
                current = dialog.current,
                target = dialog.target,
                // Initiated from the context of [dialog.current] so we show [dialog.target].
                onClickTitle = { navigator.push(MangaRoute(dialog.target.id)) },
                onDismissRequest = onDismissRequest,
                onComplete = {
                    navigator.popToRoot()
                    resultEventBus.sendResult<TabEvent>(TabEvent.Browse())
                    navigator.push(MangaRoute(dialog.target.id))
                },
            )
        }
        else -> {}
    }

    // KMK -->
    // Bulk-favorite actions only
    BulkFavoriteDialogs(
        bulkFavoriteViewModel = bulkFavoriteViewModel,
        dialog = bulkFavoriteState.dialog,
    )
    // KMK <--
}
