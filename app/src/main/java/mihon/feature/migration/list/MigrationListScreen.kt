package mihon.feature.migration.list

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.util.system.toast
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MigrateSearchRoute
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.config.MigrationConfigScreenSheet
import mihon.feature.migration.list.components.MigrationExitDialog
import mihon.feature.migration.list.components.MigrationMangaDialog
import mihon.feature.migration.list.components.MigrationProgressDialog
import mihon.feature.migration.list.models.MigratingManga
import tachiyomi.i18n.MR

/**
 * Screen showing a list of pair of current-target manga entries being migrated.
 */
@Composable
fun MigrationListScreen(
    mangaIds: Collection<Long>,
    extraSearchQuery: String?,
    // KMK -->
    isSmartSearchSingleEntry: Boolean = false,
    // KMK <--
) {
    val navigator = LocalNavigator.current
    // KMK -->
    val singleEntryNoSmartSearch = mangaIds.size == 1 && !isSmartSearchSingleEntry
    // KMK <--
    val viewModel =
        assistedMetroViewModel<MigrationListViewModel, MigrationListViewModel.Factory> {
            create(
                mangaIds = mangaIds,
                extraSearchQuery = extraSearchQuery,
                // KMK -->
                runManually = singleEntryNoSmartSearch,
                // KMK <--
            )
        }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // KMK -->
    var hasPushedManual by rememberSaveable(mangaIds) { mutableStateOf(false) }
    LaunchedEffect(mangaIds) {
        if (singleEntryNoSmartSearch && !hasPushedManual) {
            @Suppress("AssignedValueIsNeverRead")
            hasPushedManual = true
            navigator.push(MigrateSearchRoute(mangaIds.single()))
        }
    }
    // KMK <--

    ResultEffect<MatchOverrideEvent> {
        viewModel.useMangaForMigration(
            current = it.current,
            target = it.target,
            onMissingChapters = {
                context.toast(MR.strings.migrationListScreen_matchWithoutChapterToast, Toast.LENGTH_LONG)
            },
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.navigateBackEvent.collect {
            // KMK -->
                /* If this screen is called from single manga migration, replace the MangaScreen in the backstack
                   with the newly migrated manga to reflect the changes properly.
                   Otherwise, just pop normally. */
            val mangaId = (state.items.firstOrNull()?.searchResult?.value as? MigratingManga.SearchResult.Success)?.manga?.id
            if (mangaIds.size == 1 && mangaId != null && navigator.routes.any { it is MangaRoute }) {
                // Replace the manga screen the migration started from, so it shows the merged entry.
                navigator.popTo { it is MangaRoute }
                navigator.pop()
                navigator.push(MangaRoute(mangaId))
            } else {
                // KMK <--
                navigator.pop()
            }
        }
    }
    MigrationListScreenContent(
        items = state.items,
        migrationComplete = state.migrationComplete,
        finishedCount = state.finishedCount,
        onItemClick = {
            navigator.push(MangaRoute(it.id, true))
        },
        onSearchManually = { migrationItem ->
            navigator.push(MigrateSearchRoute(migrationItem.manga.id))
        },
        onSkip = { viewModel.removeManga(it) },
        onMigrate = { viewModel.migrateNow(mangaId = it, replace = true) },
        onCopy = { viewModel.migrateNow(mangaId = it, replace = false) },
        openMigrationDialog = viewModel::showMigrateDialog,
        // KMK -->
        onCancel = { viewModel.cancelManga(it) },
        openOptionsDialog = viewModel::openOptionsDialog,
        // KMK <--
    )

    when (val dialog = state.dialog) {
        is MigrationListViewModel.Dialog.Migrate -> {
            MigrationMangaDialog(
                onDismissRequest = viewModel::dismissDialog,
                copy = dialog.copy,
                totalCount = dialog.totalCount,
                skippedCount = dialog.skippedCount,
                onMigrate = {
                    if (dialog.copy) {
                        viewModel.copyMangas()
                    } else {
                        viewModel.migrateMangas()
                    }
                },
            )
        }
        is MigrationListViewModel.Dialog.Progress -> {
            MigrationProgressDialog(
                progress = dialog.progress,
                exitMigration = viewModel::cancelMigrate,
            )
        }
        MigrationListViewModel.Dialog.Exit -> {
            MigrationExitDialog(
                onDismissRequest = viewModel::dismissDialog,
                exitMigration = navigator::pop,
            )
        }
        // KMK -->
        MigrationListViewModel.Dialog.Options -> {
            MigrationConfigScreenSheet(
                preferences = viewModel.preferences,
                onDismissRequest = viewModel::dismissDialog,
                onStartMigration = { _ ->
                    viewModel.dismissDialog()
                    viewModel.updateOptions()
                },
                fullSettings = false,
            )
        }
        // KMK <--
        null -> Unit
    }

    BackHandler(true) {
        viewModel.showExitDialog()
    }
}

data class MatchOverrideEvent(val current: Long, val target: Long)
