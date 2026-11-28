// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The dictionary-tab navigation rules `HomeScreen` applies, executed as pure decisions (no Compose
// harness exists, so the policy is what the navigation calls), plus a **source-level call-site
// guard** (not a behavioural test) that those call sites still go through it and that the tab is
// still part of the tab list and the tab host's entry provider. **Residual gap:** no test here
// composes the bottom bar/rail or sends a real `TabEvent`, so the decision being called is guarded
// but its effect on a real navigation graph is not.
//
// There is **no** dictionary shortcut or intent action in this repo today: the direct-event fallback
// exists because `TabEvent.Dictionary` can be sent by any caller even while the opt-in is off.
// KUN <--
package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class DictionaryTabNavigationPolicyTest {

    @Test
    fun `a direct dictionary request falls back to the library while the feature is off or nav bar is disabled`() {
        // A `TabEvent.Dictionary` must not select a tab the navigation filter hides.
        DictionaryTabNavigationPolicy.directEventTarget(enabled = false, showInNavBar = false) shouldBe DictionaryTabTarget.LIBRARY
        DictionaryTabNavigationPolicy.directEventTarget(enabled = false, showInNavBar = true) shouldBe DictionaryTabTarget.LIBRARY
        DictionaryTabNavigationPolicy.directEventTarget(enabled = true, showInNavBar = false) shouldBe DictionaryTabTarget.LIBRARY
        DictionaryTabNavigationPolicy.directEventTarget(enabled = true, showInNavBar = true) shouldBe DictionaryTabTarget.DICTIONARY
    }

    @Test
    fun `the selected dictionary tab moves to the library when disabled or removed from nav`() {
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = true, enabled = false, showInNavBar = true) shouldBe true
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = true, enabled = true, showInNavBar = false) shouldBe true
        // Nothing moves while the feature is on and nav bar is enabled...
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = true, enabled = true, showInNavBar = true) shouldBe false
        // ...and no other tab is disturbed by the switch.
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = false, enabled = false, showInNavBar = false) shouldBe false
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = false, enabled = true, showInNavBar = false) shouldBe false
        DictionaryTabNavigationPolicy.mustFallBackToLibrary(currentIsDictionary = false, enabled = true, showInNavBar = true) shouldBe false
    }

    @Test
    fun `the dictionary tab is only part of the navigation while the feature and nav bar are on`() {
        DictionaryTabNavigationPolicy.isTabVisible(enabled = false, showInNavBar = false) shouldBe false
        DictionaryTabNavigationPolicy.isTabVisible(enabled = false, showInNavBar = true) shouldBe false
        DictionaryTabNavigationPolicy.isTabVisible(enabled = true, showInNavBar = false) shouldBe false
        DictionaryTabNavigationPolicy.isTabVisible(enabled = true, showInNavBar = true) shouldBe true
    }

    @Test
    fun `only the dictionary tab follows the opt-in and nav bar preference`() {
        val tabs = listOf(
            TopLevelRoute.Library,
            TopLevelRoute.Updates,
            TopLevelRoute.Dictionary,
            TopLevelRoute.More,
        )

        // While off or not in nav bar, exactly the dictionary tab drops out of the navigation...
        tabs.filter { DictionaryTabNavigationPolicy.isVisible(it, enabled = false, showInNavBar = false) } shouldBe
            listOf(TopLevelRoute.Library, TopLevelRoute.Updates, TopLevelRoute.More)
        tabs.filter { DictionaryTabNavigationPolicy.isVisible(it, enabled = false, showInNavBar = true) } shouldBe
            listOf(TopLevelRoute.Library, TopLevelRoute.Updates, TopLevelRoute.More)
        tabs.filter { DictionaryTabNavigationPolicy.isVisible(it, enabled = true, showInNavBar = false) } shouldBe
            listOf(TopLevelRoute.Library, TopLevelRoute.Updates, TopLevelRoute.More)
        // ...and while both on, nothing does.
        tabs.filter { DictionaryTabNavigationPolicy.isVisible(it, enabled = true, showInNavBar = true) } shouldBe tabs
    }

    // ---------------------------------------------------------------- call-site guard (source text)

    /**
     * **Source-level guard, not a behavioural test.** The rules above only matter if the navigation
     * calls them, and the call sites live in Compose code with no harness here, so this reads the
     * file and tolerates any whitespace between tokens. It asserts the uses a refactor can silently
     * drop: HomeScreen's shared tab filter (without it the tab stays in the bottom bar and the rail
     * for every opted-out user), the direct-event fallback, the selected-tab fallback, **and** that
     * the tab is still in `TABS` and registered in the tab host's `entryProvider` (a `TopLevelRoute`
     * that nobody lists or dispatches silently disappears). Whether the filtered list actually
     * reaches the navigation UI at runtime stays unverified.
     */
    @Test
    fun `source guard - HomeScreen calls the policy and still routes the dictionary tab`() {
        val home = File("src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt").readText()
        // Argument order is not asserted (the parameter names make a swap a compile error anyway).
        home shouldContainSource """DictionaryTabNavigationPolicy\.isVisible\(\s*it\s*,\s*dictionaryOcrEnabled\s*,\s*showNavDictionary\s*\)"""
        home shouldContainSource """DictionaryTabNavigationPolicy\.directEventTarget\(\s*dictionaryOcrEnabled\s*,\s*showNavDictionary\s*\)"""
        home shouldContainSource """DictionaryTabNavigationPolicy\.mustFallBackToLibrary\("""
        // The tab itself must stay in the tab list — pinned inside the `TABS` declaration, because
        // `TopLevelRoute.Dictionary,` also occurs in an unrelated argument (`currentIsDictionary = …`),
        // so a bare match stays green when the tab is dropped from the list — and in the tab host's
        // entry provider.
        home shouldContainSource """private val TABS: List<TopLevelRoute> = listOf\([^)]*TopLevelRoute\.Dictionary,[^)]*\)"""
        home shouldContainSource """entry<TopLevelRoute\.Dictionary>"""
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
