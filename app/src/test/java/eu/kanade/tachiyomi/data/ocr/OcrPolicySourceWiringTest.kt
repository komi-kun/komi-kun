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

    private fun presentation(path: String): String = File("src/main/java/eu/kanade/presentation/$path").readText()

    /**
     * The balanced `Preference.PreferenceItem.ListPreference(…)` call that binds [marker], e.g.
     * `preference = preferences.detModelVariant,`. Scoping to one call keeps the guard from being
     * satisfied by a sibling preference's identical `onValueChanged` body.
     */
    private fun listPreference(source: String, marker: String): String {
        val at = source.indexOf(marker)
        check(at >= 0) { "missing preference binding: $marker" }
        val open = source.lastIndexOf("ListPreference(", at)
        check(open >= 0) { "no ListPreference call for: $marker" }

        var depth = 0
        for (i in open + "ListPreference".length until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("unterminated ListPreference call for: $marker")
    }

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

    /**
     * The mirror of the promotion: a chapter whose download **failed** must not stay
     * `WAITING_DOWNLOAD`, because nothing promotes a task whose download never succeeds and the
     * launch probe skips waiting-download work (`hasRunnableStatuses`). Every chapter-level exit that
     * marks the download `ERROR` must therefore also record the OCR failure — so the count of calls
     * is compared against the count of error exits inside `downloadChapter` rather than hard-coded.
     */
    @Test
    fun `the download failure exits record the OCR task as failed`() {
        val downloader = app("data/download/Downloader.kt")
        val chapterDownload = functionBody(downloader, "private suspend fun downloadChapter(")

        val errorExits = Regex("""download\.status\s*=\s*Download\.State\.ERROR""")
            .findAll(chapterDownload)
            .count()
        val ocrFailures = Regex("""ocrManager\.markChapterDownloadFailed\(\s*download\.chapter\.id\s*\)""")
            .findAll(chapterDownload)
            .count()
        check(errorExits > 0) { "no download error exit found in downloadChapter" }
        ocrFailures shouldBe errorExits

        // Metadata only, exactly like the promotion: gating it on the switch would strand the task
        // for the whole time the feature is off.
        chapterDownload shouldNotContainSource
            """if\s*\(\s*ocrEnabled\s*\)[^{]*\{\s*ocrManager\.markChapterDownloadFailed"""
        // The cancel/stop path is deliberately excluded: `stopLocked` turns *paused* downloads into
        // `ERROR` too, and a user pausing the downloader must not fail their queued OCR tasks.
        functionBody(downloader, "private fun stopLocked(") shouldNotContain "markChapterDownloadFailed"

        // Not asserted: that the task really becomes `ERROR` — `OcrManagerOptInTest` covers the
        // manager, and only a download run shows the ordering against `download.status`.
    }

    @Test
    fun `the worker defers the queue until the selected models are installed`() {
        val worker = app("data/ocr/OcrWorker.kt")
        val doWork = functionBody(worker, "override suspend fun doWork()")

        // A superseded install must not OCR the batch with the previous detector...
        doWork shouldContainSource """!\s*applicationContext\.appGraph\.modelDownloader\.isPaddleDownloaded"""
        // ...and the check must precede everything the run does: the queue run and the foreground
        // notification (which is what makes a pointless run visible to the user).
        val gate = doWork.indexOf("isPaddleDownloaded")
        val foreground = doWork.indexOf("setForegroundSafely()")
        val run = doWork.indexOf("ocrJob.start")
        check(gate >= 0 && foreground > gate && run > gate) { "model gate is missing or too late in doWork" }

        // Not asserted: that `isPaddleDownloaded` reports the *installed* release at runtime —
        // `ModelDownloaderOptInTest` covers the marker, and this JVM test has no Android context.
    }

    /**
     * The defect this pins: WorkManager stopping the worker (constraints gone, unique work replaced)
     * cancelled `doWork` and dropped the foreground notification, while the OCR run — which lives on
     * `@AppCoroutineScope`, not on the worker — kept going for minutes with no foreground service,
     * the state Android kills the process for. `OcrJob.stop()` had no caller at all.
     */
    @Test
    fun `a stopped worker stops the OCR job it left running`() {
        val worker = app("data/ocr/OcrWorker.kt")
        val doWork = functionBody(worker, "override suspend fun doWork()")

        // Gated on `isStopped`, so only a WorkManager stop aborts the run.
        doWork shouldContainSource """if\s*\(\s*isStopped\s*\)\s*ocrJob\s*\.\s*stop\(\s*\)"""
        // In the `finally`, because the cancellation path rethrows: a catch branch would not be the
        // way out of the worker.
        val finallyAt = doWork.indexOf("finally {")
        val stopAt = doWork.indexOf("ocrJob.stop()")
        check(finallyAt >= 0 && stopAt > finallyAt) { "the OCR job stop is missing from doWork's finally" }

        // Not asserted: that a real WorkManager cancellation reaches this `finally` at runtime (that
        // needs the work-runtime test harness), nor that `stop()` really interrupts an in-flight
        // engine call — `OcrManagerQueueRunTest` covers the run, `OcrJob` its cancellation.
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
        // The installed-model readiness is the third input of that decision, read the same way (the
        // marker read and the `stat`s are I/O too) and never a download: a superseded install must
        // not OCR its first batch with the previous detector.
        resume shouldContainSource
            """withContext\(\s*Dispatchers\.IO\s*\)\s*\{\s*context\.appGraph\.modelDownloader\.isPaddleDownloaded\s*\}"""
        resume shouldContainSource """shouldResumeQueue\(\s*enabled\s*,\s*runnableWork\s*,\s*modelsReady\s*\)"""
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
        // The launch resume declines to run on superseded models, so a completed migration must drain
        // the queue itself — otherwise the waiting batch only moves on the next launch.
        migrate shouldContainSource """ModelDownloadState\.Completed\s*\)\s*OcrWorker\.start\(\s*context\s*\)"""
        // And it really is on the launch path, next to the queue resume.
        mainActivity shouldContainSource """ResumeOcrQueue\(\)\s*MigrateOcrModels\(\)"""

        // Not asserted: that the download actually runs at launch (the graph singleton, the
        // dispatcher at runtime and the toast are Android/Compose-bound), nor that a completed
        // download leaves the marker matching the selection — `ModelDownloaderOptInTest` covers that.
    }

    /**
     * The settings screen is the other place a model download starts: switching the detector or the
     * recogniser resolves to another release, and nothing else re-downloads the weights the user just
     * selected (the engine only ever reloads what is *installed*). Deleting either `onValueChanged`
     * callback leaves the selection pointing at a release the downloader is never asked to install,
     * and no behavioural test can see it — the callback needs a Compose `Preference` host.
     */
    @Test
    fun `the settings variant switches trigger the download`() {
        val settings = presentation("more/settings/screen/SettingsOcrScreen.kt")

        // Scoped to each ListPreference call, so removing one callback is caught even though all
        // three of them have the same body.
        listPreference(settings, "preference = preferences.detModelVariant,") shouldContainSource
            """modelDownloader\s*\.\s*triggerPaddleDownload\(\s*\)"""
        listPreference(settings, "preference = preferences.recModelVariant,") shouldContainSource
            """modelDownloader\s*\.\s*triggerPaddleDownload\(\s*\)"""

        // Not asserted: that the switch really resolves to a release the downloader then installs —
        // `ModelDownloaderOptInTest` covers the download side, and the preference wiring is Compose's.
    }

    /**
     * A **finished model download is the trigger the queue was waiting for**. `OcrWorker.doWork`
     * returns success while the selected release is not installed and leaves its tasks `PENDING`, and
     * `MainActivity`'s resume is keyed on the master switch — so a first-time download that installed
     * nothing else left every queued chapter waiting until an app restart or an unrelated download,
     * and the feature looked dead exactly when the user had just set it up. Deleting this effect
     * reintroduces that, and no behavioural test can see it: the trigger lives in a Compose screen.
     */
    @Test
    fun `a completed model download starts the queue run the deferral left waiting`() {
        val settings = presentation("more/settings/screen/SettingsOcrScreen.kt")

        // Only the `Completed` transition acts: progress, failure and idle must never raise a
        // foreground run.
        settings shouldContainSource
            """if\s*\(\s*downloadState\s*!=\s*ModelDownloadState\.Completed\s*\)\s*return@LaunchedEffect"""
        // The run is gated on the same three inputs as the launch/rotation resume: the switch, real
        // runnable work (so no notification for an empty queue), and the installed release.
        settings shouldContainSource """OcrRuntimePolicy\.shouldResumeQueue\("""
        settings shouldContainSource """queueWorkProbe\s*\.\s*hasRunnableWork\(\s*\)"""
        settings shouldContainSource """modelDownloader\s*\.\s*isPaddleDownloaded"""
        // The start happens after the state gate, i.e. only on the completed transition.
        val completedGate = settings.indexOf("downloadState != ModelDownloadState.Completed")
        val start = settings.indexOf("OcrWorker.start(")
        check(completedGate >= 0 && start > completedGate) {
            "the completed-download queue start is missing or precedes the Completed check"
        }

        // Not asserted: that `ModelDownloader.state` really reaches `Completed` on a successful
        // install, or that the start drains the queue — the policy is pinned by `OcrRuntimePolicyTest`
        // and the run by `OcrManagerQueueRunTest`.
    }

    /**
     * The defect this pins: `OcrManager.deleteOcrForChapter`/`deleteOcrForManga` had **no call
     * sites**, so the per-chapter OCR results (~1–2 MB each under `filesDir/ocr_cache`) outlived the
     * chapters and manga they were recognised from; only the manual "Clear cache" action ever
     * reclaimed any. `DownloadManager` is where every deletion converges (chapter delete and pending
     * delete-after-read, library removal, "delete downloads", migration), so the pruning belongs
     * there. Deleting a chapter drops its entry; deleting a manga its whole directory.
     */
    @Test
    fun `deleting a chapter or a manga prunes its OCR cache`() {
        val downloads = app("data/download/DownloadManager.kt")

        val deleteChapters = functionBody(downloads, "suspend fun deleteChapters(")
        deleteChapters shouldContainSource
            """ocrManager\s*\.\s*deleteOcrForChapter\(\s*ocrManager\s*\.\s*cacheKey\("""
        // Keyed exactly as `OcrChapterResolver` keys the entry it wrote: source, manga, chapter.
        deleteChapters shouldContainSource
            """cacheKey\(\s*manga\s*\.\s*source\s*,\s*chapter\s*\.\s*mangaId\s*,\s*chapter\s*\.\s*id\s*\)"""

        val deleteManga = functionBody(downloads, "suspend fun deleteManga(")
        deleteManga shouldContainSource
            """ocrManager\s*\.\s*deleteOcrForManga\(\s*manga\s*\.\s*source\s*,\s*manga\s*\.\s*id\s*\)"""

        // Not asserted: that the cache files really disappear (`OcrCacheManagerTest` covers the
        // manager), nor that a chapter row deleted *without* its files is pruned — that path does
        // not touch `DownloadManager`, so it keeps its cache.
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
