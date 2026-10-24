// SPDX-License-Identifier: Apache-2.0
// KUN -->
// komi-kun's single opt-in switch for the immersion features (dictionary lookup + OCR). The
// settings prompt writes it; the GPL `:core:ocr` / `:core:dictionary` services read it.
//
// It lives in the Apache-2.0 `:core:common` module (not in either GPL module) so the GPL services,
// the app settings screen and the GPL-free seam files can all share exactly one key without
// importing GPL code (contract §1.3 / §1.9).
//
// This file is a **registered** GPL-free seam file (contract §1.3 and `scripts/verify-gpl-free.sh`):
// `ReaderActivity`/`ReaderViewModel` (Apache, GPL-free) gate on it, so it must keep its Apache header
// and must never import a GPL package. Do not relicense it to GPL — that would put GPL code behind a
// GPL-free seam file.
//
// **One key is both the master state and the explicit-choice flag.** [DictionaryOcrPreferences.hasChosen]
// (`isSet()`) distinguishes "never asked" (unset, default `false` → the app may prompt) from "asked
// and declined" (`false` but *set* → never prompt again). [DictionaryOcrPreferences.recordCurrentChoice]
// only writes when nothing is stored, so an accepted (`true`) or declined (`false`) choice is never
// rewritten by a later launch and survives upgrades.
// KUN <--
package tachiyomi.core.common.preference

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The dictionary/OCR opt-in preference (komi-kun's own key, [KEY_ENABLED]).
 *
 * Off by default and inert until the user explicitly accepts, which is what lets the immersion
 * features stay dark for anyone who never asked for them. A stored value is the user's decision:
 * nothing here ever resets it.
 */
@Inject
@SingleIn(AppScope::class)
class DictionaryOcrPreferences(
    preferenceStore: PreferenceStore,
) {
    /** Master switch for the dictionary/OCR features; `false` until the user opts in. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean(KEY_ENABLED, false)

    /** Whether the features may run right now. */
    fun isEnabled(): Boolean = enabled.get()

    /** True once an explicit choice (accept *or* decline) has been stored. */
    fun hasChosen(): Boolean = enabled.isSet()

    /** Stores an explicit accept (`true`) or decline (`false`) so the app never asks again. */
    fun choose(value: Boolean) {
        enabled.set(value)
    }

    /**
     * Persists the current value **only when no choice is stored yet**, turning "never asked" into an
     * explicit decision. It never overwrites a stored accept/decline, so calling it on every launch
     * cannot reset the user's choice.
     */
    fun recordCurrentChoice() {
        if (enabled.isSet()) return
        enabled.set(enabled.get())
    }

    companion object {
        /** The single preference key, shared by the app and the GPL services. */
        const val KEY_ENABLED = "pref_dictionary_ocr_enabled"
    }
}
