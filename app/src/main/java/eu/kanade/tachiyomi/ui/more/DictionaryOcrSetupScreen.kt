// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The one-time dictionary/OCR opt-in shown on the first launch after a pre-feature upgrade.
//
// It is *not* a standalone settings shortcut: `MainActivity` pushes it at most once, only while
// `BasePreferences.shownOnboardingFlow` is true (an install that already finished onboarding) and
// `DictionaryOcrPreferences.hasChosen()` is false (never asked). Both buttons store an explicit
// answer, and there is no dismiss path — back and every "dismiss" affordance are blocked — so the
// prompt cannot be left half-asked.
//
// It renders the same `DictionaryOcrSetupContent` the onboarding step renders.
// KUN <--
package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.onboarding.DictionaryOcrSetupContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.more.setup.DictionaryOcrSetupPolicy
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen

class DictionaryOcrSetupScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val preferences = remember(context) { context.appGraph.dictionaryOcrPreferences }

        // Starts from the stored value (off on a fresh upgrade, since nothing auto-enables it) and
        // only the accept button commits it.
        var enabled by rememberSaveable { mutableStateOf(preferences.isEnabled()) }

        // No dismiss without an answer: back is swallowed, and InfoScreen is given both actions.
        BackHandler(enabled = true) {
            // Intentionally empty; the user must accept or decline.
        }

        InfoScreen(
            icon = MaterialSymbols.Rounded.Book,
            headingText = stringResource(KUNMR.strings.dictionary_ocr_setup_title),
            subtitleText = stringResource(KUNMR.strings.dictionary_ocr_setup_description),
            acceptText = stringResource(KUNMR.strings.dictionary_ocr_setup_accept),
            // Enabling is the accept path, so it requires the switch to be on: the button can never
            // write a value the user did not see selected.
            canAccept = enabled,
            onAcceptClick = {
                DictionaryOcrSetupPolicy.recordAnswer(preferences, accepted = true)
                navigator.pop()
            },
            rejectText = stringResource(KUNMR.strings.dictionary_ocr_setup_decline),
            onRejectClick = {
                // An explicit decline is stored too, so this screen never returns.
                DictionaryOcrSetupPolicy.recordAnswer(preferences, accepted = false)
                navigator.pop()
            },
        ) {
            DictionaryOcrSetupContent(
                enabled = enabled,
                onEnabledChange = { enabled = it },
            )
        }
    }
}
