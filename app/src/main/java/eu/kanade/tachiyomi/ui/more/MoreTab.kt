package eu.kanade.tachiyomi.ui.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.openSettings
import eu.kanade.presentation.util.rememberAnimatedVectorPainter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mihon.core.navigation.AboutRoute
import mihon.core.navigation.BatchAddRoute
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.HistoryTabRoute
import mihon.core.navigation.LibraryUpdateErrorRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.StatsRoute
import mihon.core.navigation.UpdatesTabRoute
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MoreTab() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val isTabletUi = isTabletUi()
    val viewModel = metroViewModel<MoreViewModel>()
    val downloadQueueState by viewModel.downloadQueueState.collectAsState()
    MoreScreen(
        downloadQueueStateProvider = { downloadQueueState },
        downloadedOnly = viewModel.downloadedOnly,
        onDownloadedOnlyChange = { viewModel.downloadedOnly = it },
        incognitoMode = viewModel.incognitoMode,
        onIncognitoModeChange = { viewModel.incognitoMode = it },
        // SY -->
        showNavUpdates = viewModel.showNavUpdates,
        showNavHistory = viewModel.showNavHistory,
        // SY <--
        // KUN -->
        showDictionary = viewModel.showDictionary,
        onClickDictionary = { navigator.push(TopLevelRoute.Dictionary) },
        // KUN <--
        onClickDownloadQueue = { navigator.push(DownloadQueueRoute) },
        onClickCategories = { navigator.push(CategoryRoute) },
        onClickStats = { navigator.push(StatsRoute) },
        onClickDataAndStorage = {
            navigator.openSettings(SettingsDataScreen, isTabletUi)
        },
        onClickSettings = { navigator.openSettings(twoPane = isTabletUi) },
        onClickAbout = { navigator.openSettings(AboutRoute, isTabletUi) },
        // SY -->
        onClickBatchAdd = { navigator.push(BatchAddRoute) },
        onClickUpdates = { navigator.push(UpdatesTabRoute) },
        onClickHistory = { navigator.push(HistoryTabRoute) },
        // SY <--
        // KMK -->
        onClickLibraryUpdateErrors = { navigator.push(LibraryUpdateErrorRoute) },
        // KMK <--
    )

    ResultEffect<Unit>(resultKey = TopLevelRoute.More.reselectEventKey) {
        navigator.openSettings(twoPane = isTabletUi)
    }

    // AM (DISCORD) -->
    LaunchedEffect(Unit) {
        with(DiscordRPCService) {
            discordScope.launchIO { setScreen(context, DiscordScreen.MORE) }
        }
    }
    // <-- AM (DISCORD)
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MoreViewModel(
    private val downloadManager: DownloadManager,
    preferences: BasePreferences,
    // SY -->
    uiPreferences: UiPreferences,
    // SY <--
    // KUN -->
    dictionaryOcrPreferences: DictionaryOcrPreferences,
    // KUN <--
) : ViewModel() {

    var downloadedOnly by preferences.downloadedOnly.asState(viewModelScope)
    var incognitoMode by preferences.incognitoMode.asState(viewModelScope)

    // SY -->
    val showNavUpdates by uiPreferences.showNavUpdates.asState(viewModelScope)
    val showNavHistory by uiPreferences.showNavHistory.asState(viewModelScope)
    // SY <--
    // KUN -->
    val showNavDictionary by uiPreferences.showNavDictionary.asState(viewModelScope)
    val dictionaryOcrEnabled by dictionaryOcrPreferences.enabled.asState(viewModelScope)
    val showDictionary: Boolean
        get() = dictionaryOcrEnabled && !showNavDictionary
    // KUN <--

    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(DownloadQueueState.Stopped)
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    init {
        // Handle running/paused status change and queue progress updating
        viewModelScope.launch {
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
            ) { isRunning, downloadQueue -> Pair(isRunning, downloadQueue.size) }
                .collectLatest { (isDownloading, downloadQueueSize) ->
                    val pendingDownloadExists = downloadQueueSize != 0
                    _downloadQueueState.value = when {
                        !pendingDownloadExists -> DownloadQueueState.Stopped
                        !isDownloading -> DownloadQueueState.Paused(downloadQueueSize)
                        else -> DownloadQueueState.Downloading(downloadQueueSize)
                    }
                }
        }
    }
}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}
