// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Rewrite of Chimahon's `app/src/main/java/eu/kanade/tachiyomi/data/ocr/ModelDownloader.kt`
// @ f85c063 (contract §1.7, 03 §2, risk R2).
//
// Chimahon hot-linked a third-party zip from its own release and never verified it. komi-kun instead
// consumes **our own** `komi-kun/komi-kun-ocr-models` release: a `manifest.json` lists each model
// file with its SHA-256, size and SPDX id, and this downloader verifies every byte against that hash
// before keeping it. Downloads are runtime, user-opt-in (the settings CTA / the import path), and the
// `.so`s themselves ship inside the APK via `core/ocr/src/main/jniLibs` — only the models + dict are
// fetched here. No WorkManager / app notification utilities (contract §1.1), so progress is exposed
// through [state].
// KUN <--
package mihon.core.ocr.paddle

import android.content.Context
import android.net.Uri
import exh.log.xLogE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** Our own release tag. A cached `manifest.json` from any other release must be re-fetched. */
private const val DEFAULT_RELEASE = "ocr-models-v3"

/** Our own release; the `.so`s are consumed at build time, only models download at runtime. */
private const val DEFAULT_BASE_URL =
    "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/$DEFAULT_RELEASE"

/** One downloadable OCR artifact from the release manifest. */
@Serializable
data class OcrModelFile(
    val name: String,
    /** Path relative to the manifest `baseUrl`; defaults to `models/<name>`. */
    val path: String? = null,
    val sha256: String = "",
    val size: Long = 0,
    val abi: String? = null,
    val spdx: String? = null,
)

/** The release manifest (`manifest.json`) describing every OCR model artifact. */
@Serializable
data class OcrModelManifest(
    val release: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val files: List<OcrModelFile> = emptyList(),
)

/** Progress of a runtime model download. */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val downloaded: Int, val total: Int) : ModelDownloadState
    data object Completed : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Downloads and verifies the PaddleOCR det/rec models and `ppocrv6_small_dict.txt` into
 * `filesDir/paddle_ocr/` (where [PaddleOcrEngine] loads them from), and can import them from a
 * user-picked archive. [triggerPaddleDownload] is the runtime, opt-in entry point.
 *
 * Also prunes model files published by earlier releases that the engine no longer loads
 * ([LEGACY_MODELS]) so an in-place upgrade does not keep dead weights around.
 */
