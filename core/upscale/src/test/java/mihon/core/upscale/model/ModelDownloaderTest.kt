// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The model downloader: no network while the feature is off, checksum-gated installs, the marker
// written only after every file verifies, and pruning that touches only files this app published.
//
// The HTTP side is an OkHttp interceptor that answers from a map, so the tests are offline and the
// "was a request attempted?" question is answered by counting them.
// KUN <--
package mihon.core.upscale.model

import android.content.Context
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.UpscalePreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ModelDownloaderTest {

    private val filesDir: File = Files.createTempDirectory("upscale-files").toFile()
    private val context: Context = mockk<Context>().also { context ->
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
    }
    private val preferences = UpscalePreferences(InMemoryPreferenceStore())

    private val bytesA = "model-a-bytes".toByteArray()
    private val bytesB = "model-b-bytes".toByteArray()

    @Test
    fun `no request is attempted while the feature is disabled`() {
        val server = FakeServer(emptyMap())
        val downloader = downloader(server)
        preferences.enabled.set(false)

        val result = runBlocking { downloader.downloadModels() }

        result.isFailure shouldBe true
        server.requested shouldBe emptyList()
        downloader.modelsDir.exists() shouldBe false
        downloader.installedIds() shouldBe emptySet<String>()
    }

    @Test
    fun `a SHA-256 mismatch rejects the file and records nothing`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(
                    entry("a.onnx", id = "model_a", sha256 = "deadbeef"),
                ),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server)
        preferences.enabled.set(true)

        val result = runBlocking { downloader.downloadModels() }

        result.isFailure shouldBe true
        File(downloader.modelsDir, "a.onnx").exists() shouldBe false
        File(downloader.modelsDir, "a.onnx.part").exists() shouldBe false
        installedReleaseMarker(downloader.modelsDir) shouldBe null
        downloader.installedIds() shouldBe emptySet<String>()
    }

    @Test
    fun `the release marker is written only after every selected file verified`() {
        val good = entry("a.onnx", id = "model_a", sha256 = sha256(bytesA))
        val bad = entry("b.onnx", id = "model_b", sha256 = "deadbeef")
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(good, bad),
                "a.onnx" to bytesA,
                "b.onnx" to bytesB,
            ),
        )
        val downloader = downloader(server)
        preferences.enabled.set(true)

        runBlocking { downloader.downloadModels() }.isFailure shouldBe true

        // The good file landed, but a partial set must not be vouched for.
        File(downloader.modelsDir, "a.onnx").isFile shouldBe true
        installedReleaseMarker(downloader.modelsDir) shouldBe null
        downloader.installedIds() shouldBe emptySet<String>()

        // The second run sees a corrected manifest and completes.
        server.put("manifest.json", manifest(good, bad.copy(sha256 = sha256(bytesB))))
        runBlocking { downloader.downloadModels() }.isSuccess shouldBe true

        installedReleaseMarker(downloader.modelsDir) shouldBe "upscale-models-v1"
        downloader.installedIds() shouldBe setOf("model_a", "model_b")
    }

    @Test
    fun `pruning removes a superseded name but keeps every legacy name the catalog still publishes`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(entry("a.onnx", id = "model_a", sha256 = sha256(bytesA))),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server)
        preferences.enabled.set(true)
        downloader.modelsDir.mkdirs()
        // The defect this pins: `LEGACY_MODEL_NAMES` also carries names the catalog still publishes
        // (the pre-fp16 exports share the catalog's unsuffixed names), so pruning them would delete a
        // model a subset install left installed.
        val stillPublished = ModelDownloader.LEGACY_MODEL_NAMES.filter { catalogIdFor(it) != null }
        stillPublished shouldHaveSize 4
        stillPublished.forEach { File(downloader.modelsDir, it).writeText("live weights") }
        // A name that is only in the legacy allow-list has to go.
        val superseded = File(downloader.modelsDir, SUPERSEDED_NAME)
        superseded.writeText("old weights")
        val userFile = File(downloader.modelsDir, "my-notes.txt")
        userFile.writeText("do not delete me")

        val removed = downloader.cleanupStaleModels(
            staleNames = ModelDownloader.LEGACY_MODEL_NAMES + SUPERSEDED_NAME,
        )

        removed shouldBe listOf(SUPERSEDED_NAME)
        superseded.exists() shouldBe false
        stillPublished.forEach { File(downloader.modelsDir, it).isFile shouldBe true }
        userFile.isFile shouldBe true
    }

    @Test
    fun `a subset install does not delete a legacy name the catalog still publishes`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(entry("a.onnx", id = "model_a", sha256 = sha256(bytesA))),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server)
        preferences.enabled.set(true)
        downloader.modelsDir.mkdirs()
        val legacy = ModelDownloader.LEGACY_MODEL_NAMES.first()
        catalogIdFor(legacy) shouldNotBe null
        File(downloader.modelsDir, legacy).writeText("live weights")

        runBlocking { downloader.downloadModels() }.isSuccess shouldBe true

        File(downloader.modelsDir, legacy).isFile shouldBe true
        downloader.modelsDir.listFiles().orEmpty().map { it.name } shouldContain "a.onnx"
    }

    @Test
    fun `a metered network is refused while only unmetered downloads are allowed`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(entry("a.onnx", id = "model_a", sha256 = sha256(bytesA))),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server, metered = true)
        preferences.enabled.set(true)
        preferences.unmeteredOnly.get() shouldBe true

        val result = runBlocking { downloader.downloadModels() }

        // The defect this pins: the preference was honoured only by the settings screen, so a
        // download started on Wi-Fi kept spending data after a switch to cellular.
        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe ModelDownloader.METERED_ONLY_MESSAGE
        server.requested shouldBe emptyList()
        downloader.modelsDir.exists() shouldBe false
    }

    @Test
    fun `an unmetered network downloads while only unmetered downloads are allowed`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(entry("a.onnx", id = "model_a", sha256 = sha256(bytesA))),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server, metered = false)
        preferences.enabled.set(true)

        runBlocking { downloader.downloadModels() }.isSuccess shouldBe true

        File(downloader.modelsDir, "a.onnx").isFile shouldBe true
        downloader.installedIds() shouldBe setOf("model_a")
    }

    @Test
    fun `a metered network is fine when the unmetered-only preference is off`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(entry("a.onnx", id = "model_a", sha256 = sha256(bytesA))),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server, metered = true)
        preferences.enabled.set(true)
        preferences.unmeteredOnly.set(false)

        runBlocking { downloader.downloadModels() }.isSuccess shouldBe true

        downloader.installedIds() shouldBe setOf("model_a")
    }

    @Test
    fun `a switch to a metered network mid-run stops the download before the next file`() {
        var meteredNow = false
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(
                    entry("a.onnx", id = "model_a", sha256 = sha256(bytesA)),
                    entry("b.onnx", id = "model_b", sha256 = sha256(bytesB)),
                ),
                "a.onnx" to bytesA,
                "b.onnx" to bytesB,
            ),
        )
        // The handover happens while the first file is being served.
        server.onServed = { name -> if (name == "a.onnx") meteredNow = true }
        val downloader = ModelDownloader(
            context = context,
            httpClient = OkHttpClient.Builder().addInterceptor(server).build(),
            json = Json { ignoreUnknownKeys = true },
            preferences = preferences,
            manifestUrl = "$BASE_URL/manifest.json",
            metered = { meteredNow },
        )
        preferences.enabled.set(true)

        val result = runBlocking { downloader.downloadModels() }

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe ModelDownloader.METERED_ONLY_MESSAGE
        // The first file landed; the second was never requested.
        File(downloader.modelsDir, "a.onnx").isFile shouldBe true
        server.requested shouldBe listOf("manifest.json", "a.onnx")
        File(downloader.modelsDir, "b.onnx").exists() shouldBe false
        File(downloader.modelsDir, "b.onnx.part").exists() shouldBe false
        installedReleaseMarker(downloader.modelsDir) shouldBe null
    }

    @Test
    fun `a body shorter than the manifest size is rejected even when the hash matches`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(
                    UpscaleModelFile(
                        name = "a.onnx",
                        id = "model_a",
                        sha256 = sha256(bytesA),
                        // The real file is longer; the hash of what we got is still correct, so only
                        // the size check can catch the truncation.
                        size = bytesA.size.toLong() + 10,
                    ),
                ),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server)
        preferences.enabled.set(true)

        val result = runBlocking { downloader.downloadModels() }

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe
            "size mismatch for a.onnx: ${bytesA.size} != ${bytesA.size + 10}"
        File(downloader.modelsDir, "a.onnx").exists() shouldBe false
        File(downloader.modelsDir, "a.onnx.part").exists() shouldBe false
        installedReleaseMarker(downloader.modelsDir) shouldBe null
    }

    @Test
    fun `a manifest name or path that leaves the models directory is rejected before any fetch`() {
        // Each of these would make `File(modelsDir, name)` address something other than a model file
        // inside the directory: a parent escape, an absolute path, and a nested path.
        for (escaping in listOf("../escaped.onnx", "/tmp/escaped.onnx", "sub/escaped.onnx")) {
            val server = FakeServer(
                mapOf(
                    "manifest.json" to manifest(
                        UpscaleModelFile(
                            name = escaping,
                            id = "model_a",
                            sha256 = sha256(bytesA),
                            size = bytesA.size.toLong(),
                        ),
                    ),
                    "escaped.onnx" to bytesA,
                ),
            )
            val downloader = downloader(server)
            preferences.enabled.set(true)

            val result = runBlocking { downloader.downloadModels() }

            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe
                "the manifest's model name \"$escaping\" is not a single path segment"
            // Only the manifest was fetched: no model byte was ever requested or written.
            server.requested shouldBe listOf("manifest.json")
            File(filesDir, "escaped.onnx").exists() shouldBe false
            File(filesDir, "sub/escaped.onnx").exists() shouldBe false
            File(File(filesDir, "upscale"), "escaped.onnx").exists() shouldBe false
        }
    }

    @Test
    fun `an escaping path is rejected too, not only the name`() {
        val server = FakeServer(
            mapOf(
                "manifest.json" to manifest(
                    UpscaleModelFile(
                        name = "a.onnx",
                        path = "../../a.onnx",
                        id = "model_a",
                        sha256 = sha256(bytesA),
                        size = bytesA.size.toLong(),
                    ),
                ),
                "a.onnx" to bytesA,
            ),
        )
        val downloader = downloader(server)
        preferences.enabled.set(true)

        val result = runBlocking { downloader.downloadModels() }

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe
            "the manifest's model path \"../../a.onnx\" is not a single path segment"
        server.requested shouldBe listOf("manifest.json")
    }

    @Test
    fun `a cached manifest with an escaping name is not used to delete anything`() {
        val downloader = downloader(FakeServer(emptyMap()))
        downloader.modelsDir.mkdirs()
        // A poisoned cache entry: `<modelsDir>/../escaped.onnx.part` resolves outside the directory.
        File(File(filesDir, "upscale"), "escaped.onnx.part").writeText("not ours")
        File(downloader.modelsDir, "manifest.json").writeText(
            manifest(entry("../escaped.onnx", id = "model_a", sha256 = sha256(bytesA))).decodeToString(),
        )

        downloader.cleanupPartFiles() shouldBe emptyList()
        downloader.installedIds() shouldBe emptySet<String>()
        File(File(filesDir, "upscale"), "escaped.onnx.part").isFile shouldBe true
    }

    // ------------------------------------------------------------------ helpers

    private fun downloader(server: FakeServer, metered: Boolean = false): ModelDownloader = ModelDownloader(
        context = context,
        httpClient = OkHttpClient.Builder().addInterceptor(server).build(),
        json = Json { ignoreUnknownKeys = true },
        preferences = preferences,
        manifestUrl = "$BASE_URL/manifest.json",
        metered = { metered },
    )

    private fun manifest(vararg files: UpscaleModelFile): ByteArray =
        Json.encodeToString(
            UpscaleModelManifest.serializer(),
            UpscaleModelManifest(release = "upscale-models-v1", baseUrl = BASE_URL, files = files.toList()),
        ).toByteArray()

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun entry(name: String, id: String, sha256: String): UpscaleModelFile =
        UpscaleModelFile(name = name, id = id, sha256 = sha256, size = 0)

    private companion object {
        const val BASE_URL = "https://models.test/upscale-models-v1"

        /** A v0-era export name that is *not* a catalog `fileName` (so pruning must delete it). */
        const val SUPERSEDED_NAME = "2x_MangaJaNai_1600p_V1_ESRGAN_60k.onnx"
    }
}

/** An offline release server: answers by file name, and counts every request made. */
private class FakeServer(initial: Map<String, ByteArray>) : Interceptor {

    private val files = initial.toMutableMap()

    /** Names of every asset requested, in order — the "was it ever fetched?" answer. */
    val requested = mutableListOf<String>()

    /** Called with each served name, so a test can change state mid-run (a Wi-Fi handover). */
    var onServed: (String) -> Unit = {}

    fun put(name: String, bytes: ByteArray) {
        files[name] = bytes
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val name = chain.request().url.pathSegments.last()
        val body = files[name] ?: throw IOException("no such asset: $name")
        requested += name
        onServed(name)
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }
}
