// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level layout guard — not a behavioural test.**
//
// The bug this pins crashed the app at startup: the shared opt-in body carried its own
// `Modifier.verticalScroll(...)` while both of its call sites render it inside `InfoScreen`, whose
// content slot is already a scrolling `Column`. The parent measures its children with an infinite
// maximum height, so the nested scrollable died in measure with
//
//   java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity
//   maximum height constraints, which is disallowed.
//     at androidx.compose.foundation.ScrollNode.measure-3p2s80s(Scroll.kt:451)
//
// and `MainActivity` pushing the one-time upgrade prompt took the whole process down.
//
// There is no Robolectric or Compose UI-test harness in this module (see
// `OcrPolicySourceWiringTest`), so the measure-time constraint cannot be exercised from a JVM unit
// test; the crash is caught by reading the composable's source instead. **Residual gap:** that the
// screen now actually lays out and shows on a device is only proven by launching it.
// KUN <--
package eu.kanade.presentation.more.onboarding

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class DictionaryOcrSetupLayoutTest {

    private fun source(path: String): String = File(path).readText().withoutComments()

    /**
     * The defect itself: a scroll container inside `InfoScreen`'s already-scrolling content slot.
     * `verticalScroll` and `LazyColumn` are both rejected here — either one is the same
     * infinite-height measure crash.
     */
    @Test
    fun `source guard - the shared opt-in body has no scroll container of its own`() {
        val content = source(SETUP_CONTENT)

        content shouldNotContainSource """verticalScroll\s*\("""
        content shouldNotContainSource """rememberScrollState\s*\("""
        content shouldNotContainSource """LazyColumn\s*\("""
    }

    /**
     * The premise the rule above rests on: `InfoScreen` is what scrolls, so the body must not. If
     * upstream ever stops scrolling that slot, dropping the nested scroll would silently make the
     * prompt unscrollable on a short screen — this fails so that decision is made deliberately.
     */
    @Test
    fun `source guard - InfoScreen scrolls its content slot`() {
        val infoScreen = source(INFO_SCREEN)

        // `content()` is invoked from inside that scrolling Column (not from a sibling), which is
        // exactly what hands the body an infinite maximum height.
        val scroll = Regex("""verticalScroll\s*\(\s*rememberScrollState\s*\(\s*\)\s*\)""").find(infoScreen)
        val contentCall = Regex("""\bcontent\s*\(\s*\)""").find(infoScreen)
        if (scroll == null) {
            fail("expected InfoScreen's content slot to be a verticalScroll(rememberScrollState())")
        }
        if (contentCall == null || contentCall.range.first <= scroll.range.first) {
            fail("expected `content()` to be invoked after the content slot's verticalScroll")
        }
    }

    /**
     * The upgrade prompt answers with two equally weighted buttons, each enabled, each storing its
     * value and dismissing in one tap. The shape it replaced had a single accept button disabled
     * until an in-content switch was flipped, next to a "Not now" secondary action — two controls
     * answering the same question, one of them silently inert.
     */
    @Test
    fun `source guard - the upgrade prompt offers two enabled answers that both store the choice`() {
        val screen = source(SETUP_SCREEN)

        // Equal standing: the shared button bar puts both answers in one weighted row...
        screen shouldContainSource """equalActionButtons\s*=\s*true"""
        // ...and neither is ever disabled, nor is the in-content switch used as the answer.
        screen shouldNotContainSource """canAccept\s*=\s*(enabled|false)"""
        screen shouldContainSource """showEnableSwitch\s*=\s*false"""
        // Both answers are stored: one tap writes `true`, the other an explicit `false`.
        screen shouldContainSource """accepted\s*=\s*true"""
        screen shouldContainSource """accepted\s*=\s*false"""
        occurrences(screen, Regex("""navigator\s*\.\s*pop\(\s*\)""")) shouldBe 2
    }

    private companion object {
        const val SETUP_CONTENT =
            "src/main/java/eu/kanade/presentation/more/onboarding/DictionaryOcrSetupContent.kt"
        const val SETUP_SCREEN =
            "src/main/java/eu/kanade/tachiyomi/ui/more/DictionaryOcrSetupScreen.kt"
        const val INFO_SCREEN =
            "../presentation-core/src/main/java/tachiyomi/presentation/core/screens/InfoScreen.kt"
    }
}

/** Whitespace-tolerant source assertion, matching `DictionaryOcrSetupPolicyTest`'s local helper. */
private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source NOT to contain /$pattern/")
    }
}

/** The mirror of [shouldNotContainSource]: [pattern] must occur in the source. */
private infix fun String.shouldContainSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        fail("expected the source to contain /$pattern/")
    }
}

/** How many times [pattern] occurs in this text. */
private fun occurrences(source: String, pattern: Regex): Int = pattern.findAll(source).count()

/**
 * Drops comments before asserting: the composable's KUN header *documents* the rule by naming
 * `verticalScroll`, and a guard that matched its own documentation would be unfixable.
 */
private fun String.withoutComments(): String = replace(Regex("""/\*[\s\S]*?\*/"""), "")
    .replace(Regex("""//[^\n]*"""), "")