class ModelDownloader(
    context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Coalesces redundant [triggerPaddleDownload] calls into one in-flight run. */
    private val downloadGate = SingleFlightGate()

    /** Serialises a [downloadModels] run against an [importFromUri] run (both write [modelsDir]). */
    private val runLock = Mutex()

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    /** Directory the engine reads its models from (`filesDir/paddle_ocr`). */
    val modelsDir: File get() = File(appContext.filesDir, PADDLE_DIR)

    /**
     * Cheap, synchronous readiness gate for the settings screen (see `SettingsOcrScreen`): every
     * required model file exists and, when the manifest cached beside the models is available,
     * matches its published **size** (no cached manifest → a mere non-empty check).
     *
     * It does read and JSON-parse the small cached `manifest.json`, so it is **not** a pure `stat`,
     * but it deliberately does **not** SHA-256 the ~31 MB of model bytes — that is what keeps it
     * main-thread safe (the previous full-hash implementation was not). The full size **and**
     * SHA-256 verification runs inside [downloadModels], off the main thread; [triggerPaddleDownload]
     * always enters that coroutine (once), so a same-size but corrupt model is still detected and
     * re-fetched.
     */
    val isPaddleDownloaded: Boolean
        get() {
            val manifest = loadCachedManifest()
                ?: return requiredModelFiles().all { it.isFile && it.length() > 0L }
            return requiredModelFiles().all { file ->
                val entry = manifest.files.firstOrNull { it.name == file.name }
                entry != null && modelFileHasExpectedSize(file, entry)
            }
        }

    /** The model files the engine needs at runtime (the `.so`s ship in the APK). Backs [isPaddleDownloaded]. */
    fun requiredModelFiles(): List<File> = REQUIRED_MODELS.map { File(modelsDir, it) }

    /** The manifest cached by the last [fetchManifest], or null when none is on disk. */
    private fun loadCachedManifest(): OcrModelManifest? {
        val file = File(modelsDir, MANIFEST_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<OcrModelManifest>(file.readText()) }.getOrNull()
    }

    /**
     * Runtime, user-opt-in entry point (the settings CTA). Non-suspend and main-thread safe: it only
     * schedules the work. The coroutine re-verifies every model — size **and** SHA-256 — off the
     * main thread and (re-)downloads anything missing or corrupt, so calling it on each Paddle
     * selection is cheap and self-healing rather than a one-shot gate.
     *
     * **Single-flight.** A run already in flight is never started twice: a trigger arriving while a
     * run is active is coalesced into it (the running pass re-verifies the same pinned release), so
     * two runs can never delete each other's `<name>.part` scratch files or race on `manifest.json`.
     */
    fun triggerPaddleDownload() {
        if (!downloadGate.tryAcquire()) {
            logcat(LogPriority.INFO) { "ModelDownloader: download already in progress; ignoring duplicate trigger" }
            return
        }
        scope.launch {
            try {
                runLock.withLock {
                    downloadModels().fold(
                        onSuccess = { _state.value = ModelDownloadState.Completed },
                        onFailure = {
                            val message = it.message ?: "Unknown error"
                            _state.value = ModelDownloadState.Failed(message)
                            xLogE("ModelDownloader: model download failed: $message", it)
                        },
                    )
                }
            } finally {
                downloadGate.release()
            }
        }
    }

    /** Fetches and parses the release manifest (and caches it beside the models). */
    suspend fun fetchManifest(): Result<OcrModelManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$DEFAULT_BASE_URL/$MANIFEST_NAME").build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Manifest download failed: HTTP ${response.code}" }
                val body = response.body.string()
                val manifest = json.decodeFromString<OcrModelManifest>(body)
                // Persist the manifest with the models so `downloadModels` can re-verify size /
                // SHA-256 offline instead of trusting mere file existence.
                File(modelsDir, MANIFEST_NAME).apply { parentFile?.mkdirs() }.writeText(body)
                manifest
            }
        }
    }

    /**
     * Verifies every required model against the manifest — size **and** SHA-256 — and downloads the
     * ones that are missing or corrupt. Fails (and keeps nothing new) when the manifest lacks a
     * checksum for a required file, or a downloaded hash mismatches.
     *
     * Runs on [Dispatchers.IO]; this is the only place the (expensive) full-file hashing happens, so
     * the synchronous [isPaddleDownloaded] gate stays cheap.
     */
    suspend fun downloadModels(): Result<Unit> = withContext(Dispatchers.IO) {
        // Drop any `.part` scratch files an interrupted earlier run left behind.
        cleanupPartFiles()

        // Offline-friendly fast path: the cached manifest was fetched from the release we still pin
        // AND fully accounts for every required file, and every file verifies (size + SHA-256) —
        // nothing to download. Checking the release id stops a cached manifest from pinning an old
        // model set forever when a future release reuses the same file names.
        loadCachedManifest()?.let { cached ->
            if (cachedManifestIsUsable(cached, DEFAULT_RELEASE, DEFAULT_BASE_URL, modelsDir, REQUIRED_MODELS)) {
                cleanupStaleModels()
                return@withContext Result.success(Unit)
            }
        }

        val manifest = fetchManifest().getOrElse { return@withContext Result.failure(it) }
        val entries = mutableListOf<Pair<OcrModelFile, String>>()
        for (name in REQUIRED_MODELS) {
            val entry = manifest.files.firstOrNull { it.name == name }
                ?: return@withContext Result.failure(IllegalStateException("Manifest is missing $name"))
            if (entry.sha256.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Manifest is missing a SHA-256 for $name"))
            }
            entries += entry to "$DEFAULT_BASE_URL/${entry.path ?: "models/${entry.name}"}"
        }

        // Re-fetch only what is missing or fails verification; keep the rest untouched.
        val pending = pendingModelEntries(entries, modelsDir)
        if (pending.isNotEmpty()) {
            val done = entries.size - pending.size
            _state.value = ModelDownloadState.Downloading(done, entries.size)
            pending.forEachIndexed { index, (entry, url) ->
                downloadAndVerify(url, entry).getOrElse { return@withContext Result.failure(it) }
                _state.value = ModelDownloadState.Downloading(done + index + 1, entries.size)
            }
        }

        // Only after every current model landed: prune what an earlier release installed.
        cleanupStaleModels()
        Result.success(Unit)
    }

    /**
     * Imports an archive the user picked. Extracts it into a scratch dir, verifies the staged model
     * files against the manifest **before** anything is copied into [modelsDir], and only then
     * installs them — so a bad or partial archive can never clobber a working install.
     */
    suspend fun importFromUri(uri: Uri): Result<Unit> = runLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected file")
                // Prefer the live manifest; fall back to the one cached beside the models so import
                // still verifies (and is not a silent no-op) offline.
                val manifest = fetchManifest().getOrElse {
                    loadCachedManifest() ?: error("No model manifest available to verify the archive")
                }
                // The archive is untrusted. Extract it into a throwaway scratch dir, verify it there,
                // then copy ONLY the model files the engine loads into modelsDir — never an arbitrary
                // path, in particular not a planted `lib/<abi>/*.so` that
                // `PaddleOcrEngine.loadNativeLibraries` would `System.load`. The scratch dir is
                // removed whatever happens.
                val scratch = File(appContext.cacheDir, "ocr-import-${System.nanoTime()}")
                scratch.mkdirs()
                try {
                    input.use { extractZipInto(scratch, it) }
                    verifyStagedModels(scratch, manifest, REQUIRED_MODELS)
                    installRequiredModels(scratch, modelsDir, REQUIRED_MODELS)
                } finally {
                    scratch.deleteRecursively()
                }
                cleanupStaleModels()
                Unit
            }
        }
    }

    /**
     * Deletes model files published by an earlier release that the engine no longer loads (see
     * [LEGACY_MODELS]), so an in-place upgrade does not keep dead weights in `filesDir/paddle_ocr/`.
     *
     * Safe by construction: it only ever removes names this app itself published, never arbitrary
     * files a user may have placed in the directory. Returns the names actually removed.
     */
    fun cleanupStaleModels(): List<String> {
        val removed = mutableListOf<String>()
        for (name in LEGACY_MODELS) {
            if (name in REQUIRED_MODELS) continue
            val file = File(modelsDir, name)
            if (file.isFile && file.delete()) {
                removed += name
                logcat(LogPriority.INFO) { "ModelDownloader: removed stale model $name" }
            }
        }
        return removed
    }

    /**
     * Removes leftover `<required-model>.part` scratch files (an interrupted download). Like
     * [cleanupStaleModels] this is safe by construction: only the exact names this downloader writes
     * are ever deleted. Returns the names actually removed.
     */
    fun cleanupPartFiles(): List<String> {
        val removed = removeOwnedPartFiles(modelsDir, REQUIRED_MODELS)
        removed.forEach { logcat(LogPriority.INFO) { "ModelDownloader: removed partial download $it" } }
        return removed
    }

    private fun downloadAndVerify(url: String, entry: OcrModelFile): Result<Unit> = runCatching {
        logcat(LogPriority.INFO) { "ModelDownloader: downloading ${entry.name}" }
        val request = Request.Builder().url(url).build()
        val target = File(modelsDir, entry.name)
        val temp = File(modelsDir, "${entry.name}.part")
        temp.parentFile?.mkdirs()

        try {
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed for ${entry.name}: HTTP ${response.code}" }
                response.body.byteStream().use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
            }

            val actual = sha256Hex(temp)
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                error("SHA-256 mismatch for ${entry.name}")
            }
            if (entry.size > 0 && temp.length() != entry.size) {
                logcat(LogPriority.WARN) { "ModelDownloader: ${entry.name} size differs from the manifest" }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            Unit
        } finally {
            // Never leave a partial download behind (HTTP/IO failure or checksum mismatch), and only
            // ever delete our own `<name>.part` scratch file.
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private const val MANIFEST_NAME = "manifest.json"
        private const val PADDLE_DIR = "paddle_ocr"

        // PP-OCRv6 small (ocr-models-v3): ONNX graphs (the shipped backend is ONNX Runtime).
        private const val DET_MODEL = "ppocrv6_small_det.onnx"
        private const val REC_MODEL = "ppocrv6_small_rec.onnx"
        private const val DICT_TXT = "ppocrv6_small_dict.txt"

        /** Must stay in sync with [PaddleOcrEngine]'s model constants. */
        val REQUIRED_MODELS = listOf(DET_MODEL, REC_MODEL, DICT_TXT)

        /**
         * Model files shipped by `ocr-models-v1` (PP-OCRv4) and `ocr-models-v2` (PP-OCRv5) that the
         * engine no longer loads. Listed by name so [cleanupStaleModels] can prune them on upgrade
         * without ever touching a file this app did not publish.
         */
        private val LEGACY_MODELS = listOf(
            "ppocrv4_det.nb",
            "ppocrv4_japan_rec.nb",
            "ppocrv4_japan_dict.txt",
            "ppocrv5_det.nb",
            "ppocrv5_rec.nb",
            "ppocrv5_dict.txt",
        )
    }
}

