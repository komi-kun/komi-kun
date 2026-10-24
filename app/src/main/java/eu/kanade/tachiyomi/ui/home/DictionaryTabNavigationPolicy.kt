// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The two navigation fallbacks for the Dictionary tab, as pure decisions.
//
// The tab is filtered out of the navigation while the combined dictionary/OCR opt-in is off
// (`DictionaryTab.isEnabled()` -> the single `fastFilter` used by both the bottom bar and the rail).
// Two paths could still land on it, and both are decided here so they can be unit-tested without a
// Compose harness:
//
//  * a **direct** `HomeScreen.Tab.Dictionary` request (shortcut / intent) must fall back to the
//    library instead of selecting a tab that is not in the navigation;
//  * the tab may be the **selected** one when the switch is turned off, and must then move away.
// KUN <--
package eu.kanade.tachiyomi.ui.home

/** Where a direct dictionary-tab request lands. */
enum class DictionaryTabTarget {
    DICTIONARY,
    LIBRARY,
}

object DictionaryTabNavigationPolicy {

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
