package eu.kanade.tachiyomi.ui.libraryUpdateError

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.libraryUpdateError.LibraryUpdateErrorScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrationConfigRoute
import mihon.feature.migration.config.MigrationConfigScreen

@Composable
fun LibraryUpdateErrorScreen() {
    val navigator = LocalNavigator.current
    val viewModel = metroViewModel<LibraryUpdateErrorViewModel>()
    val state by viewModel.state.collectAsState()

    LibraryUpdateErrorScreen(
        state = state,
        onClick = { item ->
            navigator.push(MigrationConfigRoute(item.error.mangaId))
        },
        onClickCover = { item -> navigator.push(MangaRoute(item.error.mangaId)) },
        onMultiMigrateClicked = {
            navigator.push(MigrationConfigRoute(state.selected.map { it.error.mangaId }))
        },
        onSelectAll = viewModel::toggleAllSelection,
        onInvertSelection = viewModel::invertSelection,
        onErrorsDelete = viewModel::deleteSelected,
        onErrorDelete = viewModel::delete,
        onErrorSelected = viewModel::toggleSelection,
        navigateUp = navigator::pop,
    )
}