/** SHA-256 of [file], lower-case hex. */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * True when [file] is a regular, non-empty file matching [entry]'s published [OcrModelFile.size]
 * (when known). Cheap (`stat` only): safe for the main-thread [ModelDownloader.isPaddleDownloaded]
 * gate. Use [modelFileMatches] when the SHA-256 must be checked too.
 */
internal fun modelFileHasExpectedSize(file: File, entry: OcrModelFile): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    return entry.size <= 0L || file.length() == entry.size
}

/**
 * True when [file] is a regular, non-empty file matching [entry]'s published [OcrModelFile.size]
 * (when known) and [OcrModelFile.sha256] (when known). Shared by the cached-manifest
 * "already downloaded" check and post-import verification, so a truncated or corrupt model is
 * rejected instead of counted as present. Reads the whole file, so call it off the main thread.
 */
internal fun modelFileMatches(file: File, entry: OcrModelFile): Boolean {
    if (!modelFileHasExpectedSize(file, entry)) return false
    if (entry.sha256.isNotBlank() && !sha256Hex(file).equals(entry.sha256, ignoreCase = true)) return false
    return true
}

/**
 * Deletes the `<name>.part` scratch files this downloader writes for [names] inside [dir]. Safe by
 * construction: only those exact, app-owned names are ever removed — never an arbitrary file.
 * Returns the names actually deleted.
 */
