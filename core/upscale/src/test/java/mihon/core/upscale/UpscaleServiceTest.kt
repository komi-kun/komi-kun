// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The reader-facing queue: what happens to a request while the feature is off, when two requests name
// the same page, when the reader moves to another page, and when it cancels.
//
// The engine is real (with a fake inference), so these tests exercise the actual thread, the actual
// session lifetime and the actual cache write — only the model is fake.
// KUN <--
package mihon.core.upscale

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mihon.core.upscale.cache.UpscaleCache
import mihon.core.upscale.engine.ArgbSurfaces
import mihon.core.upscale.engine.DecodedPage
import mihon.core.upscale.engine.TileInference
import mihon.core.upscale.engine.TileInput
import mihon.core.upscale.engine.UpscaleEngine
import mihon.core.upscale.engine.UpscaleResult
import mihon.core.upscale.model.InstalledModels
import mihon.core.upscale.model.ModelArch
import mihon.core.upscale.model.ModelFamily
import mihon.core.upscale.model.UpscaleModel
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.UpscalePreferences
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpscaleServiceTest {

    private val root: File = Files.createTempDirectory("upscale-service").toFile()
    private val cache = UpscaleCache(File(root, "cache"), maxBytes = { Long.MAX_VALUE })
    private val modelFile = File(root, "fake_2x.onnx").apply { writeText("stub weights") }
    private val preferences = UpscalePreferences(InMemoryPreferenceStore())

    /** Pages the queue actually started, in order. */
    private val started = ConcurrentLinkedQueue<Int>()

    /** Called by the fake inference for every tile. */
    private var onInfer: () -> Unit = {}

    /** Pages the queue actually encoded, in order (same order as [started] unless a page failed). */
    private val encoded = ConcurrentLinkedQueue<Int>()

    /**
     * The page being processed right now. Every job runs on the engine's single thread, so this is a
     * plain field: the decoder sets it, the encoder reads it.
     */
    private var currentPage: Int = -1

    /** Tiles the fake model was asked to infer, i.e. "did the engine run at all". */
    private val inferredTiles = AtomicInteger()

    /** Called by the fake decoder before it returns; lets a test hold a page inside the decode. */
    private var decodeGate: () -> Unit = {}

    @Test
    fun `a request is dropped while the feature is disabled`() {
        preferences.enabled.set(false)
        val service = service()

        val done = CountDownLatch(1)
        service.request(page(0), bytes(0), MODEL, 2, SIGNATURE) { done.countDown() }

        done.await(5, TimeUnit.SECONDS) shouldBe false
        started.shouldBeEmpty()
        service.cached(page(0), MODEL, 2, SIGNATURE) shouldBe null
    }

    @Test
    fun `a request for a model that is not installed is dropped`() {
        preferences.enabled.set(true)
        val other = MODEL.copy(id = "not_installed", fileName = "other.onnx")
        val service = service(installed = emptySet())

        val done = CountDownLatch(1)
        service.request(page(0), bytes(0), other, 2, SIGNATURE) { done.countDown() }

        done.await(5, TimeUnit.SECONDS) shouldBe false
        started.shouldBeEmpty()
    }

    @Test
    fun `two requests for the same page run once and both callers are told when it is done`() {
        preferences.enabled.set(true)
        val service = service()

        val done = CountDownLatch(2)
        service.request(page(0), bytes(0), MODEL, 2, SIGNATURE) { done.countDown() }
        service.request(page(0), bytes(0), MODEL, 2, SIGNATURE) { done.countDown() }

        done.await(10, TimeUnit.SECONDS) shouldBe true
        started.toList() shouldBe listOf(0)
        encoded.toList() shouldBe listOf(0)
        service.cached(page(0), MODEL, 2, SIGNATURE)!!.isFile shouldBe true
    }

    @Test
    fun `an already cached page is answered without running the engine again`() {
        preferences.enabled.set(true)
        runBlocking {
            cache.put(keyOf(0)) { it.writeText("already here") }
        }
        val service = service()

        val done = CountDownLatch(1)
        service.request(page(0), bytes(0), MODEL, 2, SIGNATURE) { done.countDown() }

        done.await(5, TimeUnit.SECONDS) shouldBe true
        started.shouldBeEmpty()
        service.cached(page(0), MODEL, 2, SIGNATURE)!!.readText() shouldBe "already here"
    }

    @Test
    fun `focus serves the visible page first, then the next three, and drops other chapters`() {
        preferences.enabled.set(true)
        // Hold the first tile so every later request is queued before the queue is walked again.
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        onInfer = {
            if (blocked.count == 1L) {
                blocked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val service = service()

        val done = CountDownLatch(4)
        // Page 3 of the focused chapter is what gets the engine busy first.
        service.request(page(3, chapter = 7), bytes(3), MODEL, 2, SIGNATURE) { done.countDown() }
        blocked.await(10, TimeUnit.SECONDS) shouldBe true

        service.request(page(5, chapter = 7), bytes(5), MODEL, 2, SIGNATURE) { done.countDown() }
        service.request(page(1, chapter = 7), bytes(1), MODEL, 2, SIGNATURE) { done.countDown() }
        service.request(page(2, chapter = 7), bytes(2), MODEL, 2, SIGNATURE) { done.countDown() }
        // Another chapter is queued and must be dropped by the focus.
        service.request(page(0, chapter = 8), bytes(0), MODEL, 2, SIGNATURE)

        service.focus(chapterId = 7, pageIndex = 1)
        release.countDown()

        done.await(10, TimeUnit.SECONDS) shouldBe true
        started.toList() shouldBe listOf(3, 1, 2, 5)
        encoded.toList() shouldBe listOf(3, 1, 2, 5)
    }

    @Test
    fun `cancelAll stops the running page before it is written to the cache`() {
        preferences.enabled.set(true)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        onInfer = {
            if (blocked.count == 1L) {
                blocked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val service = service()

        val done = CountDownLatch(1)
        service.request(page(0), bytes(0), MODEL, 2, SIGNATURE) { done.countDown() }
        blocked.await(10, TimeUnit.SECONDS) shouldBe true

        service.cancelAll()
        release.countDown()

        done.await(10, TimeUnit.SECONDS) shouldBe true
        encoded.shouldBeEmpty()
        service.cached(page(0), MODEL, 2, SIGNATURE) shouldBe null
    }

    /**
     * Catches a cancel token that is read *after* the bytes read and the decode: `cancelAll` swaps the
     * token, so the page would then run its whole inference and overwrite the cache entry for the
     * chapter the reader already left. Cancelling while the decoder is in flight must mean "no
     * inference, no entry" — and it must be reported as a cancellation, not as a page that can never
     * be upscaled (the reader remembers those).
     */
    @Test
    fun `a cancel while the page is decoding runs no inference and writes nothing`() {
        preferences.enabled.set(true)
        val decoding = CountDownLatch(1)
        val release = CountDownLatch(1)
        decodeGate = {
            decoding.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val service = service()

        val outcomes = ConcurrentLinkedQueue<UpscaleOutcome>()
        val done = CountDownLatch(1)
        service.requestAuto(page(0), bytes(0), 2, SIGNATURE, route = { MODEL }) {
            outcomes += it
            done.countDown()
        }
        decoding.await(10, TimeUnit.SECONDS) shouldBe true

        service.cancelAll()
        release.countDown()

        done.await(10, TimeUnit.SECONDS) shouldBe true
        outcomes.toList() shouldBe listOf(UpscaleOutcome.CANCELLED)
        inferredTiles.get() shouldBe 0
        service.cached(page(0), MODEL, 2, SIGNATURE) shouldBe null
    }

    /**
     * The other half of the report: a page that really did fail says so, so the reader can stop asking
     * for it. A cancellation must *not* be reported this way (previous test), or a page turn that
     * happened to interrupt a page would blacklist it.
     */
    @Test
    fun `a page the router refuses is reported as such`() {
        preferences.enabled.set(true)
        val service = service()

        val outcomes = ConcurrentLinkedQueue<UpscaleOutcome>()
        val done = CountDownLatch(1)
        service.requestAuto(page(0), bytes(0), 2, SIGNATURE, route = { null }) {
            outcomes += it
            done.countDown()
        }

        done.await(10, TimeUnit.SECONDS) shouldBe true
        outcomes.toList() shouldBe listOf(UpscaleOutcome.NO_ROUTE)
        inferredTiles.get() shouldBe 0
        service.cached(page(0), MODEL, 2, SIGNATURE) shouldBe null
    }

    // ------------------------------------------------------------------ helpers

    private fun service(installed: Set<String> = setOf(MODEL.id)): UpscaleService {
        val installedModels = object : InstalledModels {
            override fun installedIds(): Set<String> = installed

            override fun fileFor(model: UpscaleModel): File? =
                if (model.id in installed) modelFile else null
        }
        val engine = UpscaleEngine(
            modelsDir = root,
            surfaces = ArgbSurfaces,
            inferenceFactory = { _, scale, _ -> RecordingInference(scale) },
            threadPriority = 0,
        )
        return UpscaleService(
            cache = cache,
            models = installedModels,
            engine = engine,
            preferences = preferences,
            pageDecoder = { bytes ->
                val index = String(bytes).toInt()
                currentPage = index
                started += index
                decodeGate()
                DecodedPage(IntArray(64 * 64) { OPAQUE_GREY }, 64, 64)
            },
            tileSize = { 128 },
            encoder = PageEncoder { result: UpscaleResult, target: File ->
                encoded += currentPage
                target.writeText("encoded ${result.width}x${result.height}")
            },
        )
    }

    private fun page(index: Int, chapter: Long = 7): PageKey =
        PageKey(sourceId = 1, mangaId = 2, chapterId = chapter, pageIndex = index, sourceLength = 10L, sourceLastModified = 20L)

    private fun bytes(index: Int): () -> ByteArray = { index.toString().toByteArray() }

    private fun keyOf(index: Int, chapter: Long = 7): String = cache.key(
        sourceId = 1,
        mangaId = 2,
        chapterId = chapter,
        pageIndex = index,
        sourceLength = 10L,
        sourceLastModified = 20L,
        modelId = MODEL.id,
        scale = 2,
        signature = SIGNATURE,
    )

    private inner class RecordingInference(private val scale: Int) : TileInference {
        override val isFloat16: Boolean = false

        override fun infer(input: TileInput): FloatArray {
            inferredTiles.incrementAndGet()
            onInfer()
            val rgb = (input as TileInput.Float32).rgb
            val width = input.width
            val height = input.height
            val output = FloatArray(width * scale * height * scale * 3)
            for (y in 0 until height * scale) {
                for (x in 0 until width * scale) {
                    val from = ((y / scale) * width + x / scale) * 3
                    val to = (y * width * scale + x) * 3
                    output[to] = rgb[from]
                    output[to + 1] = rgb[from + 1]
                    output[to + 2] = rgb[from + 2]
                }
            }
            return output
        }
    }

    private companion object {
        const val OPAQUE_GREY = 0xFF808080.toInt()
        const val SIGNATURE = "v1;mode=AUTO;scale=2;bucket=1600;model=fake"

        val MODEL = UpscaleModel(
            id = "fake_2x",
            fileName = "fake_2x.onnx",
            family = ModelFamily.ILLUSTRATION_JANAI,
            scale = 2,
            bucketHeight = null,
            arch = ModelArch.ESRGAN,
            displayName = "Fake 2x",
            approxBytes = 1_000L,
        )
    }
}
