package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.source.model.installedExtension
import eu.kanade.presentation.browse.SourceCategoriesDialog
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.ExtensionDetailsRoute
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.SmartSearchSourceRoute
import mihon.core.navigation.SourceFeedRoute
import mihon.core.navigation.SourcesFilterRoute
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.`18UpRating`
import mihon.icons.materialsymbols.rounded.FilterList
import mihon.icons.materialsymbols.rounded.TravelExplore
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun sourcesTab(
    smartSearchConfig: SmartSearchConfig? = null,
): TabContent {
    val navigator = LocalNavigator.current
    val viewModel =
        assistedMetroViewModel<SourcesViewModel, SourcesViewModel.Factory> {
            create(smartSearchConfig = smartSearchConfig)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()

    return TabContent(
        // SY -->
        titleRes = when (smartSearchConfig == null) {
            true -> MR.strings.label_sources
            false -> SYMR.strings.find_in_another_source
        },
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = MaterialSymbols.Rounded.TravelExplore,
                onClick = { navigator.push(GlobalSearchRoute(smartSearchConfig?.origTitle ?: "")) },
            ),
            // KMK -->
            AppBar.Action(
                title = stringResource(KMR.strings.action_toggle_nsfw_only),
                icon = MaterialSymbols.Rounded.`18UpRating`,
                iconTint = if (state.nsfwOnly) MaterialTheme.colorScheme.error else LocalContentColor.current,
                onClick = { viewModel.toggleNsfwOnly() },
            ),
            // KMK <--
        ).let {
            when (smartSearchConfig) {
                null -> {
                    it + AppBar.Action(
                        title = stringResource(MR.strings.action_filter),
                        icon = MaterialSymbols.Rounded.FilterList,
                        onClick = { navigator.push(SourcesFilterRoute) },
                    )
                }
                // Merge: find in another source
                else -> it
            }
        },
        // SY <--
        content = { contentPadding, snackbarHostState ->
            SourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    // SY -->
                    val route = when {
                        // Search selected source for entries to merge or for the recommending entry
                        smartSearchConfig != null ->
                            SmartSearchSourceRoute(source.id, smartSearchConfig.origTitle, smartSearchConfig.origMangaId)
                        listing == Listing.Popular && viewModel.useNewSourceNavigation -> SourceFeedRoute(source.id)
                        else -> BrowseSourceRoute(source.id, listing.query)
                    }
                    navigator.push(route)
                    // SY <--
                },
                onClickPin = viewModel::togglePin,
                onLongClickItem = viewModel::showSourceDialog,
                // KMK -->
                onChangeSearchQuery = viewModel::search,
                // KMK <--
            )

            when (val dialog = state.dialog) {
                is SourcesViewModel.Dialog.SourceLongClick -> {
                    val source = dialog.source
                    SourceOptionsDialog(
                        source = source,
                        onClickPin = {
                            viewModel.togglePin(source)
                            viewModel.closeDialog()
                        },
                        onClickDisable = {
                            viewModel.toggleSource(source)
                            viewModel.closeDialog()
                        },
                        // SY -->
                        onClickSetCategories = {
                            viewModel.showSourceCategoriesDialog(source)
                        }.takeIf { state.categories.isNotEmpty() },
                        onClickToggleDataSaver = {
                            viewModel.toggleExcludeFromDataSaver(source)
                            viewModel.closeDialog()
                        }.takeIf { state.dataSaverEnabled },
                        // SY <--
                        onDismiss = viewModel::closeDialog,
                        // KMK -->
                        onClickSettings = {
                            if (source.installedExtension !== null) {
                                navigator.push(ExtensionDetailsRoute(source.installedExtension!!.pkgName))
                            }
                            viewModel.closeDialog()
                        },
                        // KMK <--
                    )
                }
                is SourcesViewModel.Dialog.SourceCategories -> {
                    val source = dialog.source
                    SourceCategoriesDialog(
                        source = source,
                        categories = state.categories,
                        onClickCategories = { categories ->
                            viewModel.setSourceCategories(source, categories)
                            viewModel.closeDialog()
                        },
                        onDismissRequest = viewModel::closeDialog,
                    )
                }
                null -> Unit
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        SourcesViewModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
