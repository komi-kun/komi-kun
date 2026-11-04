// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Downloads and verifies the upscaling models.
//
// Mirrors `:core:ocr`'s `ModelDownloader` deliberately: a `manifest.json` in our own release lists each
// ONNX file with its SHA-256 and size, every byte is verified before the file is kept, the install
// marker is written only once *all* selected files verified, and superseded files are pruned by a
// name allow-list so nothing this app did not publish is ever deleted.
//
// **Opt-in, like OCR.** Nothing happens — not a request, not a `.part`, not a prune — while
// `UpscalePreferences.isEnabled()` is false: the download is a user action, not a background one.
//
// **The installed set is cheap.** `installedIds()` is a directory listing plus the marker and the
// cached manifest; it never hashes a model (that is ~10-20 MB per file and runs on the main thread's
// callers otherwise).
//
// **The metered preference is enforced here, not only in the settings screen.** `unmeteredOnly` used
// to be a check the UI made once, before calling this class, so a Wi-Fi -> cellular handover in the
// middle of a 530 MB run spent the bytes the preference refuses. It is re-read here — before the
// first byte and again before every file — so the switch is what decides, whichever caller asks.
//
// **Manifest names are one path segment, always.** A `name`/`path` of `../x`, `/abs` or `a/b` would
// write outside `filesDir/upscale/models`, so the manifest is rejected at parse time (fetched and
// cached alike) instead of being trusted by the twenty places that turn it into a `File`.
// KUN <--
package mihon.core.upscale.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.preference.UpscalePreferences
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One downloadable model file from the release manifest. */
@Serializable
data class UpscaleModelFile(
    /** File name on disk (the catalog's `UpscaleModel.fileName`). */
    val name: String,
    /** Path relative to the release's download root; defaults to [name]. */
    val path: String? = null,
    val sha256: String = "",
    val size: Long = 0,
    /** Catalog id (`UpscaleModel.id`). */
    val id: String = "",
    val scale: Int = 0,
    val family: String = "",
    val bucket: Int = 0,
)

/**
 * The release manifest. [baseUrl] deliberately defaults to `""`: a manifest that omits it must not
 * look like the one the app pinned ([cachedManifestIsCurrent]).
 */
@Serializable
data class UpscaleModelManifest(
    val release: String = "",
    val baseUrl: String = "",
    val files: List<UpscaleModelFile> = emptyList(),
)

/** Progress of a runtime model download. */
sealed interface UpscaleDownloadState {
    data object Idle : UpscaleDownloadState
    data class Downloading(val done: Int, val total: Int) : UpscaleDownloadState
    data object Completed : UpscaleDownloadState
    data class Failed(val message: String) : UpscaleDownloadState
}

/** What the engine and the service need to know about the models on disk. */
interface InstalledModels {
    /** Ids of the models that are installed **and** belong to the release the marker records. */
    fun installedIds(): Set<String>

    /** The `.onnx` file of [model], or `null` when it is not installed. */
    fun fileFor(model: UpscaleModel): File?
}

/**
 * Downloads, verifies and prunes the upscaling models in `filesDir/upscale/models/`.
 */