internal fun removeOwnedPartFiles(dir: File, names: Collection<String>): List<String> {
    val removed = mutableListOf<String>()
    for (name in names) {
        val file = File(dir, "$name.part")
        if (file.isFile && file.delete()) removed += file.name
    }
    return removed
}

/**
 * Extracts a zip [input] into [dir], rejecting entries that would escape it (zip-slip).
 *
 * Used by [ModelDownloader.importFromUri] to stage an **untrusted** user archive into a scratch
 * directory; only the required model files are then copied into the engine directory (see
 * [installRequiredModels]), so nothing else — e.g. a planted `.so` under `lib/` — is ever written
 * where the engine would load it.
 */
internal fun extractZipInto(dir: File, input: InputStream) {
    val root = dir.canonicalFile
    root.mkdirs()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val target = File(root, entry.name).canonicalFile
            check(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
                "Unsafe zip entry: ${entry.name}"
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output -> zip.copyTo(output) }
            }
            zip.closeEntry()
        }
    }
}

/**
 * Finds the first regular file named [name] anywhere under [from], or null. Shared by
 * [installRequiredModels] and [verifyStagedModels] so both match an archive's model files the same
 * way (a release may nest them under `models/`).
 */
internal fun findModelFile(from: File, name: String): File? =
    from.walkTopDown().firstOrNull { it.isFile && it.name == name }

