package eu.kanade.tachiyomi.ui.home

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import eu.kanade.core.preference.asState
import eu.kanade.presentation.more.settings.screen.SettingsMangadexScreen
import eu.kanade.presentation.more.settings.screen.openSettings
import eu.kanade.presentation.util.rememberAnimatedVectorPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseShowExtensionsEvent
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.browse.BrowseTabEvents
import eu.kanade.tachiyomi.ui.dictionary.DictionaryTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibrarySearchEvent
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import mihon.app.di.appGraph
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.LibraryUpdateErrorRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.Navigator
import mihon.core.navigation.NavigatorDisplay
import mihon.core.navigation.rememberNavigator
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Search
import mihon.presentation.core.util.isTabletUi
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Serializable
sealed interface TopLevelRoute : NavKey {

    val reselectEventKey: String
        get() = "TabReselectEvent:$this"

    @Composable
    fun options(isSelected: Boolean): TabOptions

    @Serializable
    data object Library : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.animated_library)
            return TabOptions(
                title = stringResource(MR.strings.label_library),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object Updates : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.animated_updates)
            return TabOptions(
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object History : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.animated_history)
            return TabOptions(
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    }

    @Serializable
    data object Browse : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val enter = AnimatedImageVector.animatedVectorResource(R.drawable.animated_browse_enter)
            val exit = AnimatedImageVector.animatedVectorResource(R.drawable.animated_browse_exit)
            return TabOptions(
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(enter, exit, isSelected),
            )
        }
    }

    // KUN -->
    /**
     * Part of the bottom bar and navigation rail only while dictionaries/OCR are enabled
     * (`DictionaryOcrPreferences`) and the user has opted to display the tab in the navigation bar
     * (`UiPreferences.showNavDictionary`). By default, the Dictionary is reached from the More
     * screen, which pushes this route on the outer back stack.
     *
     * The rule itself lives in `DictionaryTabNavigationPolicy.isTabVisible`, which the `HomeScreen`
     * tab filter and the direct-request fallback share (and which is unit-tested).
     */
    @Serializable
    data object Dictionary : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            return TabOptions(
                title = stringResource(KUNMR.strings.label_dictionary),
                icon = rememberVectorPainter(MaterialSymbols.Rounded.Search),
            )
        }
    }
    // KUN <--

    @Serializable
    data object More : TopLevelRoute {
        @Composable
        override fun options(isSelected: Boolean): TabOptions {
            val enter = AnimatedImageVector.animatedVectorResource(R.drawable.animated_more_enter)
            val exit = AnimatedImageVector.animatedVectorResource(R.drawable.animated_more_exit)
            return TabOptions(
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(enter, exit, isSelected),
            )
        }
    }
}

