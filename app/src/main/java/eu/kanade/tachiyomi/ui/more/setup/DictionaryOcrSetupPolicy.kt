// SPDX-License-Identifier: Apache-2.0
// KUN -->
// Where, if anywhere, the combined dictionary/OCR opt-in is asked for.
//
// The features are default-off and gated at runtime by `DictionaryOcrPreferences`
// (`pref_dictionary_ocr_enabled`). This policy is the *only* place that decides which surface asks
// for the answer, and it is deliberately pure so the five product states can be unit-tested without
// Compose or Android:
//
// | onboarding complete | choice stored | surface          |
// |---------------------|---------------|------------------|
// | no (fresh install)  | no            | onboarding step  |
// | no                  | yes           | onboarding step  |
// | yes (pre-feature)   | no            | upgrade screen   |
// | yes                 | yes           | none             |
//
// `BasePreferences.shownOnboardingFlow` is the discriminator: it is false for a fresh install and
// true for every install that already finished onboarding before this feature existed. There is no
// versionCode-based migration in play — the nightly `versionCode` is pinned at 83, so a
// `oldVersion < newVersion` migration would never fire for anyone; the existing runtime preference
// architecture is what carries the one-time prompt instead.
//
// Nothing here ever *chooses* for the user. `resolve` is read-only, and both writes below record an
// explicit answer (`true` or `false`) so a decline is never re-asked and an accept is never reset.
// KUN <--
package eu.kanade.tachiyomi.ui.more.setup

import tachiyomi.core.common.preference.DictionaryOcrPreferences

/** The surface that asks for the dictionary/OCR opt-in, if any. */
enum class DictionaryOcrSetupSurface {
    /** Nothing to ask: the user already answered (or onboarding completes and records the answer). */
    NONE,

    /** A step inside the existing onboarding flow (fresh install). */
    ONBOARDING_STEP,

    /** The standalone one-time screen shown on the first launch after a pre-feature upgrade. */
    UPGRADE_SCREEN,
}

/**
 * The resolved setup state: which [surface] asks, and the value the feature has right now (which is
 * what the surface starts from — never a legacy usage flag, so a pre-feature install is not silently
 * opted in).
 */
data class DictionaryOcrSetupState(
    val surface: DictionaryOcrSetupSurface,
    val enabled: Boolean,
)

object DictionaryOcrSetupPolicy {

    /**
     * The one decision function. [hasChosen] is the explicit-answer flag (the key is set), so
     * `hasChosen == false` means "never asked", not "declined".
     */
    fun resolve(
        onboardingComplete: Boolean,
        hasChosen: Boolean,
        enabled: Boolean,
    ): DictionaryOcrSetupState = when {
        // Fresh install: the choice is a step of the onboarding flow it is about to run.
        !onboardingComplete -> DictionaryOcrSetupState(DictionaryOcrSetupSurface.ONBOARDING_STEP, enabled)
        // Upgraded install that has never been asked: the same content, once, as a standalone screen.
        !hasChosen -> DictionaryOcrSetupState(DictionaryOcrSetupSurface.UPGRADE_SCREEN, enabled)
        // Answered (accepted or declined): never ask again, never rewrite the answer.
        else -> DictionaryOcrSetupState(DictionaryOcrSetupSurface.NONE, enabled)
    }

    /** [resolve] against the real preferences. Read-only: it never writes the choice. */
    fun resolve(
        preferences: DictionaryOcrPreferences,
        onboardingComplete: Boolean,
    ): DictionaryOcrSetupState = resolve(
        onboardingComplete = onboardingComplete,
        hasChosen = preferences.hasChosen(),
        enabled = preferences.isEnabled(),
    )

    /**
     * Whether the standalone upgrade screen must be pushed *now*.
     *
     * [resolve] says which surface owns the question; this is the `MainActivity` call-site guard
     * that keeps it to one push: a fresh install is answered inside onboarding (never here), the
     * screen is not pushed twice in one process ([alreadyShown]) and not on top of itself
     * ([alreadyOnScreen]). Once the user accepts or declines, [resolve] reports [DictionaryOcrSetupSurface.NONE]
     * and this is false forever.
     */
    fun shouldShowUpgradeScreen(
        state: DictionaryOcrSetupState,
        alreadyShown: Boolean,
        alreadyOnScreen: Boolean,
    ): Boolean =
        state.surface == DictionaryOcrSetupSurface.UPGRADE_SCREEN && !alreadyShown && !alreadyOnScreen

    /**
     * Onboarding finished: whatever the step is showing becomes the stored answer, so "left off" is
     * an explicit decline instead of an unasked question. A choice already written by the step's own
     * switch is kept (recordCurrentChoice only fills in an unset key).
     */
    fun recordOnboardingFinished(preferences: DictionaryOcrPreferences) {
        preferences.recordCurrentChoice()
    }

    /**
     * The single "onboarding is over" step, in the only correct order: record the answer **first**,
     * then mark onboarding complete. That order is what guarantees a fresh install never sees the
     * post-upgrade prompt — by the time `shownOnboardingFlow` flips (and this policy would otherwise
     * resolve `UPGRADE_SCREEN`), the choice is already stored, so it resolves `NONE`.
     */
    fun finishOnboarding(
        preferences: DictionaryOcrPreferences,
        markOnboardingComplete: () -> Unit,
    ) {
        recordOnboardingFinished(preferences)
        markOnboardingComplete()
    }

    /**
     * The upgrade screen's answer. Both values are stored: accept enables the feature, decline stores
     * an explicit `false` so the screen is never shown again. The user can still change either
     * decision later from Settings.
     */
    fun recordAnswer(preferences: DictionaryOcrPreferences, accepted: Boolean) {
        preferences.choose(accepted)
    }
}