/**
 * Verifies the required model files staged under [from] against [manifest] **before** they are
 * copied into the engine directory, so a bad, partial or misnamed archive can never clobber a
 * working install. Every required name must be described by the manifest with a checksum and must
 * match it; the first failure throws.
 */
internal fun verifyStagedModels(from: File, manifest: OcrModelManifest, names: Collection<String>) {
    for (name in names) {
        val entry = manifest.files.firstOrNull { it.name == name } ?: error("Manifest is missing $name")
        if (entry.sha256.isBlank()) error("Manifest is missing a SHA-256 for $name")
        val file = findModelFile(from, name) ?: error("$name was not found in the archive")
        check(modelFileMatches(file, entry)) { "Checksum or size mismatch for $name" }
    }
}

/**
 * Copies only the model files named in [names] from [from] into [to], matching by file name
 * (anywhere under [from]), and returns the names actually installed. Everything else in [from] is
 * ignored, so an untrusted archive cannot install a non-model (or executable) path into the engine's
 * model directory. Missing names are skipped; [verifyStagedModels] runs first and reports them.
 */
internal fun installRequiredModels(from: File, to: File, names: Collection<String>): List<String> {
    if (!from.isDirectory) return emptyList()
    to.mkdirs()
    val installed = mutableListOf<String>()
    for (name in names) {
        val source = findModelFile(from, name) ?: continue
        source.copyTo(File(to, name), overwrite = true)
        installed += name
    }
    return installed
}

/**
 * A minimal single-flight gate: the first [tryAcquire] succeeds, every call until [release] is
 * refused. [ModelDownloader.triggerPaddleDownload] uses it to coalesce redundant triggers, so a
 * second run cannot purge the first run's in-flight `<name>.part` files (or race on `manifest.json`).
 */
internal class SingleFlightGate {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        inFlight.set(false)
    }
}

/**
 * True when [manifest] was fetched from the release the app still pins ([release] tag or [baseUrl]).
 * A cached manifest from any other release must be re-fetched even if it still accounts for every
 * required file — otherwise a release that reuses the model file names could never be picked up.
 */
internal fun cachedManifestIsCurrent(manifest: OcrModelManifest, release: String, baseUrl: String): Boolean =
    manifest.release == release || manifest.baseUrl == baseUrl

/**
 * True when [manifest] accounts for every required model in [names] **and** each one verifies
 * against its published size and SHA-256 inside [modelsDir]. A same-size but corrupt file fails here
 * and is re-fetched.
 */
internal fun requiredModelsVerified(
    manifest: OcrModelManifest,
    modelsDir: File,
    names: Collection<String>,
): Boolean = names.all { name ->
    val entry = manifest.files.firstOrNull { it.name == name }
    entry != null && entry.sha256.isNotBlank() && modelFileMatches(File(modelsDir, name), entry)
}

/**
 * The pure fast-path decision: [manifest] is from the pinned release **and** every required model
 * verifies, so [ModelDownloader.downloadModels] can skip the network. Extracted so it is testable
 * without an Android `Context`.
 */
internal fun cachedManifestIsUsable(
    manifest: OcrModelManifest,
    release: String,
    baseUrl: String,
    modelsDir: File,
    names: Collection<String>,
): Boolean = cachedManifestIsCurrent(manifest, release, baseUrl) &&
    requiredModelsVerified(manifest, modelsDir, names)

/**
 * The subset of [entries] that is missing or fails verification in [modelsDir] — the "re-fetch only
 * what mismatched" decision, extracted so it is testable without an Android `Context`.
 */
internal fun pendingModelEntries(
    entries: List<Pair<OcrModelFile, String>>,
    modelsDir: File,
): List<Pair<OcrModelFile, String>> =
    entries.filterNot { (entry, _) -> modelFileMatches(File(modelsDir, entry.name), entry) }
