// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `chimahon-local-ocr/src/main/java/chimahon/local/ocr/PaddleOcrEngine.kt` @ f85c063.
// Adapted for komi-kun: every published ABI (including `x86_64`) is now genuinely supported (see
// supportedAbi()), logging uses the project's `logcat`, and the native-library load path also falls
// back to the APK-packaged (`jniLibs`) layout, not just the downloaded absolute path.
//
// ---------------------------------------------------------------------------------------------
// INTEGRATION POINT — `libpaddle_ocr.so` *and* `libonnxruntime.so` are SEPARATE artifacts
// (contract §1.7).
// This class declares the JNI surface (`nativeInit`/`nativeProcess`/`nativeRelease`, plus the
// forward-compatible `nativeGetOrtError` diagnostics accessor — a stub until a shim release exports
// it) and the Kotlin overload that consumes it. The `.so` is our own JNI wrapper over ONNX Runtime (MIT, loaded with
// `dlopen`), published to `komi-kun/komi-kun-ocr-models` alongside `libonnxruntime.so`,
// `libomp.so`/`libc++_shared.so` and fetched
// by `:core:ocr:fetchPaddleNativeLibs` from `core/ocr/paddle-manifest.json`. Nothing in this module
// builds ONNX Runtime. Until the artifact lands, `init()` simply returns false and `recognize()`
// yields an empty list — the engine degrades gracefully.
//
// The native side MUST emit the text protocol parsed by [parseOutput]:
//   TIMING|total|prep|det|post|crop|rec|rawBoxCount
//   LINE|order|score|bboxL,bboxT,bboxR,bboxB|pts...|isVertical(0/1)|isFurigana(0/1)|text
// (Normalized bbox coords; the quad `pts` are tl,tr,br,bl and drive the skew angle.)
// ---------------------------------------------------------------------------------------------
// KUN <--
package mihon.core.ocr.paddle

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import logcat.LogPriority
import mihon.core.ocr.EngineLine
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrBitmapDecoder
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.OcrPreprocessor
import mihon.core.ocr.WritingDirection
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

// Detection/recognition thresholds copied from the ocrtest "Full Page Preset"
// (full-page manga mode). Bubble preset (0.20/0.45/1.60) is intentionally not
// used: page OCR always runs full-page, and reading-order/vertical/furigana
// postprocessing already happens inside the native engine.
data class PaddleOcrOptions(
    val thresh: Float = 0.15f,
    val boxThresh: Float = 0.25f,
    val unclipRatio: Float = 1.40f,
    val filterFurigana: Boolean = false,
    val numThreads: Int = 4,
    // KUN -->
    /** Lines whose recognition score is below this are dropped. 0 disables the floor. */
    val recConfidenceFloor: Float = 0f,
    // KUN <--
)

data class PaddleOcrResult(
    val lines: List<EngineLine>,
    val totalMs: Long,
    val prepMs: Long,
    val detMs: Long,
    val postMs: Long,
    val cropMs: Long,
    val recMs: Long,
    val rawBoxCount: Int,
    val furiganaCount: Int,
) {
    val benchmark: String
        get() = "Total: ${totalMs}ms (Prep: ${prepMs}ms, Det: ${detMs}ms, Post: ${postMs}ms, Crop: ${cropMs}ms, Rec: ${recMs}ms) | Boxes: $rawBoxCount, Lines: ${lines.size}"
}

