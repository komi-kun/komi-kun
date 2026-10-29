// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level call-site guards for the feature-off path — not behavioural tests.**
//
// Both settings screens are `@Composable` and there is no Compose test harness in this module (see
// `OcrPolicySourceWiringTest`), so what no JVM test here can prove is that composing them with the
// switch off does no work. What a source guard *can* pin is the shape that keeps the off path cheap:
//  * the dictionary repository — whose construction builds the GPL engine and `dlopen`s
//    `libhoshidicts_jni` — must not be resolved by a plain `remember` (composition), only behind a
//    `Lazy`;
//  * the external-storage listing and the installed-model probe must sit behind `featureEnabled`.
// Residual gap, stated once: a green run does not prove the lazy really stays unresolved at runtime
// (that needs a Compose test), only that the off path cannot eagerly resolve it in source.
// KUN <--
package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class SettingsScreensOffPathWiringTest {

    private fun presentation(path: String): String = File("src/main/java/eu/kanade/presentation/$path").readText()

    /**
     * The defect this pins: `getPreferences` used to run `val repository = remember(context) {
     * context.appGraph.dictionaryRepository }` before any `visible = featureEnabled` check, so merely
     * opening Settings > Dictionary — or typing in Settings search, which composes every enabled
     * screen's preferences — constructed `HoshiDictionaryEngine` and loaded its native library on the
     * main thread for a user who never enabled the feature.
     */
    @Test
    fun `the dictionary settings never resolve the engine-bearing repository in composition`() {
        val settings = presentation("more/settings/screen/SettingsDictionaryScreen.kt")
        val getPreferences = functionBody(settings, "override fun getPreferences()")

        // No eager binding: a plain `remember { … dictionaryRepository }` is composition work.
        getPreferences shouldNotContainSource """val\s+repository\s*=\s*remember\("""
        // Deferred instead, so only an actual import (a gated row, tapped) resolves it.
        getPreferences shouldContainSource """lazy\s*\{\s*context\.appGraph\.dictionaryRepository\s*\}"""
        // The external-storage listing is on-path only.
        getPreferences shouldContainSource """if\s*\(\s*featureEnabled\s*\)\s*allDictionaryNames\(\s*context\s*\)"""
        getPreferences shouldContainSource """else\s+emptyList\(\)"""
        // …and switching it on must still show the rest of the screen without leaving it: the groups
        // follow the collected state, not a value read once at composition.
        settings shouldContainSource """val\s+featureEnabled\s+by\s+dictionaryOcrPreferences\.enabled\.collectAsState\(\)"""
        // The master switch stays ungated, or the feature could never be turned back on.
        getPreferences shouldContainSource """return\s+masterPreferences\(\)\s*\+"""
    }

    /**
     * Same defect on the OCR screen: `isPaddleDownloaded` stats the installed model files, and the
     * status row it feeds is gated, so probing it while off is pure off-path I/O.
     */
    @Test
    fun `the ocr settings probe the installed models only while the switch is on`() {
        val settings = presentation("more/settings/screen/SettingsOcrScreen.kt")

        // Re-evaluated on a switch flip (so an on-screen enable is reflected), and gated.
        settings shouldContainSource """remember\(\s*downloadState\s*,\s*featureEnabled\s*\)\s*\{"""
        val ready = rememberBlock(settings, "val modelsReady =")
        ready shouldContainSource """featureEnabled\s*&&"""
        ready shouldContainSource """modelDownloader\.isPaddleDownloaded"""
        // `featureEnabled` first: with `&&` that is what makes the stat unreachable while off.
        (ready.indexOf("featureEnabled") < ready.indexOf("modelDownloader.isPaddleDownloaded")) shouldBe true
        // The row it feeds is hidden while off, so a `false` gate is never shown to the user.
        settings shouldContainSource
            """title\s*=\s*stringResource\(KUNMR\.strings\.pref_ocr_model_status\)[\s\S]*?visible\s*=\s*featureEnabled"""
    }

    /** The `remember { … }` expression assigned at [marker], found by brace matching. */
    private fun rememberBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "missing assignment: $marker" }
        val open = source.indexOf("remember(", start)
        check(open > start) { "no remember call for: $marker" }
        val body = source.indexOf('{', open)

        var depth = 0
        for (i in body until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(body, i + 1)
                }
            }
        }
        error("unterminated remember block for: $marker")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing call site: $signature" }
        val open = source.indexOf('{', start)
        check(open > start) { "no block for: $signature" }

        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("unterminated body for: $signature")
    }
}

private infix fun String.shouldContainSource(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        fail("expected the source to contain /$pattern/")
    }
}

private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source not to contain /$pattern/")
    }
}
