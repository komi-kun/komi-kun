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
    "https://github.com/komi-kun/komi-kun-ocr-models/releases/download/ocr-models-v1"

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
 * Downloads and verifies the PaddleOCR det/rec models and `ppocrv4_japan_dict.txt` into
 * `filesDir/paddle_ocr/` (where [PaddleOcrEngine] loads them from), and can import them from a
 * user-picked archive. [triggerPaddleDownload] is the runtime, opt-in entry point.
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

    /** True when every required model file exists and is non-empty. */
    val isPaddleDownloaded: Boolean
        get() = requiredModelFiles().all { it.isFile && it.length() > 0L }

    /** The model files the engine needs at runtime (the `.so`s ship in the APK). */
    fun requiredModelFiles(): List<File> = REQUIRED_MODELS.map { File(modelsDir, it) }

    /** Runtime, user-opt-in download of all required models (checksum-verified). */
    fun triggerPaddleDownload() {
        if (isPaddleDownloaded) {
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

    /** Fetches and parses the release manifest. */
    suspend fun fetchManifest(): Result<OcrModelManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$DEFAULT_BASE_URL/$MANIFEST_NAME").build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Manifest download failed: HTTP ${response.code}" }
                json.decodeFromString<OcrModelManifest>(response.body.string())
            }
        }
    }

    /**
     * Downloads every required model file from the manifest, verifying each SHA-256. Fails (and keeps
     * nothing) when the manifest lacks a checksum for a required file, or a hash mismatches.
     */
    suspend fun downloadModels(): Result<Unit> = withContext(Dispatchers.IO) {
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
        Result.success(Unit)
    }

    /** Imports an archive the user picked; extracts it and verifies what a manifest describes. */
    suspend fun importFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected file")
            input.use { extractZip(it) }
        }.mapCatching {
            // Verify the freshly imported files against the manifest when one is available.
            fetchManifest().getOrNull()?.let { manifest -> verifyInstalled(manifest) }
            Unit
        }
    }

    private fun verifyInstalled(manifest: OcrModelManifest) {
        for (name in REQUIRED_MODELS) {
            val expected = manifest.files.firstOrNull { it.name == name }?.sha256.orEmpty()
            if (expected.isBlank()) continue
            val file = File(modelsDir, name)
            check(file.isFile) { "$name was not imported" }
            check(sha256Hex(file).equals(expected, ignoreCase = true)) { "Checksum mismatch for $name" }
        }
    }

    private fun downloadAndVerify(url: String, entry: OcrModelFile): Result<Unit> = runCatching {
        logcat(LogPriority.INFO) { "ModelDownloader: downloading ${entry.name}" }
        val request = Request.Builder().url(url).build()
        val target = File(modelsDir, entry.name)
        val temp = File(modelsDir, "${entry.name}.part")
        temp.parentFile?.mkdirs()

        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed for ${entry.name}: HTTP ${response.code}" }
            response.body.byteStream().use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
        }

        val actual = sha256Hex(temp)
        if (!actual.equals(entry.sha256, ignoreCase = true)) {
            temp.delete()
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
    }

    private fun extractZip(input: InputStream) {
        val root = appContext.filesDir.canonicalFile
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

    companion object {
        private const val MANIFEST_NAME = "manifest.json"
        private const val PADDLE_DIR = "paddle_ocr"

        private const val DET_MODEL = "ppocrv4_det.nb"
        private const val REC_MODEL = "ppocrv4_japan_rec.nb"
        private const val DICT_TXT = "ppocrv4_japan_dict.txt"

        /** Must stay in sync with [PaddleOcrEngine]'s model constants. */
        val REQUIRED_MODELS = listOf(DET_MODEL, REC_MODEL, DICT_TXT)
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
