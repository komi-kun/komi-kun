// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The one-time dictionary/OCR opt-in shown on the first launch after a pre-feature upgrade.
//
// It is *not* a standalone settings shortcut: `MainActivity` pushes it at most once, only while
// `BasePreferences.shownOnboardingFlow` is true (an install that already finished onboarding) and
// `DictionaryOcrPreferences.hasChosen()` is false (never asked). Both answers are **buttons of equal
// standing** — "Turn on" and "Keep off" — each storing its value and dismissing the screen in one
// tap, and there is no dismiss path: back and every "dismiss" affordance are blocked, so the prompt
// cannot be left half-asked. There is deliberately no disabled accept either: a greyed-out button
// tied to an in-content switch leaves the user guessing which control actually answers.
//
// It renders the same `DictionaryOcrSetupContent` the onboarding step renders (without the switch,
// since the buttons *are* the answer).
// KUN <--
package eu.kanade.tachiyomi.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

        // No dismiss without an answer: back is swallowed, and both answers are buttons.
        BackHandler(enabled = true) {
            // Intentionally empty; the user must turn it on or keep it off.
        }

        InfoScreen(
            icon = MaterialSymbols.Rounded.Book,
            headingText = stringResource(KUNMR.strings.dictionary_ocr_setup_title),
            subtitleText = stringResource(KUNMR.strings.dictionary_ocr_setup_description),
            acceptText = stringResource(KUNMR.strings.dictionary_ocr_setup_accept),
            // Both buttons are always enabled: each one *is* an answer, so neither may be disabled.
            canAccept = true,
            equalActionButtons = true,
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
                enabled = false,
                onEnabledChange = {},
                showEnableSwitch = false,
            )
        }
    }
}
