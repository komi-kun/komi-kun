package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.preference.asState
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.rememberAnimatedVectorPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.feed.FeedViewModel
import eu.kanade.tachiyomi.ui.browse.feed.feedTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.app.di.appGraph
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.LocalNavigator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object BrowseTabEvents {
    val switchToExtensionTabChannel = Channel<Unit>(Channel.CONFLATED)

    fun showExtension() {
        switchToExtensionTabChannel.trySend(Unit)
    }

    // SY -->
    val feedRefreshChannel = Channel<Unit>(Channel.CONFLATED)
    private var feedRefreshNotified = false

    /**
     * Notify the feed to refresh on browse-tab selection
     */
    fun onBrowseTabSelected(isSelected: Boolean) {
        if (isSelected) {
            if (!feedRefreshNotified) {
                feedRefreshNotified = true
                feedRefreshChannel.trySend(Unit)
            }
        } else {
            feedRefreshNotified = false
        }
    }
    // SY <--
}

@Composable
fun BrowseTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // SY -->
    val hideFeedTab by remember { context.appGraph.uiPreferences.hideFeedTab.asState(scope) }
    val feedTabInFront by remember { context.appGraph.uiPreferences.feedTabInFront.asState(scope) }
    // SY <--
    val navigator = LocalNavigator.current

    // Hoisted for extensions tab's search bar
    val extensionsViewModel = metroViewModel<ExtensionsViewModel>()
    val extensionsSearchQuery by extensionsViewModel.searchQuery.collectAsStateWithLifecycle()

    // KMK -->
    val feedViewModel = metroViewModel<FeedViewModel>()
    val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
    // KMK <--

    // SY -->
    LaunchedEffect(Unit) {
        BrowseTabEvents.feedRefreshChannel.receiveAsFlow().collect {
            feedViewModel.init()
        }
    }
    val tabs = when {
        hideFeedTab ->
            listOf(
                sourcesTab(),
                extensionsTab(extensionsViewModel),
                migrateSourceTab(),
            )

        feedTabInFront ->
            listOf(
                feedTab(
                    // KMK -->
                    feedViewModel,
                    bulkFavoriteViewModel,
                    // KMK <--
                ),
                sourcesTab(),
                extensionsTab(extensionsViewModel),
                migrateSourceTab(),
            )

        else ->
            listOf(
                sourcesTab(),
                feedTab(
                    // KMK -->
                    feedViewModel,
                    bulkFavoriteViewModel,
                    // KMK <--
                ),
                extensionsTab(extensionsViewModel),
                migrateSourceTab(),
            )
    }
    // SY <--

    val state = rememberPagerState { tabs.size }

    TabbedScreen(
        titleRes = MR.strings.browse,
        tabs = tabs,
        state = state,
        searchQuery = extensionsSearchQuery,
        onChangeSearchQuery = extensionsViewModel::search,
        // KMK -->
        feedViewModel = feedViewModel,
        bulkFavoriteViewModel = bulkFavoriteViewModel,
        // KMK <--
    )
    LaunchedEffect(Unit) {
        BrowseTabEvents.switchToExtensionTabChannel.receiveAsFlow()
            .collectLatest { state.scrollToPage(/* SY --> */if (hideFeedTab) 1 else 2/* SY <-- */) }
    }

    LaunchedEffect(Unit) {
        (context as? MainActivity)?.ready = true

        // AM (DISCORD) -->
        with(DiscordRPCService) {
            discordScope.launchIO { setScreen(context, DiscordScreen.BROWSE) }
        }
        // <-- AM (DISCORD)
    }

    ResultEffect<BrowseShowExtensionsEvent> {
        state.scrollToPage(1)
    }

    ResultEffect<Unit>(resultKey = TopLevelRoute.Browse.reselectEventKey) {
        navigator.push(GlobalSearchRoute())
    }
}

data object BrowseShowExtensionsEvent
