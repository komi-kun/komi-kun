// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The Dictionary tab's place in the navigation, as pure decisions.
//
// The tab is filtered out of the navigation while the combined dictionary/OCR opt-in is off
// (`DictionaryTab.isEnabled()` delegates to [DictionaryTabNavigationPolicy.isTabVisible], and
// `HomeScreen` applies the same [isTabVisible] rule in its tab filter). Two paths could still land
// on it, and both are decided here so they can be unit-tested without a Compose harness:
//
//  * a **direct** `HomeScreen.openTab(HomeScreen.Tab.Dictionary)` request must fall back to the
//    library instead of selecting a tab that is not in the navigation. Nothing in this repo produces
//    one today (there is no dictionary shortcut and no intent action for it — this is *not* a ported
//    Screen and no `Constants.SHORTCUT_*` maps to it); `openTab` is public, so the fallback keeps any
//    caller — including a future shortcut — from selecting a hidden tab;
//  * the tab may be the **selected** one when the switch is turned off, and must then move away.
// KUN <--
package eu.kanade.tachiyomi.ui.home

import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.dictionary.DictionaryTab

/** Where a direct dictionary-tab request lands. */
enum class DictionaryTabTarget {
    DICTIONARY,
    LIBRARY,
}

object DictionaryTabNavigationPolicy {

    /**
     * Whether the Dictionary tab is part of the navigation at all while the opt-in is [enabled].
     * `DictionaryTab.isEnabled()` and the `HomeScreen` tab filter both read this, so the tab cannot
     * be visible in one layout and missing from the other.
     */
    fun isTabVisible(enabled: Boolean): Boolean = enabled

    /**
     * Whether [tab] survives [enabled] in the tab list: the Dictionary tab follows the opt-in, every
     * other tab keeps its own `Tab.isEnabled()` decision (applied by the caller).
     */
    fun isVisible(tab: Tab, enabled: Boolean): Boolean = tab !== DictionaryTab || isTabVisible(enabled)

    /**
     * A direct `HomeScreen.Tab.Dictionary` request: honour it only while the feature is on,
     * otherwise open the library. (While off there is no visible Dictionary tab to land on.)
     */
    fun directEventTarget(enabled: Boolean): DictionaryTabTarget =
        if (enabled) {
            DictionaryTabTarget.DICTIONARY
        } else {
            DictionaryTabTarget.LIBRARY
        }

    /**
     * Whether the currently selected tab must fall back to the library because the feature was just
     * switched off under it. Off does not disturb any other tab.
     */
    fun mustFallBackToLibrary(currentIsDictionary: Boolean, enabled: Boolean): Boolean =
        currentIsDictionary && !enabled
}