class PaddleOcrEngine(
    context: Context,
    // KUN -->
    // User-tunable detector/recogniser thresholds (OcrPreferences.detectionOptions), read per page.
    private val preferences: OcrPreferences,
    // KUN <--
) : OcrEngine, Closeable {
    private val appContext = context.applicationContext

    // The JNI layer owns a single global engine instance, so all locking is
    // companion-wide (never per-instance): two instances must still exclude
    // each other. Scheme, mirroring ocrtest's MangaOcrEngine intent
    // (@Synchronized is illegal on suspend functions, hence explicit locks):
    // - [lock]: held only for init/destroy/close. Brief, never waits on permits.
    // - [inferPermits]: bounds concurrent native inferences. Inference never
    //   takes [lock] except via init(), which never waits on permits.
    // That ordering (permits -> lock, never lock -> permits) cannot deadlock,
    // and init can never free g_engine mid-inference (the SIGSEGV we hit).
    @Volatile
    private var isInitialized = false

    /**
     * Identity of the model files the native engine currently holds (see
     * [installedModelIdentity]); used to detect a variant switch **and** a file replacement.
     *
     * It keys on what is actually installed — the downloader's marker plus the model files' own
     * fingerprint — never on the selection ([OcrPreferences.resolveModelRelease]). The detector file
     * name is the *same* in every release, so keying on the selection meant: an upgrading install
     * loaded the SFX weights still on disk and recorded the newly selected dialogue release, and after
     * the download that fixed the file the early-return comparison still matched, so the wrong
     * detector stayed in native memory until process death.
     */
    @Volatile
    private var loadedModelKey: String? = null

    companion object {
        private const val MODEL_DIR = "paddle_ocr"
        // PP-OCRv6 small, run through ONNX Runtime. Still CTC with rec_image_height = 48, so the
        // JNI input shape and the dict convention are unchanged. The detector and recogniser file
        // names both come from `OcrPreferences` (`detModelFile()` / `recModelFile()`): every release
        // publishes the same two names, so a constant here could silently diverge from what the
        // downloader installs.
        private const val DICT_TXT = "ppocrv6_small_dict.txt"

        /**
         * ABIs the Kotlin engine will *attempt*. `komi-kun-ocr-models` ships all three
         * (`ocr-models-v3`, ONNX Runtime + architecture-independent ONNX models); an ABI that is
         * somehow absent still degrades gracefully (see [supportedAbi]).
         */
        private val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

        /**
         * Max concurrent native inferences, scaled by CPU count (not RAM: no
         * device-specific memory paths). Each job already fans out to
         * [deviceThreads] native threads, so one job saturates a small
         * device; big devices get a second slot to overlap memory stalls.
         */
        private val INFER_PERMITS: Int =
            if (Runtime.getRuntime().availableProcessors() >= 8) 2 else 1

        private val lock = Any()
        private val inferPermits = Semaphore(INFER_PERMITS)
    }

    /** Big-core count (ported from ocrtest), computed once per process. */
    private val deviceThreads: Int by lazy { detectPerformanceCores().coerceIn(1, 4) }

    /**
     * First supported ABI of the device, or null.
     *
     * `ocr-models-v3` ships `arm64-v8a`, `armeabi-v7a` **and `x86_64`**: ONNX Runtime publishes all
     * three, and the ONNX models are architecture-independent, so x86_64 emulators are supported
     * rather than merely degrading. The `UnsatisfiedLinkError` catch below is kept as a safety net
     * for a device whose ABI has no published artifact.
     */
    private fun supportedAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }

    fun init() = init(PaddleOcrOptions(numThreads = deviceThreads))

    fun init(options: PaddleOcrOptions): Boolean = synchronized(lock) {
        if (isInitialized) return true

        // Both names come from the single accessors (shared by the downloader and the settings).
        val detModel = preferences.detModelFile()
        val recModel = preferences.recModelFile()

        val abi = supportedAbi()
        if (abi == null) {
            logcat(LogPriority.ERROR) { "Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}" }
            return false
        }
        if (!loadNativeLibraries(abi)) return false

        val start = SystemClock.elapsedRealtime()
        val modelDir = File(appContext.filesDir, MODEL_DIR)
        // What is on disk *now* — read immediately before the load, so the recorded key always names the
        // file set the load was started with. A download that replaces those bytes in between changes
        // the *next* comparison, so the engine reloads there: a stale key can never outlive one check,
        // and it can never be derived from the selection (which says nothing about the bytes).
        val installed = installedModelIdentity(modelDir, listOf(detModel, recModel))
        val success = nativeInit(
            detParamAbsPath = File(modelDir, detModel).absolutePath,
            detBinAbsPath = "",
            recParamAbsPath = File(modelDir, recModel).absolutePath,
            recBinAbsPath = "",
            dictAbsPath = File(modelDir, DICT_TXT).absolutePath,
            numThreads = options.numThreads,
        )
        if (success) {
            isInitialized = true
            loadedModelKey = installed
            val elapsed = SystemClock.elapsedRealtime() - start
            logcat(LogPriority.INFO) { "Paddle OCR engine initialized in ${elapsed}ms ($installed)" }
        } else {
            logcat(LogPriority.ERROR) {
                "Failed to initialize Paddle OCR engine from ${modelDir.absolutePath}" +
                    " ($installed, native error: ${nativeOrtError()})"
            }
        }
        return success
    }

    // KUN -->
    /**
     * Last native init failure reason (e.g. a missing/mismatched `libonnxruntime.so`, or a failed
     * det/rec session), or a short placeholder when the shim does not expose it.
     *
     * **Not active yet.** The pinned `ocr-models-v3` shim exports only
     * `nativeInit`/`nativeProcess`/`nativeRelease`, so [nativeGetOrtError] always throws
     * [UnsatisfiedLinkError] and this returns its fallback. The shim's own `nativeInit` already logs
     * the real reason to logcat (`runtime=…`, e.g. `libonnxruntime.so not found`), which is the
     * source of truth until a shim release adds the getter (see `ort-v6-migration.md` §6).
     */
    private fun nativeOrtError(): String = try {
        nativeGetOrtError().ifBlank { "not reported by the native engine" }
    } catch (_: UnsatisfiedLinkError) {
        "unavailable; see the nativeInit logcat line"
    }
    // KUN <--

    // KUN -->
    /**
     * Loads the native libraries. The **live** path today is the APK-packaged `jniLibs` layout: the
     * fetched artifact ships the `.so`s in the APK, so `loadLibrary` resolves `libpaddle_ocr.so` and
     * the shim's own `dlopen` of `libonnxruntime.so` resolves from the same APK lib directory.
     *
     * It first tries a **forward-compatible** app-storage layout
     * (`filesDir/paddle_ocr/lib/<abi>/`, STL + OpenMP + ONNX Runtime before the engine — dependency
     * order matters) for a future release that ships the runtime there. Nothing writes `.so`s into
     * that directory today (only models are downloaded at runtime), so this branch is currently
     * **unpopulated** and never wins; it is kept so the fallback already exists when such a release
     * lands, and a present-but-unloadable app-storage copy still falls back to `jniLibs`.
     */
    // KUN <--
    private fun loadNativeLibraries(abi: String): Boolean {
        val libDir = File(File(appContext.filesDir, MODEL_DIR), "lib/$abi")
        val engine = File(libDir, "libpaddle_ocr.so")

        if (engine.isFile && loadFromAppStorage(libDir, engine)) return true
        return loadFromJniLibs()
    }

    /** App-storage layout: absolute-path `System.load`s in dependency order. */
    private fun loadFromAppStorage(libDir: File, engine: File): Boolean = try {
        System.load(File(libDir, "libc++_shared.so").absolutePath)
        System.load(File(libDir, "libomp.so").absolutePath)
        System.load(File(libDir, "libonnxruntime.so").absolutePath)
        System.load(engine.absolutePath)
        logcat(LogPriority.INFO) { "libpaddle_ocr.so loaded from app storage" }
        true
    } catch (e: UnsatisfiedLinkError) {
        logcat(LogPriority.ERROR, e) { "Failed to load libpaddle_ocr.so from $libDir; falling back to jniLibs" }
        false
    }

    /** APK-packaged layout: the fetched artifact lives in `jniLibs`, so `loadLibrary` finds it. */
    private fun loadFromJniLibs(): Boolean = try {
        System.loadLibrary("paddle_ocr")
        logcat(LogPriority.INFO) { "libpaddle_ocr.so loaded from jniLibs" }
        true
    } catch (e: UnsatisfiedLinkError) {
        logcat(LogPriority.ERROR, e) { "libpaddle_ocr.so missing (download the OCR models)" }
        false
    }

    fun isInitialized(): Boolean = isInitialized

    /**
     * Swaps weights when the model files under the engine directory changed — a variant switch the
     * downloader has installed, or a hand-staged replacement.
     *
     * Compares the identity of *what would be loaded now* (marker + file fingerprint) with the one
     * recorded by the last [init], so a selected-but-not-yet-downloaded variant does not churn the
     * engine and a replaced file can never keep the old weights alive.
     *
     * Must run *before* taking an [inferPermits] permit: [destroy] drains every permit, so calling
     * it from inside an inference would deadlock. The next [init] then loads the files.
     */
    private fun ensureModelVariant() {
        if (!isInitialized) return
        val installed = installedModelIdentity(
            File(appContext.filesDir, MODEL_DIR),
            listOf(preferences.detModelFile(), preferences.recModelFile()),
        )
        if (!shouldReloadModels(loadedModelKey, installed)) return
        logcat(LogPriority.INFO) { "Paddle OCR engine: model files changed ($loadedModelKey -> $installed)" }
        destroy()
    }

    override suspend fun recognize(
        bytes: ByteArray,
        language: OcrLanguage,
    ): List<EngineLine> {
        // Apply a model-variant switch while no permit is held (destroy drains them).
        ensureModelVariant()
        // Bounded parallelism: suspends (never blocks a thread) until a
        // native slot frees up.
        inferPermits.acquire()
        try {
            if (!isInitialized) {
                init()
            }
            if (!isInitialized) return emptyList()
            val bitmap = try {
                // KUN --> Sampled decode: the page is shrunk to the OCR budget while decoding.
                OcrBitmapDecoder.decode(bytes)
                // KUN <--
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to decode image for paddle OCR" }
                return emptyList()
            }
            return try {
                recognizeLocked(bitmap, preferences.detectionOptions(deviceThreads), language).lines
            } finally {
                bitmap.recycle()
            }
        } finally {
            inferPermits.release()
        }
    }

    // KUN -->
    override suspend fun recognize(
        bitmap: Bitmap,
        language: OcrLanguage,
    ): List<EngineLine> {
        ensureModelVariant()
        inferPermits.acquire()
        try {
            if (!isInitialized) {
                init()
            }
            if (!isInitialized) return emptyList()
            return recognizeLocked(bitmap, preferences.detectionOptions(deviceThreads), language).lines
        } finally {
            inferPermits.release()
        }
    }
    // KUN <--

    /**
     * Bitmap inference. Must only be called while holding an [inferPermits]
     * permit (see [recognize]); takes [lock] briefly for lazy init, which
     * never waits on permits, so no deadlock is possible.
     */
    private fun recognizeLocked(
        bitmap: Bitmap,
        options: PaddleOcrOptions,
        language: OcrLanguage,
    ): PaddleOcrResult {
        if (!isInitialized) {
            init(options)
        }
        if (!isInitialized) {
            return PaddleOcrResult(emptyList(), 0, 0, 0, 0, 0, 0, 0, 0)
        }

        val software = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return PaddleOcrResult(emptyList(), 0, 0, 0, 0, 0, 0, 0, 0)
        }
        try {
            val raw = nativeProcess(
                bitmap = software,
                thresh = options.thresh,
                boxThresh = options.boxThresh,
                unclipRatio = options.unclipRatio,
                filterFurigana = options.filterFurigana,
                numThreads = options.numThreads,
            )
            val result = parseOutput(raw, language, software.width, software.height, options.recConfidenceFloor)
            logcat(LogPriority.INFO) { "recognize: ${result.benchmark}" }
            return result
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }

    private fun parseOutput(
        raw: String,
        language: OcrLanguage,
        imgW: Int,
        imgH: Int,
        // KUN --> Drop lines whose recognition confidence is below this (0 = keep every line).
        minRecScore: Float = 0f,
        // KUN <--
    ): PaddleOcrResult {
        if (raw.isBlank()) return PaddleOcrResult(emptyList(), 0, 0, 0, 0, 0, 0, 0, 0)

        var totalMs = 0L
        var prepMs = 0L
        var detMs = 0L
        var postMs = 0L
        var cropMs = 0L
        var recMs = 0L
        var rawBoxCount = 0
        var furiganaCount = 0
        val lines = mutableListOf<EngineLine>()

        val rawLines = raw.split("\n")
        for (lineStr in rawLines) {
            if (lineStr.isBlank()) continue
            if (lineStr.startsWith("TIMING|")) {
                val parts = lineStr.split("|")
                if (parts.size >= 8) {
                    totalMs = parts[1].toLongOrNull() ?: 0L
                    prepMs = parts[2].toLongOrNull() ?: 0L
                    detMs = parts[3].toLongOrNull() ?: 0L
                    postMs = parts[4].toLongOrNull() ?: 0L
                    cropMs = parts[5].toLongOrNull() ?: 0L
                    recMs = parts[6].toLongOrNull() ?: 0L
                    rawBoxCount = parts[7].toIntOrNull() ?: 0
                }
            } else if (lineStr.startsWith("LINE|")) {
                val parts = lineStr.split("|", limit = 8)
                if (parts.size >= 8) {
                    // KUN -->
                    // parts[2] is the per-line recognition confidence. The native engine always
                    // emits it; the user-configurable floor drops low-confidence lines here, in
                    // Kotlin, without a native change.
                    val recScore = parts[2].toFloatOrNull() ?: 0f
                    // KUN <--
                    val bboxCoords = parts[3].split(",")
                    val ptsCoords = parts[4].split(",")
                    val isVertical = (parts[5] == "1")
                    // NOTE: parts[6] (native is_furigana flag) is intentionally
                    // ignored. Ruby filtering lives solely in the merger so all
                    // engines behave the same; filterFurigana stays off.
                    val isFurigana = (parts[6] == "1")
                    val text = parts[7]

                    if (isFurigana) furiganaCount++

                    if (text.isBlank()) continue
                    // KUN -->
                    // Recognition confidence floor. Protect legitimate multi-character Japanese
                    // dialogue lines (>= 3 kana/kanji and not noise) by capping effective floor at 0.35.
                    val effectiveFloor = if (minRecScore > 0f && countJpChars(text) >= 3 && !OcrPreprocessor.isImplausibleJapaneseNoise(text)) {
                        minOf(minRecScore, 0.35f)
                    } else {
                        minRecScore
                    }
                    if (effectiveFloor > 0f && recScore < effectiveFloor) continue
                    // KUN <--

                    if (bboxCoords.size == 4) {
                        val left = bboxCoords[0].toDoubleOrNull() ?: continue
                        val top = bboxCoords[1].toDoubleOrNull() ?: continue
                        val right = bboxCoords[2].toDoubleOrNull() ?: continue
                        val bottom = bboxCoords[3].toDoubleOrNull() ?: continue

                        lines.add(
                            EngineLine(
                                text = text,
                                bbox = NormalizedBBox(
                                    left = left,
                                    top = top,
                                    right = right,
                                    bottom = bottom,
                                    rotation = quadRotation(ptsCoords, imgW, imgH),
                                ),
                                writingDirection = if (isVertical) WritingDirection.TTB else WritingDirection.LTR,
                                language = language,
                            ),
                        )
                    }
                }
            }
        }

        return PaddleOcrResult(
            lines = lines,
            totalMs = totalMs,
            prepMs = prepMs,
            detMs = detMs,
            postMs = postMs,
            cropMs = cropMs,
            recMs = recMs,
            rawBoxCount = rawBoxCount,
            furiganaCount = furiganaCount,
        )
    }

    /**
     * Skew angle (radians) of the box top edge, or 0 when the quad is missing
     * or near-axis-aligned. Native emits corners ordered tl,tr,br,bl, so
     * pts[0]->pts[1] is the top edge. Folded into [-PI/4, PI/4] with a
     * deadband: the merger refuses grouping across ~0.1 rad rotation gaps,
     * so tiny angles snap to 0 and only genuinely skewed boxes carry
     * rotation (overlay painter and hit-tester consume it).
     */
    private fun quadRotation(ptsCoords: List<String>, imgW: Int, imgH: Int): Double {
        if (ptsCoords.size != 8 || imgW <= 0 || imgH <= 0) return 0.0
        val x1 = ptsCoords[0].toDoubleOrNull() ?: return 0.0
        val y1 = ptsCoords[1].toDoubleOrNull() ?: return 0.0
        val x2 = ptsCoords[2].toDoubleOrNull() ?: return 0.0
        val y2 = ptsCoords[3].toDoubleOrNull() ?: return 0.0
        val dx = (x2 - x1) * imgW
        val dy = (y2 - y1) * imgH
        if (dx == 0.0 && dy == 0.0) return 0.0
        var angle = atan2(dy, dx)
        while (angle > PI / 4) angle -= PI / 2
        while (angle < -PI / 4) angle += PI / 2
        return if (abs(angle) < 0.05) 0.0 else angle
    }

    fun destroy() {
        // Drain in-flight inferences BEFORE taking [lock] (never the reverse):
        // permit holders only ever wait on [lock] briefly inside lazy init,
        // and [lock] holders never wait on permits, so this cannot deadlock.
        // Late arrivals block on permits, then re-init after initialized=false.
        runBlocking {
            repeat(INFER_PERMITS) { inferPermits.acquire() }
        }
        try {
            synchronized(lock) {
                if (isInitialized) {
                    try {
                        nativeRelease()
                    } catch (_: Throwable) {
                    }
                    isInitialized = false
                    // Forget the loaded identity with the weights: the next init records what it
                    // actually loaded (and a failed init must not leave a claim about model files).
                    loadedModelKey = null
                    logcat(LogPriority.INFO) { "Paddle OCR Engine closed" }
                }
            }
        } finally {
            repeat(INFER_PERMITS) { inferPermits.release() }
        }
    }

    override fun close() {
        destroy()
    }

    // ---------------------------------------------------------------- JNI surface
    // Implemented by `libpaddle_ocr.so` (see the INTEGRATION POINT note at the top of this file).

    private external fun nativeInit(
        detParamAbsPath: String,
        detBinAbsPath: String,
        recParamAbsPath: String,
        recBinAbsPath: String,
        dictAbsPath: String,
        numThreads: Int,
    ): Boolean

    private external fun nativeProcess(
        bitmap: Bitmap,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        filterFurigana: Boolean,
        numThreads: Int,
    ): String

    private external fun nativeRelease()

    // KUN -->
    /**
     * Human-readable reason the native engine's last init failed (the ORT error string), or "" when
     * unavailable.
     *
     * **Forward-compatible stub.** The pinned `ocr-models-v3` shim does **not** export this symbol
     * yet, so calling it throws [UnsatisfiedLinkError] (caught by [nativeOrtError]); it activates on
     * the next shim rebuild that adds the JNI getter (artifact-side work, see `ort-v6-migration.md`
     * §6 “Still open”). Until then the shim's `nativeInit` logcat line is the source of truth.
     */
    private external fun nativeGetOrtError(): String
    // KUN <--

    /**
     * Big-core count, ported from ocrtest. Cores whose max frequency is
     * within 15% of the fastest core count as performance cores; falls back
     * to half the cores on devices without readable cpufreq nodes.
     */
    private fun detectPerformanceCores(): Int {
        try {
            val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val freqs = mutableListOf<Long>()
            for (i in 0 until totalCores) {
                val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (file.exists() && file.canRead()) {
                    file.readText().trim().toLongOrNull()?.takeIf { it > 0 }?.let { freqs.add(it) }
                }
            }
            if (freqs.isNotEmpty()) {
                val maxFreq = freqs.maxOrNull() ?: 0L
                val bigCores = freqs.count { it >= (maxFreq * 0.85).toLong() }
                if (bigCores in 1..totalCores) return bigCores
            }
            if (totalCores >= 8) return 4
            return (totalCores / 2).coerceAtLeast(1)
        } catch (_: Throwable) {
            return 4.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        }
    }

    // KUN -->
    private fun countJpChars(text: String): Int {
        var count = 0
        for (i in 0 until text.length) {
            val code = text[i].code
            if ((code in 0x3041..0x3096) || (code in 0x30A1..0x30FA) || (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)) {
                count++
            }
        }
        return count
    }
    // KUN <--
}

// KUN -->
/**
 * Whether [PaddleOcrEngine] has to swap its weights: [loadedKey] is the identity `init` recorded for
 * the weights now in native memory (null before the first init, and after `destroy` forgot them) and
 * [installedKey] is what [installedModelIdentity] reports for the files on disk **now**.
 *
 * Both are whole identities — the installed-release marker **and** the model files' own fingerprint —
 * and the comparison is over all of it, never over the release tag alone: the detector's file name is
 * the same in every release, so a replaced file keeps the tag. Keying on a selection-derived value
 * instead is the bug this exists for: the engine reported a release for bytes it never loaded, and a
 * later replacement looked like "unchanged".
 *
 * Pure and top-level only so it is testable: `PaddleOcrEngine` needs an Android `Context` and
 * `libpaddle_ocr.so`, so a mutation back to a selection-derived comparison would pass every other
 * test in this module.
 */
internal fun shouldReloadModels(loadedKey: String?, installedKey: String): Boolean = loadedKey != installedKey
// KUN <--
