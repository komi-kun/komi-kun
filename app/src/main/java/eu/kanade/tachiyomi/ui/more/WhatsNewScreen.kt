package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.WhatsNewScreen
import eu.kanade.tachiyomi.util.system.openInBrowser
import mihon.core.navigation.LocalNavigator

@Composable
fun WhatsNewScreen(
    currentVersion: String,
    versionName: String,
    changelogInfo: String,
    releaseLink: String,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val changelogInfoNoChecksum = remember {
        changelogInfo
    }

    WhatsNewScreen(
        currentVersion = currentVersion,
        versionName = versionName,
        changelogInfo = changelogInfoNoChecksum,
        onOpenInBrowser = { context.openInBrowser(releaseLink) },
        onAcceptUpdate = { navigator.pop() },
    )
}
