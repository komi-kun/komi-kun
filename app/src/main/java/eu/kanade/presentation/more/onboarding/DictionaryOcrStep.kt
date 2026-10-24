// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The dictionary/OCR opt-in as a step of the existing onboarding flow (fresh install).
//
// The step itself is the answer: flipping the switch writes the decision immediately, and finishing
// onboarding records whatever is showing (see `DictionaryOcrSetupPolicy.recordOnboardingFinished`),
// so leaving it off is an explicit decline rather than an unanswered question.
// KUN <--
package eu.kanade.presentation.more.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.presentation.core.util.collectAsState

internal class DictionaryOcrStep(
    private val preferences: DictionaryOcrPreferences,
) : OnboardingStep {

    // Never blocks "Next": declining is a valid answer.
    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val enabled by preferences.enabled.collectAsState()

        DictionaryOcrSetupContent(
            enabled = enabled,
            onEnabledChange = { preferences.choose(it) },
        )
    }
}
