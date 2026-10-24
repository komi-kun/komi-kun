package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.onboarding.OnboardingScreen
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.more.setup.DictionaryOcrSetupPolicy
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import mihon.app.di.appGraph
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

class OnboardingScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val basePreferences = remember { context.appGraph.basePreferences }
        val shownOnboardingFlow by basePreferences.shownOnboardingFlow.collectAsState()
        // KUN -->
        val dictionaryOcrPreferences = remember { context.appGraph.dictionaryOcrPreferences }
        // KUN <--

        val finishOnboarding: () -> Unit = {
            // KUN -->
            // One policy call couples the answer and the completion flag in the only correct order
            // (answer first), so a fresh install can never fall through to the post-upgrade prompt.
            DictionaryOcrSetupPolicy.finishOnboarding(dictionaryOcrPreferences) {
                basePreferences.shownOnboardingFlow.set(true)
            }
            // KUN <--
            navigator.pop()
        }

        val restoreSettingKey = stringResource(SettingsDataScreen.restorePreferenceKeyString)

        BackHandler(enabled = !shownOnboardingFlow) {
            // Prevent exiting if onboarding hasn't been completed
        }

        OnboardingScreen(
            onComplete = finishOnboarding,
            onRestoreBackup = {
                finishOnboarding()
                SearchableSettings.highlightKey = restoreSettingKey
                navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage))
            },
            // KUN -->
            dictionaryOcrPreferences = dictionaryOcrPreferences,
            // KUN <--
        )
    }
}