class ModelDownloader(
    context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val preferences: UpscalePreferences,
    /** URL of the release's `manifest.json`. */
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    /**
     * Whether the network in use right now is metered.
     *
     * Read at the start of a run **and** before every file, so a Wi-Fi -> cellular handover mid-run
     * stops it instead of spending the rest of the download. Unknown counts as metered: the check
     * exists to protect the user's data, so its failure mode must be "do not download".
     */
    private val metered: () -> Boolean = { isMetered(context) },
) : InstalledModels {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()

    private val _state = MutableStateFlow<UpscaleDownloadState>(UpscaleDownloadState.Idle)
    val state: StateFlow<UpscaleDownloadState> = _state.asStateFlow()

    /** Directory the engine reads its models from (`filesDir/upscale/models`). */
    val modelsDir: File get() = File(appContext.filesDir, MODELS_DIR)

    /** The release the last completed install recorded, or null when nothing is recorded. */
    val installedRelease: String? get() = installedReleaseMarker(modelsDir)

    // ------------------------------------------------------------------ installed set (cheap)

    override fun installedIds(): Set<String> {
        val release = installedReleaseMarker(modelsDir) ?: return emptySet()
        val manifest = loadCachedManifest() ?: return emptySet()
        if (!cachedManifestIsCurrent(manifest, release)) return emptySet()
        return manifest.files
            .filter { entry -> File(modelsDir, entry.name).let { it.isFile && it.length() > 0L } }
            .mapNotNull { entry -> entry.id.ifEmpty { catalogIdFor(entry.name) } }
            .toSet()
    }

    override fun fileFor(model: UpscaleModel): File? =
        File(modelsDir, model.fileName).takeIf { it.isFile && it.length() > 0L }

    /** True when [model] is installed for the release the marker records (see [installedIds]). */
    fun isInstalled(model: UpscaleModel): Boolean = model.id in installedIds()

    // ------------------------------------------------------------------ download

    /**
     * Starts a download of [ids] (every model of the current manifest when empty), off the main
     * thread. Inert while [UpscalePreferences.isEnabled] is false, and refuses up front when the
     * network is metered and only unmetered downloads are allowed.
     */
    fun triggerDownload(ids: Collection<String> = emptyList()) {
        if (!preferences.isEnabled()) return
        // Answered here as well as in `downloadModels` so the refusal is visible immediately, not
        // after a coroutine hop: nothing is queued and no `.part` is touched.
        if (meteredRefused()) {
            _state.value = UpscaleDownloadState.Failed(METERED_ONLY_MESSAGE)
            logcat { "UpscaleModelDownloader: model download refused: $METERED_ONLY_MESSAGE" }
            return
        }
        scope.launch {
            val result = downloadModels(ids)
            result.exceptionOrNull()?.let { error ->
                val message = error.message ?: "Unknown error"
                _state.value = UpscaleDownloadState.Failed(message)
                logcat { "UpscaleModelDownloader: model download failed: $message" }
            }
        }
    }

    /** Fetches and parses the release manifest, caching it beside the models. */
    suspend fun fetchManifest(): Result<UpscaleModelManifest> {
        if (!preferences.isEnabled()) return Result.failure(disabledFailure())
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(manifestUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Manifest download failed: HTTP ${response.code}" }
                    val body = response.body.string()
                    val manifest = parseManifest(body)
                    File(modelsDir, MANIFEST_NAME).apply { parentFile?.mkdirs() }.writeText(body)
                    manifest
                }
            }
        }
    }

    /**
     * Verifies every selected model against the manifest — size **and** SHA-256 — and downloads the
     * ones that are missing or corrupt.
     *
     * Each file is verified in its `.part` form before it is renamed into place, so nothing truncated
     * or unverified is ever installed. The marker is written only after **every** selected file
     * verified, so a partial set stays "not installed" and is finished by the next run.
     */
    suspend fun downloadModels(ids: Collection<String> = emptyList()): Result<Unit> {
        // Nothing at all happens while the feature is off — not even `.part` cleanup.
        if (!preferences.isEnabled()) return Result.failure(disabledFailure())
        // Same rule for the network: the preference is enforced before any request and before
        // `cleanupPartFiles`, so a refusal leaves the directory exactly as it was.
        if (meteredRefused()) return Result.failure(meteredFailure())
        return runLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    cleanupPartFiles()
                    val manifest = fetchManifest().getOrThrow()
                    val base = manifest.baseUrl.ifBlank { manifestUrl.substringBeforeLast('/') }
                    val selected = select(manifest, ids)
                    require(selected.isNotEmpty()) {
                        "the model manifest ${manifest.release} carries no model" +
                            if (ids.isEmpty()) "" else " named ${ids.joinToString()}"
                    }
                    for (entry in selected) {
                        require(entry.sha256.isNotBlank()) {
                            "the manifest is missing a SHA-256 for ${entry.name}"
                        }
                    }

                    _state.value = UpscaleDownloadState.Downloading(0, selected.size)
                    selected.forEachIndexed { index, entry ->
                        // Re-read per file: the handover that matters happens *during* a run, and
                        // the files are tens of megabytes each.
                        if (meteredRefused()) throw meteredFailure()
                        downloadAndVerify("$base/${entry.path ?: entry.name}", entry)
                        _state.value = UpscaleDownloadState.Downloading(index + 1, selected.size)
                    }

                    // Only now: every selected file is on disk and hash-verified.
                    cleanupStaleModels(keep = selected.map { it.name }.toSet())
                    writeInstalledReleaseMarker(modelsDir, manifest.release)
                    _state.value = UpscaleDownloadState.Completed
                }
            }
        }
    }

    /** The manifest entries [ids] names, or the whole manifest when [ids] is empty. */
    private fun select(manifest: UpscaleModelManifest, ids: Collection<String>): List<UpscaleModelFile> {
        if (ids.isEmpty()) return manifest.files
        val wanted = ids.toSet()
        return manifest.files.filter { entry ->
            entry.id in wanted || catalogIdFor(entry.name)?.let { it in wanted } == true
        }
    }

    private fun downloadAndVerify(url: String, entry: UpscaleModelFile) {
        logcat { "UpscaleModelDownloader: downloading ${entry.name}" }
        val target = File(modelsDir, entry.name)
        val part = File(modelsDir, "${entry.name}$PART_SUFFIX")
        part.parentFile?.mkdirs()
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    "Download failed for ${entry.name}: HTTP ${response.code}"
                }
                response.body.byteStream().use { input ->
                    FileOutputStream(part).use { output -> input.copyTo(output) }
                }
            }
            check(sha256Hex(part).equals(entry.sha256, ignoreCase = true)) {
                "SHA-256 mismatch for ${entry.name}"
            }
            check(entry.size <= 0L || part.length() == entry.size) {
                "size mismatch for ${entry.name}: ${part.length()} != ${entry.size}"
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } finally {
            // Never leave a partial download behind, and only ever delete our own `<name>.part`.
            if (part.exists()) part.delete()
        }
    }

    /**
     * Deletes model files an earlier release published that this one no longer ships, selected by the
     * [staleNames] allow-list (the legacy names by default), so a user's own files in the directory
     * are never touched.
     *
     * A name the catalog **still publishes** ([catalogIdFor] finds it) is skipped even when the
     * allow-list carries it and [keep] does not: the allow-list records what a *previous* release
     * called a file, and a name that came back into the catalog is live weights a subset install
     * (`triggerDownload(ids)`) must not delete.
     */
    fun cleanupStaleModels(
        keep: Set<String> = emptySet(),
        staleNames: Collection<String> = LEGACY_MODEL_NAMES,
    ): List<String> {
        val removed = mutableListOf<String>()
        for (name in staleNames) {
            if (name in keep) continue
            if (catalogIdFor(name) != null) continue
            val file = File(modelsDir, name)
            if (file.isFile && file.delete()) removed += name
        }
        return removed
    }

    /** Removes `<selected>.part` scratch files an interrupted run left behind. */
    fun cleanupPartFiles(): List<String> {
        val names = loadCachedManifest()?.files?.map { it.name }.orEmpty()
        return names.mapNotNull { name ->
            val part = File(modelsDir, "$name$PART_SUFFIX")
            if (part.isFile && part.delete()) part.name else null
        }
    }

    private fun loadCachedManifest(): UpscaleModelManifest? {
        val file = File(modelsDir, MANIFEST_NAME)
        if (!file.isFile) return null
        return runCatching { parseManifest(file.readText()) }.getOrNull()
    }

    /**
     * Parses [body] as a manifest and rejects any `name`/`path` that is not a single path segment.
     *
     * Everything downstream builds `File(modelsDir, entry.name)`, so a `name` of `../x`, `/abs` or
     * `a/b` would read or write outside the models directory. Rejecting the whole manifest (rather
     * than the offending entry) keeps "the release we pinned" all-or-nothing.
     */
    private fun parseManifest(body: String): UpscaleModelManifest {
        val manifest = json.decodeFromString<UpscaleModelManifest>(body)
        for (entry in manifest.files) {
            requireSegment("name", entry.name)
            entry.path?.let { requireSegment("path", it) }
        }
        return manifest
    }

    /** Requires [value] to be exactly one path segment: non-blank, not `.`/`..`, no separators. */
    private fun requireSegment(field: String, value: String) {
        require(value.isNotBlank()) { "the manifest has a blank model $field" }
        require(value != "." && value != ".." && File(value).name == value) {
            "the manifest's model $field \"$value\" is not a single path segment"
        }
    }

    /** True when the preference forbids this network, read fresh at every check. */
    private fun meteredRefused(): Boolean = preferences.unmeteredOnly.get() && metered()

    private fun disabledFailure(): IllegalStateException = IllegalStateException(
        "Reader upscaling is disabled: enable it before downloading models",
    )

    private fun meteredFailure(): IllegalStateException = IllegalStateException(METERED_ONLY_MESSAGE)

    companion object {
        /**
         * The model release this build consumes. Every model file the catalog names is published
         * here; the manifest carries the SHA-256 of each.
         */
        const val DEFAULT_MANIFEST_URL: String =
            "https://github.com/komi-kun/komi-kun-upscale-models/releases/download/" +
                "upscale-models-v1/manifest.json"

        private const val MANIFEST_NAME = "manifest.json"
        private const val MODELS_DIR = "upscale/models"
        private const val PART_SUFFIX = ".part"

        /**
         * The refusal a metered network produces while `unmeteredOnly` is on. Surfaced as the
         * download state's message, so it has to read as an explanation, not a stack trace.
         */
        const val METERED_ONLY_MESSAGE: String =
            "The network is metered and downloads are limited to unmetered networks: " +
                "connect to Wi-Fi or turn that limit off in the upscaling settings"

        /**
         * Whether the network in use is metered, for the default [ModelDownloader.metered].
         *
         * Unknown counts as metered (no `ConnectivityManager`, no active network, no capabilities):
         * the check protects the user's data plan, so it fails closed.
         */
        internal fun isMetered(context: Context): Boolean {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
            return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        /**
         * Model files published by `upscale-models-v0` (the pre-fp16 MangaJaNai exports), listed by
         * name so an upgrade prunes exactly these. Several entries are also current catalog
         * `fileName`s — the catalog's MangaJaNai names carry no `_fp16` suffix — so
         * [cleanupStaleModels] keeps any of them the catalog still publishes.
         */
        internal val LEGACY_MODEL_NAMES: List<String> = listOf(
            "2x_MangaJaNai_1200p_V1_ESRGAN_70k.onnx",
            "2x_MangaJaNai_1600p_V1_ESRGAN_90k.onnx",
            "4x_MangaJaNai_1600p_V1_ESRGAN_70k.onnx",
            "4x_MangaJaNai_2048p_V1_ESRGAN_70k.onnx",
        )
    }
}

