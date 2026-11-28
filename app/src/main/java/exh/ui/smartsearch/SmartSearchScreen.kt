package exh.ui.smartsearch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.system.toast
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Search source for entries, either search for entries to merge or search for recommending entry.
 */
@Composable
fun SmartSearchScreen(
    sourceId: Long,
    smartSearchConfig: SmartSearchConfig,
) {
    val viewModel = assistedMetroViewModel<SmartSearchViewModel, SmartSearchViewModel.Factory> {
        create(sourceId = sourceId, config = smartSearchConfig)
    }

    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val source by viewModel.source.collectAsState()
    LaunchedEffect(state) {
        val results = state
        if (results != null) {
            if (results is SmartSearchViewModel.SearchResults.Found) {
                navigator.replace(
                    MangaRoute(
                        results.manga.id,
                        // KMK -->
                        // Finding the entry to be merged to, so we don't want to expand description
                        // so that user can see the `Merge to another` button
                        fromSource = false,
                        // KMK <--
                        smartSearchTitle = smartSearchConfig.origTitle,
                        smartSearchMangaId = smartSearchConfig.origMangaId,
                    ),
                )
            } else {
                if (results is SmartSearchViewModel.SearchResults.NotFound) {
                    context.toast(SYMR.strings.could_not_find_entry)
                } else {
                    context.toast(SYMR.strings.automatic_search_error)
                }
                navigator.replace(
                    BrowseSourceRoute(
                        sourceId = sourceId,
                        listingQuery = smartSearchConfig.origTitle,
                        smartSearchTitle = smartSearchConfig.origTitle,
                        smartSearchMangaId = smartSearchConfig.origMangaId,
                    ),
                )
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = source?.name.orEmpty(),
                navigateUp = navigator::pop,
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(SYMR.strings.searching_source),
                style = MaterialTheme.typography.titleLarge,
            )
            CircularProgressIndicator(modifier = Modifier.size(56.dp))
        }
    }
}