@Composable
fun HomeScreen() {
    val tabNavigator = rememberNavigator(TopLevelRoute.Library)
    val navigator = LocalNavigator.current
    val resultEventBus = LocalResultEventBus.current
    val tabletUi = isTabletUi()
    val navigationSuiteType = if (tabletUi) {
        NavigationSuiteType.NavigationRail
    } else {
        NavigationSuiteType.NavigationBar
    }
    val navigationSuiteState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(navigationSuiteState, tabletUi) {
        if (tabletUi) navigationSuiteState.show()
        showBottomNavEvent.receiveAsFlow().collectLatest { show ->
            if (tabletUi || show) {
                navigationSuiteState.show()
            } else {
                navigationSuiteState.hide()
            }
        }
    }

    // SY -->
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val alwaysShowLabel by remember {
        context.appGraph.uiPreferences.bottomBarLabels.asState(scope)
    }
    // SY <--

    // KUN -->
    // The dictionary/OCR master switch, observed so the navigation reacts to the settings toggle
    // (and to the setup answer) without waiting for a restart. `asState` starts a collector, so
    // it must be remembered: re-creating it on every recomposition would leak one collector per
    // pass into the composition scope.
    val dictionaryOcrPreferences = remember(context) { context.appGraph.dictionaryOcrPreferences }
    val dictionaryOcrEnabled by remember(dictionaryOcrPreferences) {
        dictionaryOcrPreferences.enabled.asState(scope)
    }
    val showNavDictionary by remember(context) {
        context.appGraph.uiPreferences.showNavDictionary.asState(scope)
    }
    // KUN <--

    NavigationSuiteScaffold(
        navigationSuiteType = navigationSuiteType,
        state = navigationSuiteState,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationRailContainerColor = MaterialTheme.colorScheme
                .surfaceColorAtElevation(3.dp),
        ),
        navigationItemVerticalArrangement = Arrangement.Center,
        navigationItems = {
            TABS
                // KUN -->
                // Same rule as the old `DictionaryTab.isEnabled()`, applied here so the tab's
                // presence in the navigation never depends on a single call site.
                .fastFilter {
                    DictionaryTabNavigationPolicy.isVisible(it, dictionaryOcrEnabled, showNavDictionary)
                }
                // KUN <--
                .fastForEach {
                    NavigationItem(
                        tab = it,
                        tabNavigator = tabNavigator,
                        navigationSuiteType = navigationSuiteType,
                        // SY -->
                        alwaysShowLabel = alwaysShowLabel,
                        // SY <--
                    )
                }
        },
    ) {
        val tabStateHolder = rememberSaveableStateHolder()
        NavigatorDisplay(
            navigator = tabNavigator,
            entryDecorators = listOf(
                remember(tabStateHolder) {
                    NavEntryDecorator { entry ->
                        tabStateHolder.SaveableStateProvider(entry.contentKey) { entry.Content() }
                    }
                },
            ),
            transitionSpec = { spec },
            popTransitionSpec = { spec },
            predictivePopTransitionSpec = { spec },
            entryProvider = entryProvider {
                entry<TopLevelRoute.Library> {
                    LibraryTab()
                }

                entry<TopLevelRoute.Updates> {
                    UpdatesTab()
                }

                entry<TopLevelRoute.History> {
                    HistoryTab()
                }

                entry<TopLevelRoute.Browse> {
                    BrowseTab()
                }

                // KUN -->
                entry<TopLevelRoute.Dictionary> {
                    DictionaryTab()
                }
                // KUN <--

                entry<TopLevelRoute.More> {
                    MoreTab()
                }
            },
        )
    }

    // KUN -->
    // Turning the feature off (or hiding the nav bar button) while the Dictionary tab is selected
    // would leave the user on a tab that is no longer in the navigation; the policy decides the
    // fallback for both the bottom bar and the rail.
    LaunchedEffect(dictionaryOcrEnabled, showNavDictionary) {
        val mustFallBack = DictionaryTabNavigationPolicy.mustFallBackToLibrary(
            currentIsDictionary = tabNavigator.current is TopLevelRoute.Dictionary,
            enabled = dictionaryOcrEnabled,
            showInNavBar = showNavDictionary,
        )
        if (mustFallBack) {
            tabNavigator.replaceAll(TopLevelRoute.Library)
        }
    }
    // KUN <--

    // SY -->
    LaunchedEffect(tabNavigator.current) {
        BrowseTabEvents.onBrowseTabSelected(tabNavigator.current == TopLevelRoute.Browse)
    }
    // SY <--

    ResultEffect<TabEvent> {
        val route = when (it) {
            is TabEvent.Library -> {
                if (it.query != null) resultEventBus.sendResult(LibrarySearchEvent(it.query))
                TopLevelRoute.Library
            }
            TabEvent.Updates -> TopLevelRoute.Updates
            TabEvent.History -> TopLevelRoute.History
            is TabEvent.Browse -> {
                if (it.toExtensions) resultEventBus.sendResult(BrowseShowExtensionsEvent)
                TopLevelRoute.Browse
            }
            // KUN -->
            // A direct `TabEvent.Dictionary` request must not open a tab that the
            // navigation filter hides while the feature is off. No in-repo producer exists today
            // (no dictionary shortcut, no intent action); the policy owns the fallback and it is
            // unit-tested.
            TabEvent.Dictionary -> when (
                DictionaryTabNavigationPolicy.directEventTarget(dictionaryOcrEnabled, showNavDictionary)
            ) {
                DictionaryTabTarget.DICTIONARY -> TopLevelRoute.Dictionary
                DictionaryTabTarget.LIBRARY -> TopLevelRoute.Library
            }
            // KUN <--
            is TabEvent.More -> TopLevelRoute.More
        }
        tabNavigator.replaceAll(route)

        if (it is TabEvent.Library && it.mangaIdToOpen != null) {
            navigator.push(MangaRoute(it.mangaIdToOpen))
        }
        if (it is TabEvent.More) {
            if (it.toDownloads) {
                navigator.push(DownloadQueueRoute)
                // KMK -->
            } else if (it.toLibraryUpdateErrors) {
                navigator.push(LibraryUpdateErrorRoute)
            } else if (it.toMangadexSettings) {
                navigator.openSettings(SettingsMangadexScreen, tabletUi)
                // KMK <--
            }
        }
    }
}

