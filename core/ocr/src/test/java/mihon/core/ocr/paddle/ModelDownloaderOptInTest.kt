// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Opt-in gate tests for the model downloader. While the dictionary/OCR feature is off it must do
// **no** network and no model-file work, and every `Result`-returning entry point must report an
// explicit failure (never a fake success).
//
// No real network is involved: every test installs an OkHttp interceptor that either fails with a
// canned HTTP 500 or serves a canned manifest + model blobs. The enabled cases are full positive
// controls (download, import, trigger) so an always-blocked implementation cannot pass, and they
// also prove the `CountDownLatch` the disabled cases rely on does fire on a real run.
//
// Readiness gate (review iteration 5): `isPaddleDownloaded` refuses weights cached from a *previous*
// release, so moving the pin (`ocr-manga-v2` -> `ocr-manga-v3`) actually downloads the new detector
// on an in-place upgrade.
//
// Model selection (detector × recogniser): every release publishes the *same* file names — the
// detector name is shared, only its bytes differ — so "the file exists" can never mean "the selected
// variant is installed". A completed install records its release in `installed-release.txt`, and the
// gate, the offline fast path and a variant switch all key on that marker.
// KUN <--
package mihon.core.ocr.paddle

import android.content.Context
import android.net.Uri
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.core.ocr.DetModelVariant
import mihon.core.ocr.ModelRelease
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.RecModelVariant
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloaderOptInTest {

    // ------------------------------------------------------------------ fetchManifest

    @Test
    fun `fetching the manifest fails without touching the network while disabled`(@TempDir dir: File): Unit =
        runBlocking {
            val requests = AtomicInteger()
            val downloader = downloader(dir, failingClient(requests), enabled = false)

            val result = downloader.fetchManifest()

            (result.exceptionOrNull() is IllegalStateException) shouldBe true
            requests.get() shouldBe 0
        }

    @Test
    fun `fetching the manifest still reaches the network while enabled`(@TempDir dir: File): Unit = runBlocking {
        val requests = AtomicInteger()
        val downloader = downloader(dir, failingClient(requests), enabled = true)

        val result = downloader.fetchManifest()

        requests.get() shouldBe 1
        result.exceptionOrNull()?.message shouldBe "Manifest download failed: HTTP 500"
    }

    // ------------------------------------------------------------------ downloadModels

    @Test
    fun `downloading refuses while disabled and leaves the model directory untouched`(@TempDir dir: File): Unit =
        runBlocking {
            val requests = AtomicInteger()
            val downloader = downloader(dir, failingClient(requests), enabled = false)
            // A leftover scratch file from an interrupted run: the disabled path must not even prune it.
            val scratch = File(downloader.modelsDir, "$DET_MODEL.part").apply {
                parentFile?.mkdirs()
                writeText("partial")
            }

            val result = downloader.downloadModels()

            (result.exceptionOrNull() is IllegalStateException) shouldBe true
            requests.get() shouldBe 0
            scratch.exists() shouldBe true
            downloader.modelsDir.listFiles().orEmpty().map { it.name } shouldBe listOf("$DET_MODEL.part")
        }

    @Test
    fun `downloading still installs the models while enabled`(@TempDir dir: File): Unit = runBlocking {
        val models = stagedModels()
        val requests = AtomicInteger()
        val urls = CopyOnWriteArrayList<String>()
        // The manifest names an explicit *flat* `path` (the shape the published releases actually
        // have), so the downloader must request `<baseUrl>/<name>` — not `<baseUrl>/models/<name>`.
        val downloader = downloader(
            dir,
            cannedRelease(requests, urls, CountDownLatch(1), manifestJson(models, dir, flat = true), flatFiles(models)),
            enabled = true,
        )

        val result = downloader.downloadModels()

        result.isSuccess shouldBe true
        downloader.modelsDir.listFiles().orEmpty().map { it.name }.shouldContainExactlyInAnyOrder(
            modelNames + "manifest.json" + INSTALLED_RELEASE_MARKER,
        )
        models.forEach { (name, bytes) -> File(downloader.modelsDir, name).readBytes() shouldBe bytes }
        // The run records the release it installed; that marker — not the file set, whose names are
        // shared by every release — is what the readiness gate and the fast path trust.
        installedReleaseMarker(downloader.modelsDir) shouldBe ModelRelease.DEFAULT.release
        // One manifest fetch + one download per model, and nothing re-fetched...
        requests.get() shouldBe modelNames.size + 1
        // ...at exactly the URLs the manifest's `path` entries resolve to.
        urls.toList() shouldBe listOf("$baseUrl/manifest.json") + modelNames.map { "$baseUrl/$it" }
        downloader.isPaddleDownloaded shouldBe true
    }

    /**
     * The fallback shape, pinned exactly: an entry **without** `path` resolves to
     * `<baseUrl>/models/<name>`. The fake only serves the flat release layout here, so this must fail
     * with the 404 for that URL — the assertion that keeps the fake path-sensitive (a name-only fake
     * would pass this *and* the real release's 404 shape).
     */
    @Test
    fun `a manifest entry without a path resolves to the models prefix`(@TempDir dir: File): Unit = runBlocking {
        val models = stagedModels()
        val requests = AtomicInteger()
        val urls = CopyOnWriteArrayList<String>()
        val downloader = downloader(
            dir,
            cannedRelease(
                requests,
                urls,
                CountDownLatch(1),
                manifestJson(models, dir, flat = false),
                flatFiles(models),
            ),
            enabled = true,
        )

        val result = downloader.downloadModels()

        result.exceptionOrNull()?.message shouldBe "Download failed for $DET_MODEL: HTTP 404"
        urls.toList() shouldBe listOf("$baseUrl/manifest.json", "$baseUrl/models/$DET_MODEL")
        // Nothing was installed from the manifest the fallback could not satisfy.
        File(downloader.modelsDir, DET_MODEL).exists() shouldBe false
    }

    // ------------------------------------------------------------------ importFromUri

    @Test
    fun `importing refuses while disabled without opening the archive`(@TempDir dir: File): Unit = runBlocking {
        val requests = AtomicInteger()
        val context = mockContext(dir)
        val downloader = downloader(context, failingClient(requests), enabled = false)

        val result = downloader.importFromUri(mockk<Uri>())

        (result.exceptionOrNull() is IllegalStateException) shouldBe true
        verify(exactly = 0) { context.contentResolver }
        requests.get() shouldBe 0
    }

    @Test
    fun `importing still installs from a picked archive while enabled`(@TempDir dir: File): Unit = runBlocking {
        val models = stagedModels()
        val requests = AtomicInteger()
        val uri = mockk<Uri>()
        val context = mockContext(dir)
        every { context.contentResolver.openInputStream(uri) } returns ByteArrayInputStream(zipOf(models))
        val downloader = downloader(
            context,
            cannedRelease(
                requests,
                CopyOnWriteArrayList(),
                CountDownLatch(1),
                manifestJson(models, dir, flat = true),
                flatFiles(models),
            ),
            enabled = true,
        )

        val result = downloader.importFromUri(uri)

        result.isSuccess shouldBe true
        models.forEach { (name, bytes) -> File(downloader.modelsDir, name).readBytes() shouldBe bytes }
        verify(exactly = 1) { context.contentResolver.openInputStream(uri) }
        downloader.isPaddleDownloaded shouldBe true
    }

    // ------------------------------------------------------------------ isPaddleDownloaded

    /**
     * The selection resolves to a release whose files are *not* the ones on disk. The detector name
     * and the recogniser/dictionary names are identical in every release (only the detector bytes
     * differ), so the file set itself looks perfect: only the installed-release marker can tell that
     * these are the `ocr-manga-v3` (SFX) weights an older build installed.
     */
    @Test
    fun `the readiness gate rejects a cache whose marker names another release`(@TempDir dir: File) {
        val models = stagedModels()
        val downloader = downloader(dir, failingClient(AtomicInteger()), enabled = true)
        val modelsDir = downloader.modelsDir.apply { mkdirs() }
        models.forEach { (name, bytes) -> File(modelsDir, name).writeBytes(bytes) }

        // Positive control: the same bytes, marked as the selection's release, do read as ready — so
        // the `false` below is the marker and not a missing file or an unreadable manifest.
        writeCachedManifest(modelsDir, models, dir, release = ModelRelease.DEFAULT.release)
        writeMarker(modelsDir, ModelRelease.DEFAULT.release)
        downloader.isPaddleDownloaded shouldBe true

        // A stale marker while the cached manifest *claims* the selection: e.g. a run that fetched
        // the new manifest and then failed before installing. The files (and their sizes) look right
        // for the selection; only the marker knows they were installed for another release.
        writeMarker(modelsDir, "ocr-manga-v3")
        downloader.isPaddleDownloaded shouldBe false

        // A manifest from another release is refused even when the marker agrees with the selection.
        writeCachedManifest(modelsDir, models, dir, release = "ocr-manga-v3")
        writeMarker(modelsDir, ModelRelease.DEFAULT.release)
        downloader.isPaddleDownloaded shouldBe false
    }

    /**
     * A pre-marker install cannot prove *which* release its files are, so it is not ready: that is
     * exactly the case that must re-download instead of silently keeping the SFX detector. The cheap
     * size cross-check still applies once a marker and a manifest are present.
     */
    @Test
    fun `the readiness gate needs the marker even with no manifest cached`(@TempDir dir: File) {
        val models = stagedModels()
        val downloader = downloader(dir, failingClient(AtomicInteger()), enabled = true)
        val modelsDir = downloader.modelsDir.apply { mkdirs() }
        models.forEach { (name, bytes) -> File(modelsDir, name).writeBytes(bytes) }
        File(modelsDir, MANIFEST_NAME).exists() shouldBe false

        downloader.isPaddleDownloaded shouldBe false

        // With the marker the existing file set is vouched for without a manifest...
        writeMarker(modelsDir, ModelRelease.DEFAULT.release)
        downloader.isPaddleDownloaded shouldBe true

        // ...but an empty file is not a model.
        File(modelsDir, DET_MODEL).writeBytes(ByteArray(0))
        downloader.isPaddleDownloaded shouldBe false
    }

    // ------------------------------------------------------------------ model selection cache

    /**
     * The defect this pins: every release ships the **same** detector file name, so a downloader that
     * treats "the file exists" (or "it matches its own manifest") as "the selected variant is
     * installed" leaves the old detector on disk forever. Switching the detector in Settings must
     * re-fetch that shared name from the other release and leave the untouched files alone.
     */
    @Test
    fun `switching to the sound-effect detector re-fetches the shared detector file`(@TempDir dir: File): Unit =
        runBlocking {
            val dialogue = stagedModels()
            // Same names, different detector bytes — exactly the `ocr-manga-v4` / `ocr-manga-v3` pair.
            val sfx = dialogue + (DET_MODEL to "sfx-det-with-other-bytes".toByteArray())
            val urls = CopyOnWriteArrayList<String>()
            val preferences = OcrPreferences(InMemoryPreferenceStore())
            val downloader = downloader(
                mockContext(dir),
                serveReleases(
                    urls,
                    dir,
                    listOf(ModelRelease.DIALOGUE_FINE_TUNED to dialogue, ModelRelease.SFX_FINE_TUNED to sfx),
                ),
                enabled = true,
                preferences = preferences,
            )

            downloader.downloadModels().isSuccess shouldBe true
            File(downloader.modelsDir, DET_MODEL).readText() shouldBe "$DET_MODEL bytes"
            installedReleaseMarker(downloader.modelsDir) shouldBe "ocr-manga-v4"

            preferences.detModelVariant.set(DetModelVariant.SFX.name)

            // The old detector file is still there under the same name; the marker says otherwise.
            downloader.isPaddleDownloaded shouldBe false

            downloader.downloadModels().isSuccess shouldBe true

            File(downloader.modelsDir, DET_MODEL).readText() shouldBe "sfx-det-with-other-bytes"
            installedReleaseMarker(downloader.modelsDir) shouldBe "ocr-manga-v3"
            downloader.isPaddleDownloaded shouldBe true
            // The shared detector really was fetched again from the new release, and the files whose
            // bytes are identical in both releases were not re-downloaded.
            urls.filter { it.endsWith("/$DET_MODEL") } shouldBe listOf(
                "${ModelRelease.DIALOGUE_FINE_TUNED.baseUrl}/$DET_MODEL",
                "${ModelRelease.SFX_FINE_TUNED.baseUrl}/$DET_MODEL",
            )
            urls.count { it.endsWith("/$DICT_MODEL") } shouldBe 1
        }

    /**
     * The A->B->A defect: a trigger arriving while a run is in flight is coalesced, and the in-flight
     * run snapshotted *its* target. Flipping dialogue -> SFX -> dialogue quickly therefore installed
     * SFX and left nothing to schedule a dialogue download: the selection said dialogue while the
     * engine kept loading the SFX detector. The run now re-checks the selection when a pass finishes
     * and keeps going, so the run must end with the **selected** release installed.
     */
    @Test
    fun `a selection flipped back while a run is in flight still ends with the selected release`(@TempDir dir: File): Unit =
        runBlocking {
            val dialogue = stagedModels()
            // Same names, different detector bytes — the `ocr-manga-v4` / `ocr-manga-v3` pair.
            val sfx = dialogue + (DET_MODEL to "sfx-det-with-other-bytes".toByteArray())
            val preferences = OcrPreferences(InMemoryPreferenceStore())
            val urls = CopyOnWriteArrayList<String>()
            val inFlight = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val downloader = downloader(
                mockContext(dir),
                // The *first* detector request blocks, so the test can flip the selection while a run
                // (which has already snapshotted SFX) is still in flight.
                serveReleases(
                    urls,
                    dir,
                    listOf(ModelRelease.DIALOGUE_FINE_TUNED to dialogue, ModelRelease.SFX_FINE_TUNED to sfx),
                    blockOnceSuffix = "/$DET_MODEL",
                    blocked = inFlight,
                    unblock = unblock,
                ),
                enabled = true,
                preferences = preferences,
            )

            // 1. Switch to SFX: the run snapshots SFX and starts downloading its detector.
            preferences.detModelVariant.set(DetModelVariant.SFX.name)
            downloader.triggerPaddleDownload()
            inFlight.await(10, TimeUnit.SECONDS) shouldBe true

            // 2. Flip back to dialogue while it is in flight: this trigger is coalesced away...
            preferences.detModelVariant.set(DetModelVariant.DIALOGUE.name)
            downloader.triggerPaddleDownload()

            // 3. ...so the in-flight run must notice by itself that the selection moved back.
            unblock.countDown()
            withTimeout(30_000) { downloader.state.first { it is ModelDownloadState.Completed } }

            installedReleaseMarker(downloader.modelsDir) shouldBe ModelRelease.DIALOGUE_FINE_TUNED.release
            File(downloader.modelsDir, DET_MODEL).readText() shouldBe "$DET_MODEL bytes"
            downloader.isPaddleDownloaded shouldBe true
            // Both releases' detectors really were fetched (SFX for the in-flight pass, dialogue after).
            urls.filter { it.endsWith("/$DET_MODEL") }.distinct() shouldContainExactlyInAnyOrder
                listOf(
                    "${ModelRelease.SFX_FINE_TUNED.baseUrl}/$DET_MODEL",
                    "${ModelRelease.DIALOGUE_FINE_TUNED.baseUrl}/$DET_MODEL",
                )
        }

    // ------------------------------------------------------------------ triggerPaddleDownload

    @Test
    fun `triggering a download does nothing while disabled`(@TempDir dir: File) {
        val models = stagedModels()
        val requests = AtomicInteger()
        val latch = CountDownLatch(1)
        val downloader = downloader(
            dir,
            cannedRelease(
                requests,
                CopyOnWriteArrayList(),
                latch,
                manifestJson(models, dir, flat = true),
                flatFiles(models),
            ),
            enabled = false,
        )

        downloader.triggerPaddleDownload()

        // The trigger returns before launching anything, so no request can ever reach the interceptor.
        // (`triggering a download still runs while enabled` proves this latch does fire on a real run.)
        latch.await(2, TimeUnit.SECONDS) shouldBe false
        requests.get() shouldBe 0
        downloader.state.value shouldBe ModelDownloadState.Idle
        downloader.modelsDir.exists() shouldBe false
    }

    @Test
    fun `triggering a download still runs while enabled`(@TempDir dir: File): Unit = runBlocking {
        val models = stagedModels()
        val requests = AtomicInteger()
        val latch = CountDownLatch(1)
        val downloader = downloader(
            dir,
            cannedRelease(
                requests,
                CopyOnWriteArrayList(),
                latch,
                manifestJson(models, dir, flat = true),
                flatFiles(models),
            ),
            enabled = true,
        )

        downloader.triggerPaddleDownload()

        latch.await(10, TimeUnit.SECONDS) shouldBe true
        withTimeout(10_000) {
            downloader.state.first { it is ModelDownloadState.Completed }
        } shouldBe ModelDownloadState.Completed
        models.forEach { (name, bytes) -> File(downloader.modelsDir, name).readBytes() shouldBe bytes }
    }

    // ------------------------------------------------------------------ helpers

    /** The model files the engine loads; the recogniser name follows the selectable variant. */
    private val modelNames = listOf(DET_MODEL, RecModelVariant.DEFAULT.recModel, DICT_MODEL)

    private fun stagedModels(): Map<String, ByteArray> =
        modelNames.associateWith { name -> "$name bytes".toByteArray() }

    /** The download root of the default (dialogue-first) release, where the fake serves files. */
    private val baseUrl = ModelRelease.DEFAULT.baseUrl

    /** Release download root, i.e. [baseUrl] without the pinned release tag appended. */
    private val releaseRoot = baseUrl.substringBeforeLast('/')

    /**
     * A manifest that describes exactly [models] (real SHA-256 + size), as a JSON body.
     *
     * [flat] models the published releases: every entry carries an explicit `path` equal to its name,
     * because GitHub release assets are flat. `flat = false` leaves `path` out, which makes the
     * downloader use its `models/<name>` fallback. [release] defaults to the pinned release; pass an
     * older tag to model a manifest cached by a previous install.
     */
    private fun manifestJson(
        models: Map<String, ByteArray>,
        dir: File,
        flat: Boolean,
        release: String = ModelRelease.DEFAULT.release,
    ): String {
        val staging = File(dir, "staging").apply { mkdirs() }
        val manifest = OcrModelManifest(
            release = release,
            baseUrl = "$releaseRoot/$release",
            files = models.map { (name, bytes) ->
                val file = File(staging, name).apply { writeBytes(bytes) }
                OcrModelFile(
                    name = name,
                    path = if (flat) name else null,
                    sha256 = sha256Hex(file),
                    size = file.length(),
                )
            },
        )
        return Json.encodeToString(manifest)
    }

    /**
     * The flat release layout: `/<name>` under the release root for every model (GitHub publishes
     * release assets flat). Keyed by the suffix the downloader appends to the base URL.
     */
    private fun flatFiles(models: Map<String, ByteArray>): Map<String, ByteArray> =
        models.mapKeys { (name, _) -> "/$name" }

    /** Seeds the manifest the downloader caches beside the models, as an earlier release left it. */
    private fun writeCachedManifest(modelsDir: File, models: Map<String, ByteArray>, dir: File, release: String) {
        File(modelsDir, MANIFEST_NAME).writeText(manifestJson(models, dir, flat = true, release = release))
    }

    /** Seeds the installed-release marker, as a completed install of another release left it. */
    private fun writeMarker(modelsDir: File, release: String) {
        writeInstalledReleaseMarker(modelsDir, release)
    }

    private fun mockContext(dir: File): Context {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns dir
        // `importFromUri` stages the picked archive in `cacheDir`.
        every { context.cacheDir } returns File(dir, "cache").apply { mkdirs() }
        return context
    }

    private fun downloader(dir: File, client: OkHttpClient, enabled: Boolean) =
        downloader(mockContext(dir), client, enabled)

    private fun downloader(
        context: Context,
        client: OkHttpClient,
        enabled: Boolean,
        preferences: OcrPreferences = OcrPreferences(InMemoryPreferenceStore()),
    ) = ModelDownloader(
        context = context,
        httpClient = client,
        json = Json,
        preferences = preferences,
        dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(enabled) },
    )

    /**
     * A fake serving several releases at once, **keyed by the full request URL**, so a request for
     * the wrong release 404s instead of silently succeeding. Used by the variant-switch test, where
     * both releases publish the same file names. Every request URL is recorded in [urls].
     */
    private fun serveReleases(
        urls: MutableList<String>,
        dir: File,
        releases: List<Pair<ModelRelease, Map<String, ByteArray>>>,
        /** When set, the first request whose URL ends with this suffix blocks until [unblock]. */
        blockOnceSuffix: String? = null,
        blocked: CountDownLatch? = null,
        unblock: CountDownLatch? = null,
    ): OkHttpClient {
        val served = buildMap {
            releases.forEach { (release, models) ->
                put(
                    "${release.baseUrl}/$MANIFEST_NAME",
                    manifestJson(models, dir, flat = true, release = release.release).toByteArray(),
                )
                models.forEach { (name, bytes) -> put("${release.baseUrl}/$name", bytes) }
            }
        }
        val blockedOnce = AtomicBoolean(false)
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                urls += url
                if (blockOnceSuffix != null && url.endsWith(blockOnceSuffix) && blockedOnce.compareAndSet(false, true)) {
                    blocked?.countDown()
                    unblock?.await(30, TimeUnit.SECONDS)
                }
                val body = served[url]
                if (body == null) respond(chain, code = 404, body = ByteArray(0)) else respond(chain, code = 200, body = body)
            }
            .build()
    }

    /** Canned HTTP 500 for every request (counts the calls that would have hit the network). */
    private fun failingClient(requests: AtomicInteger): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                respond(chain, code = 500, body = ByteArray(0))
            }
            .build()

    /**
     * A canned release **keyed by the full request URL**, so the fake is path-sensitive: the flat
     * `/<name>` layout and the `models/<name>` fallback are different URLs, and only the one the
     * downloader actually requests is served. (The previous fake keyed on the last path segment, so
     * it answered a `models/<name>` request with a flat asset and hid the real 404 shape.)
     *
     * [files] is keyed by the suffix appended to [baseUrl] — `/name` for the flat layout. Every
     * request URL is recorded in [urls]; [latch] fires on the first one.
     */
    private fun cannedRelease(
        requests: AtomicInteger,
        urls: MutableList<String>,
        latch: CountDownLatch,
        manifestJson: String,
        files: Map<String, ByteArray>,
    ): OkHttpClient {
        val served = buildMap {
            put("$baseUrl/$MANIFEST_NAME", manifestJson.toByteArray())
            files.forEach { (suffix, bytes) -> put("$baseUrl$suffix", bytes) }
        }
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                urls += chain.request().url.toString()
                latch.countDown()
                val body = served[chain.request().url.toString()]
                if (body == null) respond(chain, code = 404, body = ByteArray(0)) else respond(chain, code = 200, body = body)
            }
            .build()
    }

    private fun respond(chain: Interceptor.Chain, code: Int, body: ByteArray): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Canned $code")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()

    /** A model archive as a user would pick it, with the models nested under `models/`. */
    private fun zipOf(models: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            models.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry("models/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        private const val DET_MODEL = "ppocrv6_small_det.onnx"
        private const val DICT_MODEL = "ppocrv6_small_dict.txt"
        private const val MANIFEST_NAME = "manifest.json"
    }
}
