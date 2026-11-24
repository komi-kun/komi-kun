// SPDX-License-Identifier: Apache-2.0
// KUN -->
// **Source-level wiring guard for the "remove filtered downloads" switch — not a behavioural test.**
//
// The callback lives inside a Compose `Preference.PreferenceItem.SwitchPreference`, and this module has
// no Compose harness (see `OcrPolicySourceWiringTest` and `SettingsScreensOffPathWiringTest`), so no JVM
// test can observe the order in which it runs relative to the framework's own preference write. That
// order is the whole bug: `PreferenceItem` calls `onValueChanged(newValue)` and only then
// `preference.set(newValue)`, so work run synchronously in the callback acts while the stored value is
// still the old one — turning the feature off cancels the sweep and clears the queue while the
// preference still reads `true`, and turning it on starts the discovery pass while it still reads
// `false` (so that pass returns at its first guard). The fix is shape: post the work.
// Residual gap, stated once: a green run does not prove the executor really runs the block after the
// write at runtime (that needs a Compose test), only that the calls cannot run before it in source.
// KUN <--
package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class FilteredDownloadCleanupPreferenceWiringTest {

    private val source: String by lazy {
        File("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDownloadScreen.kt")
            .readText()
            .withoutComments()
    }

    /**
     * The defect this pins: `onValueChanged` used to call `FilteredDownloadCleanupWorker.setupTask` and
     * `enqueueImmediate` directly, i.e. before the framework stored the new value.
     */
    @Test
    fun `the remove-filtered-downloads switch schedules the sweep after the preference write`() {
        val switch = switchPreference(source, "preference = downloadPreferences.removeFilteredDownloads,")

        // Both calls sit *inside* the posted block, and nothing of the worker runs before it: a call
        // ahead of `getMainExecutor` is the synchronous shape the callback must never have again.
        val posted = executorBlock(switch)
        switch.substringBefore("getMainExecutor") shouldNotContain "FilteredDownloadCleanupWorker."
        posted shouldContainSource """FilteredDownloadCleanupWorker\s*\.\s*setupTask\("""
        posted shouldContainSource """FilteredDownloadCleanupWorker\s*\.\s*enqueueImmediate\("""

        // Scheduled with the value the user just picked (the callback parameter), not with a literal
        // and not with a fresh read of the preference the framework has not written yet.
        val enabled = lambdaParameter(switch)
        posted shouldContainSource
            """setupTask\(\s*context\s*,\s*${Regex.escape(enabled)}\s*\)"""
        posted shouldNotContainSource """setupTask\(\s*context\s*,\s*(?:true|false)\s*\)"""
        posted shouldNotContainSource """removeFilteredDownloads\s*\.\s*get\("""

        // The immediate pass is the off → on retrofit only: turning the switch off must not start one.
        posted shouldContainSource
            """if\s*\(\s*${Regex.escape(enabled)}\s*\)\s*FilteredDownloadCleanupWorker\s*\.\s*enqueueImmediate\("""

        // And the callback still answers "write it": `false` would drop the preference write entirely.
        lambdaBody(switch).trimEnd().removeSuffix("}").trim().endsWith("true") shouldBe true
    }

    /**
     * The balanced `Preference.PreferenceItem.SwitchPreference(…)` call binding [marker], so the guard
     * cannot be satisfied by a sibling switch's identical callback.
     */
    private fun switchPreference(source: String, marker: String): String {
        val at = source.indexOf(marker)
        check(at >= 0) { "missing preference binding: $marker" }
        val open = source.lastIndexOf("SwitchPreference(", at)
        check(open >= 0) { "no SwitchPreference call for: $marker" }
        return balanced(source, open + "SwitchPreference".length)
    }

    /** The balanced `{ … }` block handed to `execute`, found from the `getMainExecutor` call. */
    private fun executorBlock(switch: String): String {
        val at = switch.indexOf("getMainExecutor")
        check(at >= 0) { "the switch does not post to an executor" }
        val open = switch.indexOf('{', at)
        check(open > at) { "no execute block after getMainExecutor" }
        return balanced(switch, open)
    }

    /** The name of the `onValueChanged` lambda's parameter, from its `{ name ->` header. */
    private fun lambdaParameter(switch: String): String {
        val match = Regex("""onValueChanged\s*=\s*\{\s*(\w+)\s*->""").find(switch)
        checkNotNull(match) { "onValueChanged has no named parameter" }
        return match.groupValues[1]
    }

    /** The `{ … }` body of `onValueChanged`, brace-matched from its header. */
    private fun lambdaBody(switch: String): String {
        val at = switch.indexOf("onValueChanged")
        check(at >= 0) { "missing onValueChanged" }
        val open = switch.indexOf('{', at)
        check(open > at) { "no lambda body for onValueChanged" }
        return balanced(switch, open)
    }

    /**
     * The bracket/brace group starting at [from] (which must be the opening character), matched by
     * counting, so the guard survives reformatting. A copy change that introduced a brace inside a
     * string literal in these callbacks would fail loudly rather than silently pass.
     */
    private fun balanced(source: String, from: Int): String {
        val open = source[from]
        val close = if (open == '(') ')' else '}'
        var depth = 0
        for (i in from until source.length) {
            when (source[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return source.substring(from, i + 1)
                }
            }
        }
        error("unterminated $open block")
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

/** The mirror: [pattern] must not occur in this text (same whitespace-tolerant regex form). */
private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source not to contain /$pattern/")
    }
}

/** Line and block comments removed, so no comment can stand in for the code a guard looks for. */
private fun String.withoutComments(): String = replace(Regex("""/\*[\s\S]*?\*/"""), "")
    .lines()
    .joinToString("\n") { line -> line.substringBefore("//") }
