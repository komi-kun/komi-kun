package eu.kanade.tachiyomi.ui.browse.migration.search

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation3.runtime.result.LocalResultEventBus
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.MigrateSearchScreen
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrateSourceSearchRoute
import mihon.core.navigation.MigrationListRoute
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.feature.migration.list.MatchOverrideEvent
@Composable
fun MigrateSearchScreen(mangaId: Long) {
    val navigator = LocalNavigator.current
    val resultEventBus = LocalResultEventBus.current

    val viewModel =
        assistedMetroViewModel<MigrateSearchViewModel, MigrateSearchViewModel.Factory> { create(mangaId = mangaId) }
    val state by viewModel.state.collectAsState()

    // KMK -->
    val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteViewModel.backHandler()
    }
    // KMK <--

    MigrateSearchScreen(
        state = state,
        fromSourceId = state.from?.source,
        navigateUp = navigator::pop,
        onChangeSearchQuery = viewModel::updateSearchQuery,
        onSearch = { viewModel.search() },
        getManga = { viewModel.getManga(it) },
        onChangeSearchFilter = viewModel::setSourceFilter,
        onToggleResults = viewModel::toggleFilterResults,
        onClickSource = { navigator.push(MigrateSourceSearchRoute(state.from!!.id, it.id, state.searchQuery)) },
        onClickItem = {
            // KMK -->
            if (bulkFavoriteState.selectionMode) {
                bulkFavoriteViewModel.toggleSelection(it)
            } else {
                // KMK <--
                val migrateListRoute = navigator.routes
                    .filterIsInstance<MigrationListRoute>()
                    .lastOrNull()

                if (migrateListRoute == null) {
                    viewModel.setMigrateDialog(mangaId, it)
                } else {
                    resultEventBus.sendResult(
                        result = MatchOverrideEvent(current = mangaId, target = it.id),
                    )
                    navigator.popTo { route -> route is MigrationListRoute }
                }
            }
        },
        onLongClickItem = { navigator.push(MangaRoute(it.id, true)) },
        // KMK -->
        bulkFavoriteViewModel = bulkFavoriteViewModel,
        hasPinnedSources = produceState(initialValue = false) { value = viewModel.hasPinnedSources() }.value,
        // KMK <--
    )

    when (val dialog = state.dialog) {
        is SearchViewModel.Dialog.Migrate -> {
            MigrateMangaDialog(
                current = dialog.current,
                target = dialog.target,
                // Initiated from the context of [dialog.current] so we show [dialog.target].
                onClickTitle = { navigator.push(MangaRoute(dialog.target.id, true)) },
                onDismissRequest = { viewModel.clearDialog() },
                onComplete = { navigator.replace(MangaRoute(dialog.target.id)) },
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
