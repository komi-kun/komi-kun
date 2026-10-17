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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import java.util.zip.ZipInputStream

/** Our own release; the `.so`s are consumed at build time, only models download at runtime. */
private const val DEFAULT_BASE_URL =
    "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/ocr-models-v3"

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

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    /** Directory the engine reads its models from (`filesDir/paddle_ocr`). */
    val modelsDir: File get() = File(appContext.filesDir, PADDLE_DIR)

    /**
     * True when every required model file is present **and intact**. When the manifest fetched with
     * the models is cached (the normal case), each file is checked against its published size and
     * SHA-256 — a truncated or corrupt model is therefore reported as not-downloaded, so
     * [triggerPaddleDownload] re-fetches it. Falls back to a mere non-empty check only when no cached
     * manifest exists (e.g. an offline import), where no checksum is available.
     */
    val isPaddleDownloaded: Boolean
        get() {
            val manifest = loadCachedManifest()
                ?: return requiredModelFiles().all { it.isFile && it.length() > 0L }
            return requiredModelFiles().all { file ->
                val entry = manifest.files.firstOrNull { it.name == file.name }
                entry != null && modelFileMatches(file, entry)
            }
        }

    /** The model files the engine needs at runtime (the `.so`s ship in the APK). */
    fun requiredModelFiles(): List<File> = REQUIRED_MODELS.map { File(modelsDir, it) }

    /** The manifest cached by the last [fetchManifest], or null when none is on disk. */
    private fun loadCachedManifest(): OcrModelManifest? {
        val file = File(modelsDir, MANIFEST_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<OcrModelManifest>(file.readText()) }.getOrNull()
    }

    /** Runtime, user-opt-in download of all required models (checksum-verified). */
    fun triggerPaddleDownload() {
        if (isPaddleDownloaded) {
            // Already on the current model set: still drop anything a previous release left behind.
            cleanupStaleModels()
            _state.value = ModelDownloadState.Completed
            return
        }
        scope.launch {
            downloadModels().fold(
                onSuccess = { _state.value = ModelDownloadState.Completed },
                onFailure = { _state.value = ModelDownloadState.Failed(it.message ?: "Unknown error") },
            )
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
                // Persist the manifest with the models so `isPaddleDownloaded` can re-verify size /
                // SHA-256 offline instead of trusting mere file existence.
                File(modelsDir, MANIFEST_NAME).apply { parentFile?.mkdirs() }.writeText(body)
                manifest
            }
        }
    }

    /**
     * Downloads every required model file from the manifest, verifying each SHA-256. Fails (and keeps
     * nothing) when the manifest lacks a checksum for a required file, or a hash mismatches.
     */
    suspend fun downloadModels(): Result<Unit> = withContext(Dispatchers.IO) {
        // Drop any `.part` scratch files an interrupted earlier run left behind.
        cleanupPartFiles()
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

        _state.value = ModelDownloadState.Downloading(0, entries.size)
        entries.forEachIndexed { index, (entry, url) ->
            downloadAndVerify(url, entry).getOrElse { return@withContext Result.failure(it) }
            _state.value = ModelDownloadState.Downloading(index + 1, entries.size)
        }
        // Only after every current model landed: prune what an earlier release installed.
        cleanupStaleModels()
        Result.success(Unit)
    }

    /** Imports an archive the user picked; extracts it and verifies what a manifest describes. */
    suspend fun importFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected file")
            // Extract straight into the directory the engine loads from, so verification below and
            // engine loading see the same files (the previous `filesDir` root never matched).
            input.use { extractZipInto(modelsDir, it) }
        }.mapCatching {
            // Verify the freshly imported files against the manifest when one is available.
            fetchManifest().getOrNull()?.let { manifest -> verifyInstalled(manifest) }
            cleanupStaleModels()
            cleanupPartFiles()
            Unit
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

    private fun verifyInstalled(manifest: OcrModelManifest) {
        for (name in REQUIRED_MODELS) {
            val entry = manifest.files.firstOrNull { it.name == name } ?: continue
            val file = File(modelsDir, name)
            check(file.isFile) { "$name was not imported" }
            check(modelFileMatches(file, entry)) { "Checksum or size mismatch for $name" }
        }
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
 * (when known) and [OcrModelFile.sha256] (when known). Shared by the cached-manifest
 * "already downloaded" check and post-import verification, so a truncated or corrupt model is
 * rejected instead of counted as present.
 */
internal fun modelFileMatches(file: File, entry: OcrModelFile): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    if (entry.size > 0 && file.length() != entry.size) return false
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
 * Extracts a zip [input] directly into [dir] (the engine's model directory), rejecting entries that
 * would escape it (zip-slip). Used by [ModelDownloader.importFromUri] so extraction, verification
 * and engine loading all agree on one directory.
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
