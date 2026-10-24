// SPDX-License-Identifier: Apache-2.0
// KUN -->
// The two dictionary-tab navigation fallbacks HomeScreen applies, executed as pure decisions (no
// Compose harness exists, so the policy is what the navigation actually calls).
// KUN <--
package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DictionaryTabNavigationPolicyTest {

    @Test
    fun `a direct dictionary request falls back to the library while the feature is off`() {
        // A shortcut / intent that asks for Tab.Dictionary must not select a tab the navigation
        // filter hides.
        DictionaryTabNavigationPolicy.directEventTarget(enabled = false) shouldBe DictionaryTabTarget.LIBRARY
        DictionaryTabNavigationPolicy.directEventTarget(enabled = true) shouldBe DictionaryTabTarget.DICTIONARY
    }

    @Test
    fun `the selected dictionary tab moves to the library when the switch is turned off`() {
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = true, enabled = false) shouldBe true
        // Nothing moves while the feature is on...
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = true, enabled = true) shouldBe false
        // ...and no other tab is disturbed by the switch.
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = false, enabled = false) shouldBe false
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = false, enabled = true) shouldBe false
    }
}
