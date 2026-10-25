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
// on an in-place upgrade; the non-empty fallback survives only when no `manifest.json` is cached.
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
            modelNames + "manifest.json",
        )
        models.forEach { (name, bytes) -> File(downloader.modelsDir, name).readBytes() shouldBe bytes }
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
     * The release pin moved (`ocr-manga-v2` → the pinned release, whose detector is a different file
     * of a different size). A cached v2 manifest next to the v2 files must **not** report "OCR ready":
     * the sizes match their own v2 manifest, so only the release-currency check can see the staleness,
     * and without it an upgrading user never downloads the new detector.
     */
    @Test
    fun `the readiness gate rejects weights cached from the previous fine-tune release`(@TempDir dir: File) {
        val models = stagedModels()
        val downloader = downloader(dir, failingClient(AtomicInteger()), enabled = true)
        val modelsDir = downloader.modelsDir.apply { mkdirs() }
        models.forEach { (name, bytes) -> File(modelsDir, name).writeBytes(bytes) }

        // Positive control first: the same bytes under a manifest from the pinned release do read as
        // ready, so the `false` below is the stale release and not a missing file or an unread manifest.
        writeCachedManifest(modelsDir, models, dir, release = RecModelVariant.DEFAULT.release)
        downloader.isPaddleDownloaded shouldBe true

        writeCachedManifest(modelsDir, models, dir, release = "ocr-manga-v2")
        downloader.isPaddleDownloaded shouldBe false
    }

    /**
     * The pre-manifest legacy install is the one case the currency check cannot judge: there is no
     * cached `manifest.json` at all, so a non-empty file set stays "ready" (and [downloadModels] still
     * re-verifies by SHA-256 when it eventually runs).
     */
    @Test
    fun `the readiness gate keeps the non-empty fallback when no manifest is cached`(@TempDir dir: File) {
        val models = stagedModels()
        val downloader = downloader(dir, failingClient(AtomicInteger()), enabled = true)
        val modelsDir = downloader.modelsDir.apply { mkdirs() }
        models.forEach { (name, bytes) -> File(modelsDir, name).writeBytes(bytes) }
        File(modelsDir, MANIFEST_NAME).exists() shouldBe false

        downloader.isPaddleDownloaded shouldBe true

        // An empty file is not a model, with or without a manifest.
        File(modelsDir, DET_MODEL).writeBytes(ByteArray(0))
        downloader.isPaddleDownloaded shouldBe false
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

    /** The pinned release root, i.e. where the manifest and the model files are fetched from. */
    private val baseUrl = RecModelVariant.DEFAULT.baseUrl

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
        release: String = RecModelVariant.DEFAULT.release,
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

    private fun downloader(context: Context, client: OkHttpClient, enabled: Boolean) = ModelDownloader(
        context = context,
        httpClient = client,
        json = Json,
        preferences = OcrPreferences(InMemoryPreferenceStore()),
        dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(enabled) },
    )

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
