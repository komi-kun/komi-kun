// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The on-device engine benchmark (phase 3b).
//
// What it measures: the real `UpscaleEngine` + the real `OrtTileInference` session on the device,
// over published weights and real pages, so the shipped tile size, output cap and provider order are
// chosen from numbers instead of guesses. Everything here is the production path — `PageDecoder`
// decodes the pages, `BitmapSurfaces` allocates the output, `OrtTileInference` opens the session —
// the only test-side pieces are a `TileInference` wrapper that counts tiles (the engine offers no
// per-page tile count) and a per-run watchdog (a native inference call cannot be interrupted, so a
// run that overruns is abandoned rather than cancelled).
//
// The matrix is **pruned on the fly**, because it is far too large to run blind:
//   * a provider that cannot open a session for a model (or that silently falls back to another one)
//     is skipped for the rest of that model's configurations;
//   * a (model, provider) whose run exceeds [RUN_TIMEOUT_MS] is recorded and its remaining tile
//     sizes are skipped — a 256 px tile over a 33 MB ESRGAN on one core is hours, not a datapoint;
//   * a global deadline keeps the whole test bounded so it can run unattended.
//
// Assets are never part of the repo (CC BY-NC 4.0 weights, generated pages): they are pushed into the
// *test* package's external files dir before the run (see `BenchmarkAssets`), and a missing asset
// skips the test instead of failing it, so CI without the push stays green.
// KUN <--
package mihon.core.upscale.bench

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.core.upscale.engine.DecodedPage
import mihon.core.upscale.engine.ExecutionProvider
import mihon.core.upscale.engine.OrtTileInference
import mihon.core.upscale.engine.PageDecoder
import mihon.core.upscale.engine.TileInference
import mihon.core.upscale.engine.TileInput
import mihon.core.upscale.engine.UpscaleEngine
import mihon.core.upscale.model.UpscaleModel
import mihon.core.upscale.model.UpscaleModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class EngineBenchmarkTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = true }
    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val modelsDir: File get() = BenchmarkAssets.modelsDir(context)
    private val pagesDir: File get() = BenchmarkAssets.pagesDir(context)

    /** Every run this test produced, in order, for the JSON report. */
    private val runs = mutableListOf<RunRecord>()

    /** `"<modelId>|<provider>"` → why that provider is done for that model. */
    private val pruned = HashMap<String, String>()

    /** Why single runs failed, so a phase with no usable data says so in its assertion message. */
    private val notes = mutableListOf<String>()

    private val deadline: Long = System.currentTimeMillis() + MATRIX_DEADLINE_MS

    @Test
    fun theEngineRunsTheBenchmarkMatrixOnThisDevice() {
        // Create the (app-owned) directories *before* the skip check: a directory made by
        // `adb shell mkdir` is owned by shell with mode 0770, so this app cannot traverse it, and a
        // push into it lands where the app can never see it. Creating them here makes
        // `<externalFilesDir>/upscale-bench` the app's own, which `adb push` can still write to (the
        // group is `ext_data_rw`).
        modelsDir.mkdirs()
        pagesDir.mkdirs()
        val models = MODEL_IDS.map { id -> checkNotNull(UpscaleModelCatalog.find(id)) { "no $id" } }
        val missing = BenchmarkAssets.missing(context, models)
        benchLog(
            "UPSCALE_BENCH assets root=${BenchmarkAssets.root(context).absolutePath} " +
                "models=${modelsDir.listFiles().orEmpty().size} " +
                "pages=${pagesDir.listFiles().orEmpty().size} missing=${missing.size}",
        )
        assumeTrue(
            "upscale benchmark assets missing (${missing.joinToString { it.absolutePath }}): " +
                "adb push the weights and pages into ${BenchmarkAssets.root(context).absolutePath}",
            missing.isEmpty(),
        )

        val startedAt = System.currentTimeMillis()
        // Every page goes through the real decoder, and its geometry is asserted, not assumed.
        val pages = PAGE_NAMES.map { name -> decodePage(File(pagesDir, name)) }
        val webtoon = decodePage(ensureWebtoonPage())

        // The two validation runs come first: they are what proves the band assembly and the 4x
        // path, and the matrix below is the part that runs out of time on a slow device.
        // The band path: a page whose 2x output cannot fit the cap in one pass.
        crossCheck(
            phase = "webtoon-bands",
            page = webtoon,
            model = models.first { it.id == "mangajanai_2x_1600p" },
            tileSize = 512,
            maxOutputPixels = WEBTOON_MAX_OUTPUT_MP * 1_000_000L,
            minBands = 2,
        )
        // The 4x path, on a crop small enough to finish on a tablet.
        crossCheck(
            phase = "4x-crop",
            page = cropOf(pages.first(), CROP_WIDTH, CROP_HEIGHT),
            model = models.first { it.id == FOUR_X_MODEL_ID },
            tileSize = 384,
            maxOutputPixels = 32_000_000L,
            minBands = 1,
        )

        matrix(models.filter { it.id in MATRIX_MODEL_IDS }, pages)

        writeReport(startedAt)
    }

    // ------------------------------------------------------------------ the matrix

    /**
     * The page × tile × provider matrix, two runs each.
     *
     * Ordered model → page → tile → provider: the expensive dimension (a provider) is the innermost
     * one, so the numbers that decide a default — which provider and which tile size — are comparable
     * long before the deadline, instead of a whole provider's column arriving or nothing arriving.
     */
    private fun matrix(models: List<UpscaleModel>, pages: List<BenchPage>) {
        for (model in models) {
            for (page in pages) {
                for (tileSize in TILE_SIZES) {
                    for (provider in PROVIDERS) {
                        val key = keyOf(model, provider)
                        if (key in pruned) continue
                        if (deadlinePassed()) {
                            record(
                                endpoint(model, page, tileSize, provider, STATUS_SKIPPED)
                                    .copy(note = "matrix deadline reached"),
                            )
                            return
                        }
                        val measure = measure(
                            model,
                            page,
                            tileSize,
                            CAP_FOR_MATRIX_MP * 1_000_000L,
                            provider,
                        )
                        measure.records.forEach(::record)
                        when {
                            measure.timedOut -> {
                                benchLog("UPSCALE_BENCH timeout $key ${page.name} tile=$tileSize")
                                pruned[key] = "over ${RUN_TIMEOUT_MS / 1000} s"
                            }
                            measure.failure != null -> {
                                notes += "$key ${page.name} tile=$tileSize: ${measure.failure}"
                                benchLog("UPSCALE_BENCH failed $key ${page.name}: ${measure.failure}")
                                if (measure.sessionCreationFailed) pruned[key] = measure.failure
                            }
                            else -> {
                                // A run that silently fell back did not use the requested provider;
                                // repeating it for the other tile sizes would only repeat the
                                // fallback.
                                val fallback = measure.records.first().fallbackFrom
                                if (fallback != null) {
                                    pruned[key] = "fell back from $fallback to " +
                                        measure.records.first().chosenProvider
                                    benchLog("UPSCALE_BENCH pruned $key: ${pruned[key]}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A second benchmark kind (banding, 4x): cold and warm, on the first provider that can serve
     * [model] at all, with the assertions that make the run a *test* rather than a printout.
     */
    private fun crossCheck(
        phase: String,
        page: BenchPage,
        model: UpscaleModel,
        tileSize: Int,
        maxOutputPixels: Long,
        minBands: Int,
    ) {
        for (provider in PROVIDERS) {
            val key = keyOf(model, provider)
            if (key in pruned) continue
            val measure = measure(model, page, tileSize, maxOutputPixels, provider, phase)
            measure.records.forEach(::record)
            when {
                measure.timedOut -> {
                    pruned[key] = "over ${RUN_TIMEOUT_MS / 1000} s"
                }
                measure.failure != null -> {
                    notes += "$key ${page.name} $phase: ${measure.failure}"
                    if (measure.sessionCreationFailed) pruned[key] = measure.failure
                }
                else -> {
                    val cold = measure.records.first()
                    assertEquals(
                        "$phase width (${model.id} on $provider)",
                        page.decoded.width * model.scale,
                        cold.outputWidth,
                    )
                    assertEquals(
                        "$phase height (${model.id} on $provider)",
                        page.decoded.height * model.scale,
                        cold.outputHeight,
                    )
                    assertTrue(
                        "$phase should have split into at least $minBands band(s), " +
                            "got ${cold.bands} (cap ${maxOutputPixels / 1_000_000} MP)",
                        cold.bands >= minBands,
                    )
                    assertTrue("$phase sampled only zero pixels", cold.samples.any { it != 0 })
                    return
                }
            }
        }
        throw AssertionError(
            "$phase: no provider could run ${model.id} over ${page.name} " +
                "(pruned: $pruned; failures: ${notes.distinct().takeLast(6)})",
        )
    }

    // ------------------------------------------------------------------ one measurement

    /**
     * Runs [model] over [page] **twice** on one engine: the first run opens the ONNX session (cold),
     * the second reuses it (warm). Both are recorded, so a page's real cost is the warm number plus
     * the one-off session the reader pays once per page shape.
     */
    private fun measure(
        model: UpscaleModel,
        page: BenchPage,
        tileSize: Int,
        maxOutputPixels: Long,
        provider: ExecutionProvider,
        phase: String = PHASE_MATRIX,
    ): Measure {
        val probe = Probe()
        val engine = UpscaleEngine(
            modelsDir = modelsDir,
            inferenceFactory = { file, scale, _ ->
                val opened = OrtTileInference.open(file, scale, preferred = provider)
                probe.opened = opened
                CountingInference(opened) { probe.tiles.incrementAndGet() }
            },
        )
        try {
            val cold = runOnce(phase, page, model, tileSize, maxOutputPixels, provider, PASS_COLD, probe, engine)
            when (cold) {
                is Attempt.TimedOut -> return Measure(
                    records = listOf(cold.record),
                    failure = cold.record.note,
                    timedOut = true,
                )
                is Attempt.Failed -> return Measure(
                    records = listOf(cold.record),
                    failure = cold.reason,
                    sessionCreationFailed = cold.sessionCreationFailed,
                )
                is Attempt.Ok -> {
                    val warm = runOnce(
                        phase, page, model, tileSize, maxOutputPixels, provider, PASS_WARM, probe, engine,
                    )
                    return when (warm) {
                        is Attempt.TimedOut -> Measure(
                            records = listOf(cold.record, warm.record),
                            failure = warm.record.note,
                            timedOut = true,
                        )
                        is Attempt.Ok -> Measure(listOf(cold.record, warm.record))
                        is Attempt.Failed -> Measure(
                            records = listOf(cold.record, warm.record),
                            failure = warm.reason,
                            sessionCreationFailed = warm.sessionCreationFailed,
                        )
                    }
                }
            }
        } finally {
            // A timed-out run still owns its session: closing under it would be a use-after-free.
            if (!probe.abandoned) engine.close()
        }
    }

    private fun runOnce(
        phase: String,
        page: BenchPage,
        model: UpscaleModel,
        tileSize: Int,
        maxOutputPixels: Long,
        provider: ExecutionProvider,
        pass: String,
        probe: Probe,
        engine: UpscaleEngine,
    ): Attempt {
        probe.tiles.set(0)
        val holder = AtomicReference<Attempt?>(null)
        val worker = Thread(
            {
                val start = System.nanoTime()
                holder.set(
                    try {
                        val result = engine.upscale(
                            page = page.decoded,
                            model = model,
                            tileSize = tileSize,
                            maxOutputPixels = maxOutputPixels,
                        )
                        val nativeHeap = Debug.getNativeHeapAllocatedSize()
                        val runtime = Runtime.getRuntime()
                        val javaHeap = runtime.totalMemory() - runtime.freeMemory()
                        val memory = memoryInfo()
                        val samples = result.bitmap?.let { bitmap ->
                            listOf(
                                bitmap.getPixel(bitmap.width / 2, bitmap.height / 4),
                                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
                                bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4),
                            )
                        }.orEmpty()
                        val record = RunRecord(
                            phase = phase,
                            model = model.id,
                            page = page.name,
                            pageWidth = page.decoded.width,
                            pageHeight = page.decoded.height,
                            requestedTile = tileSize,
                            usedTile = result.tileSize,
                            requestedProvider = provider.name,
                            chosenProvider = probe.opened?.provider?.name ?: "none",
                            fallbackFrom = provider.name.takeIf { probe.opened?.provider != provider },
                            pass = pass,
                            status = STATUS_OK,
                            millis = (System.nanoTime() - start) / 1_000_000,
                            tiles = probe.tiles.get(),
                            bands = result.bandCount,
                            outputWidth = result.width,
                            outputHeight = result.height,
                            nativeHeapBytes = nativeHeap,
                            javaHeapBytes = javaHeap,
                            memAvailBytes = memory.availMem,
                            memTotalBytes = memory.totalMem,
                            memLow = memory.lowMemory,
                            samples = samples,
                        )
                        // The output is native memory the GC cannot see: never hold two of them.
                        result.bitmap?.recycle()
                        Attempt.Ok(record)
                    } catch (error: Throwable) {
                        Attempt.Failed(
                            record = endpoint(model, page, tileSize, provider, STATUS_FAILED, phase, pass)
                                .copy(
                                    millis = (System.nanoTime() - start) / 1_000_000,
                                    note = error.describe(),
                                ),
                            reason = error.describe(),
                            sessionCreationFailed = error.isSessionCreationFailure(),
                        )
                    },
                )
            },
            "upscale-bench-${model.id}-$pass",
        ).apply { isDaemon = true }
        worker.start()
        worker.join(RUN_TIMEOUT_MS)
        if (worker.isAlive) {
            probe.abandoned = true
            return Attempt.TimedOut(
                endpoint(model, page, tileSize, provider, STATUS_TIMEOUT, phase, pass)
                    .copy(millis = RUN_TIMEOUT_MS, note = "over ${RUN_TIMEOUT_MS / 1000} s"),
            )
        }
        return checkNotNull(holder.get()) { "the benchmark thread produced no outcome" }
    }

    // ------------------------------------------------------------------ pages

    private fun decodePage(file: File): BenchPage {
        assumeTrue("benchmark page missing: ${file.absolutePath}", file.isFile && file.length() > 0L)
        val page = checkNotNull(PageDecoder.decode(file.readBytes())) {
            "PageDecoder refused ${file.name}"
        }
        PAGE_DIMENSIONS.find(file.nameWithoutExtension)?.let { match ->
            val (width, height) = match.destructured
            assertEquals("decoded width of ${file.name}", width.toInt(), page.width)
            assertEquals("decoded height of ${file.name}", height.toInt(), page.height)
        }
        assertTrue("${file.name} decoded to no pixels", page.argb.isNotEmpty())
        assertEquals("${file.name} pixel count", page.width * page.height, page.argb.size)
        // The decoder's contract: a page is opaque art, so nothing composites onto black.
        assertEquals("${file.name} alpha", 0xFF, page.argb[0] ushr 24)
        return BenchPage(file.name, DecodedPage(page.argb, page.width, page.height))
    }

    /**
     * The tall webtoon page, generated on device when it was not pushed: the band path needs a page
     * whose 2x output exceeds the cap, and a synthetic one is enough to prove the assembly.
     */
    private fun ensureWebtoonPage(): File {
        val file = File(pagesDir, WEBTOON_PAGE)
        if (file.isFile && file.length() > 0L) return file
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(WEBTOON_WIDTH, WEBTOON_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val frame = Paint().apply {
                color = Color.BLACK
                strokeWidth = 6f
                style = Paint.Style.STROKE
            }
            val hatch = Paint().apply {
                color = Color.rgb(40, 40, 40)
                strokeWidth = 3f
            }
            var top = 24f
            var panel = 0
            while (top < WEBTOON_HEIGHT - 100) {
                val bottom = min(WEBTOON_HEIGHT - 24f, top + if (panel % 3 == 0) 900f else 620f)
                canvas.drawRect(24f, top, WEBTOON_WIDTH - 24f, bottom, frame)
                var x = 40f
                while (x < WEBTOON_WIDTH - 40f) {
                    canvas.drawLine(x, top + 20f, x, bottom - 20f, hatch)
                    x += 12f
                }
                top = bottom + 40f
                panel++
            }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        benchLog("UPSCALE_BENCH generated ${file.absolutePath} (${file.length()} B)")
        return file
    }

    /** The top-left [width]x[height] window of [page], for a 4x run that has to finish today. */
    private fun cropOf(page: BenchPage, width: Int, height: Int): BenchPage {
        val w = min(width, page.decoded.width)
        val h = min(height, page.decoded.height)
        val pixels = IntArray(w * h)
        for (row in 0 until h) {
            page.decoded.argb.copyInto(
                pixels,
                destinationOffset = row * w,
                startIndex = row * page.decoded.width,
                endIndex = row * page.decoded.width + w,
            )
        }
        return BenchPage("${page.name}+crop-${w}x$h", DecodedPage(pixels, w, h))
    }

    // ------------------------------------------------------------------ report

    /** One line per run, plus the bookkeeping, in logcat under a tag `adb logcat -s` can select. */
    private fun benchLog(message: String) {
        Log.i(TAG, message)
    }

    private fun record(run: RunRecord) {
        runs += run
        benchLog("UPSCALE_BENCH ${json.encodeToString(RunRecord.serializer(), run)}")
    }

    private fun writeReport(startedAt: Long) {
        val report = BenchReport(
            device = DeviceInfo(
                model = Build.MODEL,
                device = Build.DEVICE,
                sdk = Build.VERSION.SDK_INT,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                heapGrowthLimit = System.getProperty("dalvik.vm.heapgrowthlimit").orEmpty(),
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                pruned = pruned.toMap(),
            ),
            runs = runs,
            failures = notes,
        )
        val file = File(BenchmarkAssets.root(context), RESULTS_FILE)
        file.parentFile?.mkdirs()
        file.writeText(prettyJson.encodeToString(BenchReport.serializer(), report))
        benchLog("UPSCALE_BENCH wrote ${file.absolutePath} (${runs.size} run(s))")
        assertTrue("the benchmark produced no run at all", runs.isNotEmpty())
    }

    private fun endpoint(
        model: UpscaleModel,
        page: BenchPage,
        tileSize: Int,
        provider: ExecutionProvider,
        status: String,
        phase: String = PHASE_MATRIX,
        pass: String = PASS_COLD,
    ): RunRecord {
        val memory = memoryInfo()
        return RunRecord(
            phase = phase,
            model = model.id,
            page = page.name,
            pageWidth = page.decoded.width,
            pageHeight = page.decoded.height,
            requestedTile = tileSize,
            usedTile = tileSize,
            requestedProvider = provider.name,
            chosenProvider = "none",
            pass = pass,
            status = status,
            memAvailBytes = memory.availMem,
            memTotalBytes = memory.totalMem,
            memLow = memory.lowMemory,
        )
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return info
    }

    private fun deadlinePassed(): Boolean = System.currentTimeMillis() > deadline

    private fun keyOf(model: UpscaleModel, provider: ExecutionProvider): String =
        "${model.id}|${provider.name}"

    // ------------------------------------------------------------------ types

    private class BenchPage(val name: String, val decoded: DecodedPage)

    /** What one engine holds between the cold and the warm run. */
    private class Probe {
        var opened: OrtTileInference? = null
        val tiles = AtomicInteger(0)

        /** Set when a run overran: the session is still running and must not be closed under it. */
        var abandoned: Boolean = false
    }

    /** Both runs of one configuration (or the one that failed). */
    private class Measure(
        val records: List<RunRecord>,
        val failure: String? = null,
        val sessionCreationFailed: Boolean = false,
        val timedOut: Boolean = false,
    )

    private sealed interface Attempt {
        class Ok(val record: RunRecord) : Attempt

        class Failed(
            val record: RunRecord,
            val reason: String,
            val sessionCreationFailed: Boolean,
        ) : Attempt

        class TimedOut(val record: RunRecord) : Attempt
    }

    /** Delegates to the real session and counts the tiles the engine fed it. */
    private class CountingInference(
        private val delegate: TileInference,
        private val onTile: () -> Unit,
    ) : TileInference {
        override val isFloat16: Boolean get() = delegate.isFloat16

        override fun infer(input: TileInput): FloatArray {
            onTile()
            return delegate.infer(input)
        }

        override fun close() = delegate.close()
    }

    @Serializable
    private data class RunRecord(
        val phase: String,
        val model: String,
        val page: String,
        val pageWidth: Int,
        val pageHeight: Int,
        val requestedTile: Int,
        val usedTile: Int,
        val requestedProvider: String,
        val chosenProvider: String,
        val fallbackFrom: String? = null,
        val pass: String,
        val status: String,
        val millis: Long = 0L,
        val tiles: Int = 0,
        val bands: Int = 0,
        val outputWidth: Int = 0,
        val outputHeight: Int = 0,
        val nativeHeapBytes: Long = 0L,
        val javaHeapBytes: Long = 0L,
        val memAvailBytes: Long = 0L,
        val memTotalBytes: Long = 0L,
        val memLow: Boolean = false,
        val samples: List<Int> = emptyList(),
        val note: String? = null,
    )

    @Serializable
    private data class DeviceInfo(
        val model: String,
        val device: String,
        val sdk: Int,
        val abi: String,
        val heapGrowthLimit: String,
        val startedAt: Long,
        val finishedAt: Long,
        val pruned: Map<String, String>,
    )

    @Serializable
    private data class BenchReport(
        val device: DeviceInfo,
        val runs: List<RunRecord>,
        val failures: List<String>,
    )

    private companion object {
        /** One run (a whole page) is abandoned past this: a native call cannot be interrupted. */
        const val RUN_TIMEOUT_MS = 180_000L

        /** Stops the whole matrix so the test can run unattended. */
        const val MATRIX_DEADLINE_MS = 85L * 60L * 1000L

        /**
         * The matrix's band cap, in megapixels — wide enough that the matrix pages never split.
         * `UpscaleEngine.upscale` takes **pixels**, which is why every call site multiplies.
         */
        const val CAP_FOR_MATRIX_MP = 32L

        const val PHASE_MATRIX = "matrix"
        const val PASS_COLD = "cold"
        const val PASS_WARM = "warm"
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_SKIPPED = "skipped"

        val PROVIDERS = listOf(
            ExecutionProvider.WEBGPU,
            ExecutionProvider.XNNPACK,
            ExecutionProvider.CPU,
        )

        val TILE_SIZES = listOf(512, 384, 256)

        /**
         * The matrix models, **default model first**. The matrix costs about a minute per run on this
         * device, so the order decides what the deadline can still reach: the model the app ships
         * with is measured before the ones that only exist to compare against it.
         */
        val MATRIX_MODEL_IDS = listOf(
            "illustrationjanai_2x_v3detail_fdat_m",
            "illustrationjanai_2x_v3detail_span_s",
            "mangajanai_2x_1600p",
        )

        /** The 4x model the 4x crop runs with (the V3 detail one, the owner's preferred family). */
        const val FOUR_X_MODEL_ID = "illustrationjanai_4x_v3detail_fdat_m"

        /** Every model a run needs on the device: the matrix's three plus the 4x crop's. */
        val MODEL_IDS = MATRIX_MODEL_IDS + FOUR_X_MODEL_ID

        val PAGE_NAMES = listOf("page-manga-1200x1600.png", "page-colour-1125x1600.png")

        val PAGE_DIMENSIONS = Regex(".*-(\\d+)x(\\d+)$")

        const val WEBTOON_PAGE = "page-webtoon-800x5685.png"
        const val WEBTOON_WIDTH = 800
        const val WEBTOON_HEIGHT = 5685
        const val WEBTOON_MAX_OUTPUT_MP = 8L

        const val CROP_WIDTH = 400
        const val CROP_HEIGHT = 600

        const val RESULTS_FILE = "engine-benchmark.json"

        const val TAG = "UPSCALE_BENCH"
    }
}

/** True when the session could not be opened at all (that provider is finished for the model). */
private fun Throwable.isSessionCreationFailure(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 4) {
        if (current is IllegalStateException && "could not open" in current.message.orEmpty()) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

private fun Throwable.describe(): String = "${javaClass.simpleName}: ${message.orEmpty().take(240)}"
