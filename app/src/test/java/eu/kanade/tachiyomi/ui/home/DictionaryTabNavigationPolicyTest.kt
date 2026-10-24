// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The dictionary-tab navigation rules `HomeScreen` and `DictionaryTab` apply, executed as pure
// decisions (no Compose harness exists, so the policy is what the navigation calls), plus a
// **source-level call-site guard** (not a behavioural test) that those call sites still go through
// it. **Residual gap:** no test here composes the bottom bar/rail or runs `HomeScreen.openTab`, so
// the decision being called is guarded but its effect on a real navigation graph is not.
//
// There is **no** dictionary shortcut or intent action in this repo today: the direct-event fallback
// exists because `HomeScreen.openTab(tab)` is public, so any caller can ask for the tab even while
// the opt-in is off.
// KUN <--
package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.dictionary.DictionaryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class DictionaryTabNavigationPolicyTest {

    @Test
    fun `a direct dictionary request falls back to the library while the feature is off`() {
        // `HomeScreen.openTab(HomeScreen.Tab.Dictionary)` must not select a tab the navigation filter
        // hides.
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

    @Test
    fun `the dictionary tab is only part of the navigation while the feature is on`() {
        // A constant `true`/`false` here would either show the tab to every opted-out user or hide it
        // from every opted-in one.
        DictionaryTabNavigationPolicy.isTabVisible(enabled = false) shouldBe false
        DictionaryTabNavigationPolicy.isTabVisible(enabled = true) shouldBe true
    }

    @Test
    fun `only the dictionary tab follows the opt-in`() {
        val tabs = listOf(LibraryTab, UpdatesTab, DictionaryTab, MoreTab)

        // While off, exactly the dictionary tab drops out of the navigation...
        tabs.filter { DictionaryTabNavigationPolicy.isVisible(it, enabled = false) } shouldBe
            listOf(LibraryTab, UpdatesTab, MoreTab)
        // ...and while on, nothing does.
        tabs.filter { DictionaryTabNavigationPolicy.isVisible(it, enabled = true) } shouldBe tabs
    }

    // ---------------------------------------------------------------- call-site guard (source text)

    /**
     * **Source-level guard, not a behavioural test.** The rules above only matter if the navigation
     * calls them, and the call sites live in Compose code with no harness here, so this reads the
     * two files and tolerates any whitespace between tokens. It asserts the uses a refactor can
     * silently drop: the tab's own gate, and HomeScreen's shared tab filter (without it the tab stays
     * in the bottom bar and the rail for every opted-out user). Whether the filtered list actually
     * reaches the navigation UI at runtime stays unverified.
     */
    @Test
    fun `source guard - DictionaryTab and HomeScreen call the policy`() {
        val tab = File("src/main/java/eu/kanade/tachiyomi/ui/dictionary/DictionaryTab.kt").readText()
        tab shouldContainSource """DictionaryTabNavigationPolicy\.isTabVisible\(\s*enabled\s*\)"""

        val home = File("src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt").readText()
        // Argument order is not asserted (the parameter names make a swap a compile error anyway).
        home shouldContainSource """DictionaryTabNavigationPolicy\.isVisible\("""
        home shouldContainSource """DictionaryTabNavigationPolicy\.directEventTarget\("""
        home shouldContainSource """DictionaryTabNavigationPolicy\.mustFallBackToLibrary\("""
    }
}

/**
 * Whitespace-tolerant source assertion: [pattern] must occur somewhere in this text. Local because
 * kotest's `shouldMatch` requires a match against the *whole* string, which a `.kt` file never is.
 */
private infix fun String.shouldContainSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        fail("expected the source to contain /$pattern/")
    }
}
// KUN <--