/** Name of the marker recording the fully installed, hash-verified model release. */
internal const val UPSCALE_INSTALLED_RELEASE_MARKER = "installed-release.txt"

/** The release tag the last completed install recorded in [dir], or null when none did. */
internal fun installedReleaseMarker(dir: File): String? {
    val file = File(dir, UPSCALE_INSTALLED_RELEASE_MARKER)
    if (!file.isFile) return null
    return file.readText().trim().ifEmpty { null }
}

/**
 * Records [release] as the installed model set, staged through a `.part` + rename so an interrupted
 * write cannot leave half a marker behind. Only called once every selected file verified.
 */
internal fun writeInstalledReleaseMarker(dir: File, release: String) {
    dir.mkdirs()
    val marker = File(dir, UPSCALE_INSTALLED_RELEASE_MARKER)
    val part = File(dir, "$UPSCALE_INSTALLED_RELEASE_MARKER.part")
    try {
        part.writeText(release)
        if (!part.renameTo(marker)) {
            part.copyTo(marker, overwrite = true)
            part.delete()
        }
    } finally {
        if (part.exists()) part.delete()
    }
}

/** True when [manifest] is the one the app's pinned release describes. */
internal fun cachedManifestIsCurrent(manifest: UpscaleModelManifest, release: String): Boolean =
    manifest.release.isNotBlank() && manifest.release == release

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
 * The catalog id of the model file [fileName] belongs to, or `null` for a file the catalog does not
 * name. Lets a manifest that omits `id` still describe an installed model (and keeps
 * [ModelDownloader.installedIds] free of a manifest rewrite).
 */
internal fun catalogIdFor(fileName: String): String? =
    UpscaleModelCatalog.models.firstOrNull { it.fileName == fileName }?.id
