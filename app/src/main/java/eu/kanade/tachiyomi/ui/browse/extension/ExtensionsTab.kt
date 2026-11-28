package eu.kanade.tachiyomi.ui.browse.extension

import androidx.activity.compose.BackHandler
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.browse.PrivateExtensionUninstallDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import mihon.core.navigation.ExtensionDetailsRoute
import mihon.core.navigation.ExtensionFilterRoute
import mihon.core.navigation.ExtensionStoresRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.WebViewRoute
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.`18UpRating`
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun extensionsTab(
    extensionsViewModel: ExtensionsViewModel,
): TabContent {
    val navigator = LocalNavigator.current
    val context = LocalContext.current

    val updatesCount by extensionsViewModel.updatesCount.collectAsStateWithLifecycle()
    // KMK -->
    val nsfwOnly by extensionsViewModel.nsfwOnly.collectAsStateWithLifecycle()
    // KMK <--
    var privateExtensionToUninstall by remember { mutableStateOf<Extension.Installed?>(null) }

    return TabContent(
        titleRes = MR.strings.label_extensions,
        badgeNumber = updatesCount.takeIf { it > 0 },
        searchEnabled = true,
        actions = listOf(
            // KMK -->
            AppBar.Action(
                title = stringResource(KMR.strings.action_toggle_nsfw_only),
                icon = MaterialSymbols.Rounded.`18UpRating`,
                iconTint = if (nsfwOnly) MaterialTheme.colorScheme.error else LocalContentColor.current,
                onClick = { extensionsViewModel.toggleNsfwOnly() },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_webview_refresh),
                onClick = extensionsViewModel::findAvailableExtensions,
            ),
            // KMK <--
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_filter),
                onClick = { navigator.push(ExtensionFilterRoute) },
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.extensionStores),
                onClick = { navigator.push(ExtensionStoresRoute) },
            ),
        ),
        content = { contentPadding, _ ->
            val state by extensionsViewModel.state.collectAsStateWithLifecycle()

            BackHandler(enabled = state.searchQuery != null) {
                extensionsViewModel.search(null)
            }
            ExtensionScreen(
                state = state,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onLongClickItem = { extension ->
                    when (extension) {
                        is Extension.Available -> extensionsViewModel.installExtension(extension)
                        is Extension.Installed -> {
                            if (context.isPackageInstalled(extension.pkgName)) {
                                extensionsViewModel.uninstallExtension(extension)
                            } else {
                                privateExtensionToUninstall = extension
                            }
                        }
                    }
                },
                onClickItemCancel = extensionsViewModel::cancelInstallUpdateExtension,
                onClickUpdateAll = extensionsViewModel::updateAllExtensions,
                onOpenWebView = { extension ->
                    extension.sources.getOrNull(0)?.let {
                        navigator.push(
                            WebViewRoute(
                                url = it.baseUrl,
                                initialTitle = it.name,
                                sourceId = it.id,
                            ),
                        )
                    }
                },
                onInstallExtension = extensionsViewModel::installExtension,
                onOpenExtension = { navigator.push(ExtensionDetailsRoute(it.pkgName)) },
                onTrustExtension = { extensionsViewModel.trustExtension(it) },
                onUninstallExtension = { extensionsViewModel.uninstallExtension(it) },
                onUpdateExtension = extensionsViewModel::updateExtension,
                onRefresh = extensionsViewModel::findAvailableExtensions,
            )

            privateExtensionToUninstall?.let { extension ->
                PrivateExtensionUninstallDialog(
                    extension = extension,
                    onConfirm = {
                        extensionsViewModel.uninstallExtension(extension)
                    },
                    onDismissRequest = {
                        privateExtensionToUninstall = null
                    },
                )
            }
        },
    )
}
