// SPDX-License-Identifier: GPL-3.0-or-later
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
import org.junit.jupiter.api.fail
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

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
        // An upgraded install has the legacy key stored (the master key is genuinely unset), so seed
        // that one key the way the old settings screen left it.
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
        val prefs = preferences()
        prefs.choose(true)

        // Recording on a later launch must not touch a stored accept.
        DictionaryOcrSetupPolicy.recordOnboardingFinished(prefs)

        prefs.isEnabled() shouldBe true
        DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true).surface shouldBe
            DictionaryOcrSetupSurface.NONE
    }

    @Test
    fun `a post-choice upgrade does not revert a stored decline to unset`() {
        val prefs = preferences()
        prefs.choose(false)

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

    // ---------------------------------------------------------------- MainActivity call-site guard

    @Test
    fun `a fresh install never falls through to the upgrade prompt`() {
        val prefs = preferences()

        // What `OnboardingScreen`'s finish action does, in the order it does it.
        var onboardingComplete = false
        DictionaryOcrSetupPolicy.finishOnboarding(prefs) { onboardingComplete = true }

        val setup = DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete)
        setup.surface shouldBe DictionaryOcrSetupSurface.NONE
        DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(
            state = setup,
            alreadyShown = false,
            alreadyOnScreen = false,
        ) shouldBe false
    }

    @Test
    fun `a pre-feature upgrade pushes the screen exactly once`() {
        val prefs = preferences()
        val setup = DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true)

        // First composition: push.
        DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(setup, alreadyShown = false, alreadyOnScreen = false) shouldBe
            true
        // A configuration change while the screen is up: already pushed and already showing.
        DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(setup, alreadyShown = true, alreadyOnScreen = true) shouldBe
            false
        // Backed out without answering: still not pushed a second time this process.
        DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(setup, alreadyShown = true, alreadyOnScreen = false) shouldBe
            false
        // Only `alreadyOnScreen`, e.g. a re-entrant effect: no second copy.
        DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(setup, alreadyShown = false, alreadyOnScreen = true) shouldBe
            false
    }

    @Test
    fun `accepting or declining suppresses the upgrade prompt for good`() {
        for (accepted in listOf(true, false)) {
            val prefs = preferences()
            // Pre-feature install: the screen is what asks.
            DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(
                state = DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true),
                alreadyShown = false,
                alreadyOnScreen = false,
            ) shouldBe true

            DictionaryOcrSetupPolicy.recordAnswer(prefs, accepted)

            // After the answer, a later launch resolves NONE and pushes nothing.
            DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(
                state = DictionaryOcrSetupPolicy.resolve(prefs, onboardingComplete = true),
                alreadyShown = false,
                alreadyOnScreen = false,
            ) shouldBe false
        }
    }

    @Test
    fun `an onboarding step never pushes the standalone screen`() {
        // Fresh install, onboarding still running: the answer belongs to the step, not to a push.
        val setup = DictionaryOcrSetupPolicy.resolve(preferences(), onboardingComplete = false)

        setup.surface shouldBe DictionaryOcrSetupSurface.ONBOARDING_STEP
        DictionaryOcrSetupPolicy.shouldShowUpgradeScreen(setup, alreadyShown = false, alreadyOnScreen = false) shouldBe
            false
    }

    // ---------------------------------------------------------------- call-site guard (source text)

    /**
     * **Source-level guard, not a behavioural test.** The ordering and the one-push guard above only
     * hold if the screens go through the policy, and both screens are Compose/navigator-bound with no
     * harness here, so this reads their sources (whitespace-tolerant regexes). `OnboardingScreen` must
     * record through `finishOnboarding` — answer before the completion flag — and must **not** call the
     * bare `recordOnboardingFinished` (that would mark onboarding complete for a fresh install before
     * the answer exists); `MainActivity` must ask `shouldShowUpgradeScreen` instead of inlining the
     * surface comparison. **Residual gap:** the runtime effect (the screen actually being pushed once,
     * with these arguments) is unverified.
     */
    @Test
    fun `source guard - OnboardingScreen and MainActivity call the policy`() {
        val onboarding = File("src/main/java/eu/kanade/tachiyomi/ui/more/OnboardingScreen.kt").readText()
        onboarding shouldContainSource """DictionaryOcrSetupPolicy\.finishOnboarding\("""
        // The flag must be set inside the answer-writing call, never by itself.
        onboarding shouldNotContainSource """DictionaryOcrSetupPolicy\.recordOnboardingFinished\("""

        val main = File("src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt").readText()
        main shouldContainSource """DictionaryOcrSetupPolicy\.shouldShowUpgradeScreen\("""
        main shouldContainSource """DictionaryOcrSetupPolicy\.resolve\("""
    }
}

/**
 * Whitespace-tolerant source assertions: the patterns must occur in the call-site text. Local because
 * kotest's `shouldMatch` requires a match against the *whole* string, which a `.kt` file never is.
 */
private infix fun String.shouldContainSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        fail("expected the source to contain /$pattern/")
    }
}

private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source NOT to contain /$pattern/")
    }
}
