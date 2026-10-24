// SPDX-License-Identifier: Apache-2.0
// KUN -->
// Call-site wiring for the OCR runtime gate and the dictionary session lifecycle.
//
// The policies themselves are pure and tested (`OcrRuntimePolicyTest`, `DictionarySessionOwnerTest`,
// `DictionaryOcrSetupPolicyTest`), but a pure function proves nothing if the Android class that must
// consult it stops doing so. The worker, the downloader and the launch resume are `Context`-bound and
// have no JVM harness here, so the wiring is pinned against their source — the same way
// `AudioMenuViewportHeightGuardTest` / `JniSignatureTest` pin their seams.
// KUN <--
package eu.kanade.tachiyomi.data.ocr

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

class OcrPolicyWiringTest {

    private fun app(path: String): String = File("src/main/java/eu/kanade/tachiyomi/$path").readText()

    @Test
    fun `the download hook keeps the metadata and gates the actions`() {
        val downloader = app("data/download/Downloader.kt")

        // Metadata first, unconditionally — gating it would strand a task at WAITING_DOWNLOAD.
        downloader shouldContain "OcrRuntimePolicy.refreshesQueueMetadata(ocrEnabled)"
        // Then the action truth table decides queue/worker-start.
        downloader shouldContain "OcrRuntimePolicy.downloadActions("
        downloader shouldContain "OcrRuntimePolicy.downloadActions(\n                enabled = ocrEnabled,"
    }

    @Test
    fun `the worker refuses to run the queue while the feature is off`() {
        val worker = app("data/ocr/OcrWorker.kt")

        // Off => success without touching the queue (a retry would spin forever).
        worker shouldContain "OcrRuntimePolicy.shouldRunQueue(dictionaryOcrPreferences.isEnabled())"
        // And an empty queue never raises the foreground notification.
        worker shouldContain "queueWorkProbe.hasRunnableWork()"
    }

    @Test
    fun `the launch resume probes the queue off the main thread`() {
        val mainActivity = app("ui/main/MainActivity.kt")

        // The probe deserialises the persisted queue, so it must not run during composition.
        mainActivity shouldContain "withContext(Dispatchers.IO) { ocrQueueWorkProbe.hasRunnableWork() }"
        mainActivity shouldContain "OcrRuntimePolicy.shouldResumeQueue(enabled, runnableWork)"
        mainActivity shouldNotContain "remember(enabled) { ocrQueueWorkProbe.hasRunnableWork() }"
    }

    @Test
    fun `the application releases the dictionary session on opt-out, not on every background`() {
        val app = app("App.kt")

        // One owner observes the opt-in and closes on the on -> off transition...
        app shouldContain "DictionarySessionOwner("
        app shouldContain "closeSession = { graph.dictionaryRepository.close() }"
        // ...and the only other release is real memory pressure.
        val trim = functionBody(app, "override fun onTrimMemory(level: Int) {")
        trim shouldContain "graph.dictionaryRepository.close()"
        // `ProcessLifecycle.onStop` must not re-parse the dictionaries on every foreground cycle.
        functionBody(app, "override fun onStop(owner: LifecycleOwner) {") shouldNotContain "dictionaryRepository.close()"
    }

    /** The body of the function starting at [signature], up to the next unindented closing brace. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing call site: $signature" }
        val end = source.indexOf("\n    }\n", start)
        check(end > start) { "unterminated body for: $signature" }
        return source.substring(start, end)
    }
}