@Composable
private fun NavigationItem(
    tab: TopLevelRoute,
    tabNavigator: Navigator,
    navigationSuiteType: NavigationSuiteType,
    // SY -->
    alwaysShowLabel: Boolean,
    // SY <--
) {
    val resultEventBus = LocalResultEventBus.current
    val selected = tabNavigator.current == tab
    val options = tab.options(selected)

    NavigationSuiteItem(
        navigationSuiteType = navigationSuiteType,
        selected = selected,
        onClick = {
            if (!selected) {
                tabNavigator.replaceAll(tab)
            } else {
                resultEventBus.sendResult(
                    resultKey = tab.reselectEventKey,
                    result = Unit,
                )
            }
        },
        icon = {
            Icon(
                painter = options.icon,
                contentDescription = options.title,
            )
        },
        label = if (
            // SY -->
            alwaysShowLabel ||
            // SY <--
            selected
        ) {
            {
                Text(
                    text = options.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        badge = tabBadge(tab),
    )
}

@Composable
private fun tabBadge(tab: TopLevelRoute): (@Composable () -> Unit)? {
    val context = LocalContext.current
    val count by produceState(initialValue = 0, tab) {
        val graph = context.appGraph
        when (tab) {
            is TopLevelRoute.Updates -> {
                combine(
                    graph.libraryPreferences.newShowUpdatesCount.changes(),
                    graph.libraryPreferences.newUpdatesCount.changes(),
                ) { show, count ->
                    if (show) count else 0
                }
                    .collectLatest { value = it }
            }

            is TopLevelRoute.Browse -> {
                graph.sourcePreferences.extensionUpdatesCount.changes()
                    .collectLatest { value = it }
            }

            else -> value = 0
        }
    }
    if (count <= 0) return null
    return {
        Badge {
            val desc = when (tab) {
                is TopLevelRoute.Updates -> pluralStringResource(
                    MR.plurals.notification_chapters_generic,
                    count = count,
                    count,
                )

                is TopLevelRoute.Browse -> pluralStringResource(
                    MR.plurals.update_check_notification_ext_updates,
                    count = count,
                    count,
                )

                else -> null
            }
            Text(
                text = count.toString(),
                modifier = Modifier.semantics {
                    if (desc != null) contentDescription = desc
                },
            )
        }
    }
}

// KUN -->
/**
 * Requests a bottom-navigation change from outside the tab host (selection mode in the library and
 * updates tabs, which hide the bar while selecting).
 */
suspend fun showBottomNav(show: Boolean) {
    showBottomNavEvent.send(show)
}

private val showBottomNavEvent = Channel<Boolean>()
// KUN <--

sealed interface TabEvent {
    data class Library(val mangaIdToOpen: Long? = null, val query: String? = null) : TabEvent
    data object Updates : TabEvent
    data object History : TabEvent
    data class Browse(val toExtensions: Boolean = false) : TabEvent

    // KUN -->
    data object Dictionary : TabEvent
    // KUN <--

    data class More(
        val toDownloads: Boolean,
        // KMK -->
        val toLibraryUpdateErrors: Boolean = false,
        val toMangadexSettings: Boolean = false,
        // KMK <--
    ) : TabEvent
}

@Suppress("ConstPropertyName")
private const val TabFadeDuration = 200

data class ShowBottomNavEvent(val visible: Boolean)

data class TabOptions(
    val title: String,
    val icon: Painter,
)

private val spec = materialFadeThroughIn(
    initialScale = 1f,
    durationMillis = TabFadeDuration,
) togetherWith materialFadeThroughOut(durationMillis = TabFadeDuration)

private val TABS: List<TopLevelRoute> = listOf(
    TopLevelRoute.Library,
    TopLevelRoute.Updates,
    TopLevelRoute.History,
    TopLevelRoute.Browse,
    // KUN -->
    TopLevelRoute.Dictionary,
    // KUN <--
    TopLevelRoute.More,
)
