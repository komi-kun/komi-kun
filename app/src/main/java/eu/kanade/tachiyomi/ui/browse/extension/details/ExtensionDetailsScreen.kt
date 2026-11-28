package eu.kanade.tachiyomi.ui.browse.extension.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.ExtensionDetailsScreen
import eu.kanade.presentation.browse.PrivateExtensionUninstallDialog
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.SourcePreferencesRoute
import mihon.core.navigation.WebViewRoute
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun ExtensionDetailsScreen(pkgName: String) {
    val viewModel =
        assistedMetroViewModel<ExtensionDetailsViewModel, ExtensionDetailsViewModel.Factory> {
            create(pkgName = pkgName)
        }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showUninstallDialog by remember { mutableStateOf(false) }

    val navigator = LocalNavigator.current

    when (val state = state) {
        ExtensionDetailsViewModel.State.Loading -> LoadingScreen()
        ExtensionDetailsViewModel.State.Uninstalled -> {
            LaunchedEffect(Unit) { navigator.pop() }
            EmptyScreen(MR.strings.empty_screen)
        }
        is ExtensionDetailsViewModel.State.Success -> {
            // KMK -->
            val source = state.extension.sources.getOrNull(0)
            // KMK <--
            ExtensionDetailsScreen(
                navigateUp = navigator::pop,
                state = state,
                onClickSourcePreferences = { navigator.push(SourcePreferencesRoute(it)) },
                // KMK -->
                onOpenWebView = if (source is HttpSource) {
                    {
                        navigator.push(
                            WebViewRoute(
                                url = source.baseUrl,
                                initialTitle = source.name,
                                sourceId = source.id,
                            ),
                        )
                    }
                } else {
                    null
                },
                // KMK <--
                onClickEnableAll = { viewModel.toggleSources(true) },
                onClickDisableAll = { viewModel.toggleSources(false) },
                onClickClearCookies = viewModel::clearCookies,
                // KUN -->
                // An extension that is not a separate package (installed privately) gets a confirmation
                onClickUninstall = {
                    if (context.isPackageInstalled(state.extension.pkgName)) {
                        viewModel.uninstallExtension()
                    } else {
                        showUninstallDialog = true
                    }
                },
                // KUN <--
                onClickSource = viewModel::toggleSource,
                onClickIncognito = viewModel::toggleIncognito,
            )

            if (showUninstallDialog) {
                PrivateExtensionUninstallDialog(
                    extension = state.extension,
                    onConfirm = viewModel::uninstallExtension,
                    onDismissRequest = {
                        showUninstallDialog = false
                    },
                )
            }
        }
    }
}
