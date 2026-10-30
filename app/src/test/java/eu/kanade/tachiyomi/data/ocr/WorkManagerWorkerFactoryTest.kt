// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// **The factory that builds the app's workers must actually be installed.**
//
// WorkManager instantiates a worker through the `WorkerFactory` on its `Configuration`; when that
// factory returns `null` it falls back to reflection
// (`WorkerFactory.createWorkerWithDefaultFallback`), which looks for a
// `(Context, WorkerParameters)` constructor and rethrows `NoSuchMethodException`
// ("Could not instantiate …") otherwise. `OcrWorker` has six parameters, so it is *only*
// constructible through the app's own factory — and the app's factory is only installed if
// `App` supplies the configuration *and* nothing else initialised WorkManager first.
//
// The defect this pins: `App.onCreate` used `if (!WorkManager.isInitialized()) WorkManager.initialize(…)`,
// but `androidx.work.WorkManagerInitializer` is an `androidx.startup` initialiser that runs from a
// `ContentProvider`, i.e. **before** `Application.onCreate` — so the branch was always false, the
// custom factory was never installed, and every download-triggered OCR run failed to construct its
// worker. Nothing in the app logs that as an OCR problem; it surfaces only as WorkManager's
// "Could not instantiate" error and a permanently `PENDING` queue.
// KUN <--
package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.App
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

class WorkManagerWorkerFactoryTest {

    private val reflectionConstructor = listOf(Context::class.java, WorkerParameters::class.java)

    /**
     * Every `ListenableWorker` subclass in the app, discovered from the sources so a worker added
     * later cannot silently escape the list below.
     */
    @Test
    fun `every worker in the app is constructible by the factory that is installed`() {
        val workers = workerSources()
        workers.map { it.className } shouldBe listOf(
            "AppUpdateDownloadJob",
            "AppUpdateJob",
            "BackupCreateWorker",
            "BackupRestoreWorker",
            "DelayedTrackingUpdateWorker",
            "DownloadWorker",
            "EHentaiUpdateWorker",
            "LibraryUpdateWorker",
            "MetadataUpdateWorker",
            "OcrWorker",
            "SyncDataJob",
        )

        workers.forEach { source ->
            val worker = Class.forName("${source.packageName}.${source.className}")
            val constructors = worker.declaredConstructors.map { it.parameterCount to it.parameterTypes.toList() }
            if (source.className == OCR_WORKER) {
                // Six parameters, none of them the reflection pair: WorkManager's fallback throws
                // `NoSuchMethodException` and the work is marked failed.
                constructors.none { it.second == reflectionConstructor } shouldBe true
                constructors.map { it.first } shouldBe listOf(6)
            } else {
                // These are built by the delegating factory's `else -> null` → reflection fallback.
                constructors.any { it.second == reflectionConstructor } shouldBe true
            }
        }
    }

    /**
     * The configuration and its factory: `App` must be the `Configuration.Provider` WorkManager asks
     * (the startup initialiser is removed), and its factory must be the one that knows `OcrWorker`.
     */
    @Test
    fun `the application provides the work manager configuration that builds the ocr worker`() {
        // Would be `false` for the old `WorkManager.initialize(…)`-only wiring.
        Configuration.Provider::class.java.isAssignableFrom(App::class.java) shouldBe true

        val app = app("App.kt")
        app sourceContains """override\s+val\s+workManagerConfiguration\s*:\s*Configuration"""
        app sourceContains """setWorkerFactory\(\s*KomikunWorkerFactory\("""
        app sourceContains """$OCR_WORKER::class\.java\.name\s*->\s*OcrWorker\("""
        // The initialise-if-not-initialised shape cannot install it (the startup initialiser, a
        // `ContentProvider`, has already run by `onCreate`), so it must not come back.
        app sourceNotContainsL "WorkManager.isInitialized()"
        app sourceNotContains """WorkManager\.initialize\("""
    }

    /**
     * The other half of the fix: with the default initialiser still declared, WorkManager would
     * initialise itself with a default configuration before `App.onCreate` — and a green
     * `Configuration.Provider` would never be consulted.
     */
    @Test
    fun `the default work manager initialiser is merged out of the manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        manifest sourceContains
            """<meta-data\s+android:name="androidx\.work\.WorkManagerInitializer"\s+tools:node="remove""""
        // …inside the startup provider, or the removal applies to nothing.
        manifest sourceContains """<provider\s+android:name="androidx\.startup\.InitializationProvider""""
        manifest sourceContains """android:authorities="\$\{applicationId\}\.androidx-startup""""
        manifest sourceContains """tools:node="merge">\s*<meta-data\s+android:name="androidx\.work\.WorkManagerInitializer""""
    }

    private data class WorkerSource(val packageName: String, val className: String)

    private fun workerSources(): List<WorkerSource> = File("src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            val text = file.readText()
            val packageName = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
                .find(text)
                ?.groupValues
                ?.get(1)
                ?: return@flatMap emptySequence()
            Regex("""class\s+(\w+)\s*\([\s\S]*?\)\s*:\s*(?:${CoroutineWorker::class.java.simpleName}|${Worker::class.java.simpleName}|${ListenableWorker::class.java.simpleName})\(""")
                .findAll(text)
                .map { WorkerSource(packageName, it.groupValues[1]) }
        }
        .sortedBy { it.className }
        .toList()

    private fun app(path: String): String = File("src/main/java/eu/kanade/tachiyomi/$path").readText()

    private companion object {
        const val OCR_WORKER = "OcrWorker"
    }
}

private infix fun String.sourceContains(pattern: String) {
    if (!Regex(pattern).containsMatchIn(this)) {
        fail("expected the source to contain /$pattern/")
    }
}

private infix fun String.sourceNotContainsL(literal: String) {
    if (contains(literal)) {
        fail("expected the source not to contain \"$literal\"")
    }
}

private infix fun String.sourceNotContains(pattern: String) {
    if (Regex(pattern).containsMatchIn(this)) {
        fail("expected the source not to contain /$pattern/")
    }
}
