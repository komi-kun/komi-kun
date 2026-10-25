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

        // Metadata first and unconditional: `markChapterReadyForOcr` is assigned straight to
        // `promotedForOcr`, with no policy gate (`refreshesQueueMetadata`) and no `if` around it —
        // gating the promotion would strand a task at WAITING_DOWNLOAD.
        downloader shouldContainSource
            """val\s+promotedForOcr\s*=\s*ocrManager\.markChapterReadyForOcr\(\s*download\.chapter\.id\s*\)"""
        downloader shouldNotContain "refreshesQueueMetadata"
        downloader shouldNotContainSource """if\s*\(\s*ocrEnabled\s*\)\s*\{\s*ocrManager\.markChapterReadyForOcr"""
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
    fun `the launch resume probes the queue off the main thread only while the switch is on`() {
        val mainActivity = app("ui/main/MainActivity.kt")
        val resume = functionBody(mainActivity, "private fun ResumeOcrQueue()")

        // The probe deserialises the persisted queue, so it must run on `Dispatchers.IO` rather than
        // during composition.
        resume shouldContainSource
            """withContext\(\s*Dispatchers\.IO\s*\)\s*\{\s*ocrQueueWorkProbe\.hasRunnableWork\(\)\s*\}"""
        resume shouldContainSource """OcrRuntimePolicy\.shouldResumeQueue\("""
        // Gated: off means the persisted queue is not even read, so the probe must sit *after* the
        // switch check (the resume runs on every switch flip, including the off one).
        resume shouldContainSource """OcrRuntimePolicy\.shouldRunQueue\(\s*enabled\s*\)\s*\)\s*return@LaunchedEffect"""
        val gate = resume.indexOf("OcrRuntimePolicy.shouldRunQueue(")
        val probe = resume.indexOf("ocrQueueWorkProbe.hasRunnableWork()")
        check(gate >= 0 && probe >= 0) { "missing gate or probe in ResumeOcrQueue" }
        (gate < probe) shouldBe true
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

    @Test
    fun `the reader detaches the OCR host on exit but not across a configuration change`() {
        val readerActivity = app("ui/reader/ReaderActivity.kt")
        val onDestroy = functionBody(readerActivity, "override fun onDestroy()")

        // The OCR/dictionary hosts are app-graph singletons: the popup anchor (and the page its
        // screenshot provider captured) must be released when the reader exits for good...
        onDestroy shouldContainSource """readerOcrHost\.detach\(\)"""
        // ...but not while the activity is recreated for a rotation, where the open popup survives.
        onDestroy shouldContainSource """!isChangingConfigurations"""

        // Not asserted: that the callback ordering (after `super.onDestroy()`) is safe, or that the
        // host's `detach()` really clears the anchor - `ReaderOcrPopupAnchorTest` covers that part.
    }

    @Test
    fun `the launch migration consults the policy off the main thread, once, and only while on`() {
        val mainActivity = app("ui/main/MainActivity.kt")
        val migrate = functionBody(mainActivity, "private fun MigrateOcrModels()")

        // The decision must be the pure policy, not an inline condition here (the policy is what
        // `OcrRuntimePolicyTest` pins), and it reads the *installed* release rather than the selection.
        migrate shouldContainSource """OcrRuntimePolicy\.shouldMigrateModels\("""
        migrate shouldContainSource """installedRelease\s*=\s*modelDownloader\.installedRelease"""
        // Both probes are file I/O (`stat`s and the marker read), so not on the main thread.
        migrate shouldContainSource
            """withContext\(\s*Dispatchers\.IO\s*\)\s*\{\s*OcrRuntimePolicy\.shouldMigrateModels\("""
        // Gated by the feature switch before anything is read, then acted on exactly once.
        migrate shouldContainSource """OcrRuntimePolicy\.shouldRunQueue\(\s*enabled\s*\)\s*\)\s*return@LaunchedEffect"""
        migrate shouldContainSource """modelMigrationChecked\.compareAndSet\(\s*false\s*,\s*true\s*\)"""
        migrate shouldContainSource """modelDownloader\.triggerPaddleDownload\(\)"""
        // And it really is on the launch path, next to the queue resume.
        mainActivity shouldContainSource """ResumeOcrQueue\(\)\s*MigrateOcrModels\(\)"""

        // Not asserted: that the download actually runs at launch (the graph singleton, the
        // dispatcher at runtime and the toast are Android/Compose-bound), nor that a completed
        // download leaves the marker matching the selection — `ModelDownloaderOptInTest` covers that.
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

/** The mirror: [pattern] must not occur in this text (same whitespace-tolerant regex form). */
private infix fun String.shouldNotContainSource(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source not to contain /$pattern/")
    }
}
// KUN <--
