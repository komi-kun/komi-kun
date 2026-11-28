package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import mihon.core.navigation.LocalNavigator

@Composable
fun NewUpdateScreen(
    versionName: String,
    changelogInfo: String,
    releaseLink: String,
    downloadLink: String,
    // KUN -->
    digest: String? = null,
    // KUN <--
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel = assistedMetroViewModel<NewUpdateViewModel, NewUpdateViewModel.Factory> {
        create(
            versionName = versionName,
            changelogInfo = changelogInfo,
            downloadLink = downloadLink,
            digest = digest,
        )
    }
    val state by viewModel.state.collectAsState()

    NewUpdateScreen(
        versionName = versionName,
        stage = state.stage,
        downloadProgress = { state.downloadProgress },
        changelogInfo = state.changelogInfo,
        onOpenInBrowser = { context.openInBrowser(releaseLink) },
        onAcceptUpdate = {
            when (state.stage) {
                NewUpdateViewModel.Stage.Available, NewUpdateViewModel.Stage.Failed -> viewModel.startDownload()
                NewUpdateViewModel.Stage.Downloaded -> viewModel.installUpdate()
                else -> Unit
            }
        },
        onRejectUpdate = navigator::pop,
        onSkipUpdate = {
            viewModel.skipUpdate(versionName)
            navigator.pop()
        },
    )
}
