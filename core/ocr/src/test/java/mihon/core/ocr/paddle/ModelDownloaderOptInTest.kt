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
        val downloader = downloader(
            dir,
            cannedClient(requests, CountDownLatch(1), manifestJson(models, dir), models),
            enabled = true,
        )

        val result = downloader.downloadModels()

        result.isSuccess shouldBe true
        downloader.modelsDir.listFiles().orEmpty().map { it.name }.shouldContainExactlyInAnyOrder(
            modelNames + "manifest.json",
        )
        models.forEach { (name, bytes) -> File(downloader.modelsDir, name).readBytes() shouldBe bytes }
        // One manifest fetch + one download per model, and nothing re-fetched.
        requests.get() shouldBe modelNames.size + 1
        downloader.isPaddleDownloaded shouldBe true
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
            cannedClient(requests, CountDownLatch(1), manifestJson(models, dir), models),
            enabled = true,
        )

        val result = downloader.importFromUri(uri)

        result.isSuccess shouldBe true
        models.forEach { (name, bytes) -> File(downloader.modelsDir, name).readBytes() shouldBe bytes }
        verify(exactly = 1) { context.contentResolver.openInputStream(uri) }
        downloader.isPaddleDownloaded shouldBe true
    }

    // ------------------------------------------------------------------ triggerPaddleDownload

    @Test
    fun `triggering a download does nothing while disabled`(@TempDir dir: File) {
        val models = stagedModels()
        val requests = AtomicInteger()
        val latch = CountDownLatch(1)
        val downloader = downloader(
            dir,
            cannedClient(requests, latch, manifestJson(models, dir), models),
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
            cannedClient(requests, latch, manifestJson(models, dir), models),
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

    /** A manifest that describes exactly [models] (real SHA-256 + size), as a JSON body. */
    private fun manifestJson(models: Map<String, ByteArray>, dir: File): String {
        val staging = File(dir, "staging").apply { mkdirs() }
        val manifest = OcrModelManifest(
            release = RecModelVariant.DEFAULT.release,
            baseUrl = RecModelVariant.DEFAULT.baseUrl,
            files = models.map { (name, bytes) ->
                val file = File(staging, name).apply { writeBytes(bytes) }
                OcrModelFile(name = name, sha256 = sha256Hex(file), size = file.length())
            },
        )
        return Json.encodeToString(manifest)
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
     * Serves the canned [manifestJson] and [models] by file name, so the real download/verify/
     * install path runs end to end without a socket. [latch] fires on the first request.
     */
    private fun cannedClient(
        requests: AtomicInteger,
        latch: CountDownLatch,
        manifestJson: String,
        models: Map<String, ByteArray>,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests.incrementAndGet()
            latch.countDown()
            val name = chain.request().url.encodedPath.substringAfterLast('/')
            val body = if (name == MANIFEST_NAME) manifestJson.toByteArray() else models[name]
            if (body == null) respond(chain, code = 404, body = ByteArray(0)) else respond(chain, code = 200, body = body)
        }
        .build()

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
