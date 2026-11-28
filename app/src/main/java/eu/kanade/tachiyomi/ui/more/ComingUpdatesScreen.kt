package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.ComingUpdatesScreen
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.util.system.openInBrowser
import mihon.core.navigation.LocalNavigator

@Composable
fun ComingUpdatesScreen(
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
    val changelogInfoNoChecksum = remember {
        changelogInfo
    }

    ComingUpdatesScreen(
        versionName = versionName,
        changelogInfo = changelogInfoNoChecksum,
        onOpenInBrowser = { context.openInBrowser(releaseLink) },
        onRejectUpdate = navigator::pop,
        onAcceptUpdate = {
            AppUpdateDownloadJob.start(
                context = context,
                url = downloadLink,
                title = versionName,
                // KUN -->
                digest = digest,
                // KUN <--
            )
            navigator.pop()
        },
    )
}
