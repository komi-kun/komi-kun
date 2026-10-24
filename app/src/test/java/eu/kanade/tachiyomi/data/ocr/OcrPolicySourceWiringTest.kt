// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **Source-level call-site guards — not behavioural tests.**
//
// The rules themselves are pure functions and are tested behaviourally elsewhere:
// `OcrRuntimePolicyTest` (the download truth table and the run/resume gates), `DictionarySessionOwnerTest`
// (the on → off close rule), `OcrQueueStatusTest` (what a run actually picks up). What no JVM test in
// this module can prove is that the Android classes still *consult* them: `Downloader`, `OcrWorker`,
// `MainActivity` and `App` are `Context`/worker/lifecycle/Compose-bound, and there is no Robolectric
// or Compose harness here (building one for four call sites is not worth it).
//
// So these tests read the call-site sources — the same way `AudioMenuViewportHeightGuardTest` pins the
// dictionary assets. Two properties are deliberate, because a grep guard easily becomes noise:
//  * the needles are regexes that tolerate any whitespace between tokens, never an exact multi-line
//    string, so re-indenting or wrapping an argument (Spotless's business) cannot fail the test;
//  * they assert only *that* the decision function is called with the live values. **Residual gap:
//    the arguments, the runtime dispatcher, and the lifecycle ordering they imply are unverified
//    here** — the note in each test names what a green run still does not prove.
// KUN <--
package eu.kanade.tachiyomi.data.ocr

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class OcrPolicySourceWiringTest {

    private fun app(path: String): String = File("src/main/java/eu/kanade/tachiyomi/$path").readText()

    @Test
    fun `the download hook keeps the queue metadata and gates the actions`() {
        val downloader = app("data/download/Downloader.kt")

        // Metadata first, unconditionally — gating it would strand a task at WAITING_DOWNLOAD.
        downloader shouldContainSource """OcrRuntimePolicy\.refreshesQueueMetadata\(\s*ocrEnabled\s*\)"""
        // Then the action truth table (not an inline `if (enabled)`) decides queue/worker-start.
        downloader shouldContainSource """OcrRuntimePolicy\.downloadActions\("""

        // Not asserted: which arguments the hook passes (`promoted`/`autoOnDownload`) and whether the
        // promotion really runs before the actions. Only the download path at runtime shows that.
    }

    @Test
    fun `the worker refuses to run the queue while the feature is off`() {
        val worker = app("data/ocr/OcrWorker.kt")

        // Off => success without touching the queue (a retry would spin forever).
        worker shouldContainSource
            """OcrRuntimePolicy\.shouldRunQueue\(\s*dictionaryOcrPreferences\.isEnabled\(\)\s*\)"""
        // An empty queue never raises the foreground notification.
        worker shouldContainSource """queueWorkProbe\.hasRunnableWork\(\)"""

        // Not asserted: that `doWork` returns before the queue is touched (the ordering of the two
        // guards), only that both gates are consulted.
    }

    @Test
    fun `the launch resume probes the queue off the main thread`() {
        val mainActivity = app("ui/main/MainActivity.kt")

        // The probe deserialises the persisted queue, so it must run on `Dispatchers.IO` rather than
        // during composition.
        mainActivity shouldContainSource
            """withContext\(\s*Dispatchers\.IO\s*\)\s*\{\s*ocrQueueWorkProbe\.hasRunnableWork\(\)\s*\}"""
        mainActivity shouldContainSource """OcrRuntimePolicy\.shouldResumeQueue\("""
        // Exactly one probe read in the whole activity: a second, composition-time read (the shape
        // this replaced) would reintroduce the first-frame stall.
        Regex("""ocrQueueWorkProbe\.hasRunnableWork\(\)""").findAll(mainActivity).count() shouldBe 1

        // Not asserted: that the probe really executes off the main thread at runtime, or that it
        // runs once per switch flip (the `LaunchedEffect(enabled)` key is not inspected).
    }

    @Test
    fun `the application releases the dictionary session on opt-out, not on every background`() {
        val application = app("App.kt")

        // One owner observes the opt-in and closes on the on -> off transition...
        application shouldContainSource """DictionarySessionOwner\("""
        application shouldContainSource
            """closeSession\s*=\s*\{\s*graph\.dictionaryRepository\.close\(\)\s*\}"""
        // ...and the only other release is real memory pressure.
        functionBody(application, "override fun onTrimMemory(level: Int)") shouldContain
            "graph.dictionaryRepository.close()"
        // `ProcessLifecycle.onStop` must not re-parse the dictionaries on every foreground cycle.
        functionBody(application, "override fun onStop(owner: LifecycleOwner)") shouldNotContain
            "dictionaryRepository.close()"

        // Not asserted: that the owner is started with the preference's own flow, that the collector's
        // dispatcher leaves the main thread free, or which trim levels the OS actually delivers.
    }

    /**
     * The body of the function starting at [signature], found by counting braces from its opening
     * brace, so the guard survives reformatting. (A `{`/`}` inside a string literal in those bodies
     * would confuse it; both are plain calls, and a copy change would fail loudly rather than
     * silently pass.)
     */
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
