// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The five product states of the combined dictionary/OCR opt-in, driven through the *real*
// preference (`DictionaryOcrPreferences` over an in-memory `PreferenceStore`) rather than a mock, so
// "unset / declined / accepted" are the same `isSet()` semantics the app ships with:
//
//   fresh install            → onboarding step, off
//   pre-feature upgrade      → one-time upgrade screen, off (no legacy auto-enable)
//   post-choice upgrade      → nothing, ever
//   accept                   → stored `true`, nothing more to ask
//   decline                  → stored `false`, nothing more to ask
// KUN <--
package eu.kanade.tachiyomi.ui.more.setup

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class DictionaryOcrSetupPolicyTest {

    private fun preferences(store: InMemoryPreferenceStore = InMemoryPreferenceStore()) =
        DictionaryOcrPreferences(store)

    // ---------------------------------------------------------------- fresh install

    @Test
    fun `a fresh install answers the opt-in as an onboarding step, still unasked`() {
        val prefs = preferences()

        val state = DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = false)

        state.surface shouldBe DictionaryOcrSetupSurface.ONBOARDING_STEP
        state.enabled shouldBe false
        // Reading the policy must not decide for the user: the key stays unset until the step or the
        // finish action writes it.
        prefs.hasChosen() shouldBe false
    }

    @Test
    fun `finishing onboarding without touching the step stores an explicit decline`() {
        val prefs = preferences()

        DictionaryOcrSetupPolicy.recordOnboardingFinished(prefs)

        prefs.hasChosen() shouldBe true
        prefs.isEnabled() shouldBe false
        // Answered: a later launch is a "post-choice upgrade" and is never asked again.
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true).surface shouldBe
            DictionaryOcrSetupSurface.NONE
    }

    @Test
    fun `finishing onboarding keeps a switch the user turned on`() {
        val prefs = preferences()
        prefs.choose(true) // the step's switch writes immediately

        DictionaryOcrSetupPolicy.recordOnboardingFinished(prefs)

        prefs.isEnabled() shouldBe true
        prefs.hasChosen() shouldBe true
    }

    // ---------------------------------------------------------------- pre-feature upgrade

    @Test
    fun `an upgraded install that never chose gets the one-time screen, off`() {
        val prefs = preferences()

        val state = DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true)

        state.surface shouldBe DictionaryOcrSetupSurface.UPGRADE_SCREEN
        state.enabled shouldBe false
        prefs.hasChosen() shouldBe false
    }

    @Test
    fun `legacy reader overlay usage does not auto-enable the feature`() {
        // The in-memory store hands out a *copy* of the seeded preference, so an unseeded
        // `set()` would be discarded; seed the legacy key the way a real upgraded install has it
        // stored, and only that key (the master key must stay genuinely unset).
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    "pref_reader_ocr_overlay_enabled",
                    true,
                    false,
                ),
            ),
        )
        // The seeded legacy value is really visible through the store...
        ReaderPreferences(store).ocrOverlayEnabled.get() shouldBe true
        val prefs = DictionaryOcrPreferences(store)

        val state = DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true)

        // ...and it still does not answer for the user: the master is unset and off.
        state.surface shouldBe DictionaryOcrSetupSurface.UPGRADE_SCREEN
        state.enabled shouldBe false
        prefs.isEnabled() shouldBe false
        prefs.hasChosen() shouldBe false
    }

    @Test
    fun `finishing a fresh install leaves nothing to ask afterwards`() {
        val prefs = preferences()
        var onboardingComplete = false

        DictionaryOcrSetupPolicy.finishOnboarding(prefs) {
            // Ordering matters: the answer must already be stored when onboarding is marked
            // complete, or a fresh install would fall through to the upgrade screen.
            prefs.hasChosen() shouldBe true
            onboardingComplete = true
        }

        onboardingComplete shouldBe true
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete).surface shouldBe
            DictionaryOcrSetupSurface.NONE
    }

    @Test
    fun `finishing onboarding after accepting keeps the accept and asks nothing`() {
        val prefs = preferences()
        prefs.choose(true)
        var onboardingComplete = false

        DictionaryOcrSetupPolicy.finishOnboarding(prefs) { onboardingComplete = true }

        prefs.isEnabled() shouldBe true
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete) shouldBe
            DictionaryOcrSetupState(DictionaryOcrSetupSurface.NONE, enabled = true)
    }

    // ---------------------------------------------------------------- answers

    @Test
    fun `accepting stores true and never asks again`() {
        val prefs = preferences()

        DictionaryOcrSetupPolicy.recordAnswer(prefs, accepted = true)

        prefs.isEnabled() shouldBe true
        prefs.hasChosen() shouldBe true
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true) shouldBe
            DictionaryOcrSetupState(DictionaryOcrSetupSurface.NONE, enabled = true)
    }

    @Test
    fun `declining stores an explicit false and never asks again`() {
        val prefs = preferences()

        DictionaryOcrSetupPolicy.recordAnswer(prefs, accepted = false)

        // `false` here is a stored answer, not "unset": hasChosen is what suppresses the prompt.
        prefs.isEnabled() shouldBe false
        prefs.hasChosen() shouldBe true
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true) shouldBe
            DictionaryOcrSetupState(DictionaryOcrSetupSurface.NONE, enabled = false)
    }

    @Test
    fun `a post-choice upgrade neither resets true nor re-prompts`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    DictionaryOcrPreferences.KEY_ENABLED,
                    true,
                    false,
                ),
            ),
        )
        val prefs = DictionaryOcrPreferences(store)

        // Recording on a later launch must not touch a stored accept.
        DictionaryOcrSetupPolicy.recordOnboardingFinished(prefs)

        prefs.isEnabled() shouldBe true
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true).surface shouldBe
            DictionaryOcrSetupSurface.NONE
    }

    @Test
    fun `a post-choice upgrade does not revert a stored decline to unset`() {
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    DictionaryOcrPreferences.KEY_ENABLED,
                    false,
                    false,
                ),
            ),
        )
        val prefs = DictionaryOcrPreferences(store)

        DictionaryOcrSetupPolicy.recordOnboardingFinished(prefs)

        prefs.hasChosen() shouldBe true
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true).surface shouldBe
            DictionaryOcrSetupSurface.NONE
    }

    // ---------------------------------------------------------------- navigation source of truth

    @Test
    fun `the master switch defaults to off so the tab and reader entries start hidden`() {
        // HomeScreen's tab filter and every reader entry point read exactly this.
        preferences().isEnabled() shouldBe false
    }
}
