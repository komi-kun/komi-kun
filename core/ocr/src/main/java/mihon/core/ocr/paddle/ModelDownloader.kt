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
import mihon.core.ocr.ModelRelease
import mihon.core.ocr.OcrPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

// The release tag and download root live on [ModelRelease] (detector × recogniser): every release
// publishes identically named files, so a cached `manifest.json` or a leftover model file from any
// other release must not be mistaken for the selected one.

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

/**
 * The release manifest (`manifest.json`) describing every OCR model artifact.
 *
 * [baseUrl] deliberately defaults to `""`, **not** a release URL: a cached manifest that omits the
 * field must not be mistaken for one fetched from the pinned release (F4 —
 * [cachedManifestIsCurrent] treats a blank `baseUrl` as "no identifier", not as the pinned one).
 */
@Serializable
data class OcrModelManifest(
    val release: String = "",
    val baseUrl: String = "",
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
    private val preferences: OcrPreferences,
    // KUN -->
    /** Explicit dictionary/OCR opt-in: no network or model-file work happens while it is off. */
    private val dictionaryOcr: DictionaryOcrPreferences,
    // KUN <--
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Coalesces redundant [triggerPaddleDownload] calls into one in-flight run. */
    private val downloadGate = SingleFlightGate()

    /** Serialises a [downloadModels] run against an [importFromUri] run (both write [modelsDir]). */
    private val runLock = Mutex()

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    // KUN -->
    /** True while the user has not opted into the dictionary/OCR features. */
    private fun ocrDisabled(): Boolean = !dictionaryOcr.isEnabled()

    /**
     * Failure returned by the [Result]-returning entry points while the feature is off. It is an
     * explicit error (never a fake success) so callers/UI cannot mistake "not allowed" for "done".
     */
    private fun ocrDisabledFailure(): IllegalStateException = IllegalStateException(
        "Dictionary/OCR is disabled: enable the immersion features before downloading OCR models",
    )
    // KUN <--

    /** Directory the engine reads its models from (`filesDir/paddle_ocr`). */
    val modelsDir: File get() = File(appContext.filesDir, PADDLE_DIR)

    // KUN -->
    // The selection is two user-visible axes (detector × recogniser) that resolve to one release.
    // Every release publishes the same file names — the detector name is *shared* by all four — so
    // the release tag, not the file set, is what the cache must be keyed on.
    private val selection: ModelRelease get() = preferences.resolveModelRelease()
    private val requiredModels: List<String>
        get() = listOf(selection.det.detModel, selection.rec.recModel, DICT_TXT)
    // KUN <--

    /**
     * Cheap, synchronous readiness gate for the settings screen (see `SettingsOcrScreen`): the last
     * completed install recorded **the release this build's selection resolves to**
     * ([installedReleaseMatches]), every required model file exists and is non-empty, and — when a
     * cached manifest is present — each file matches that manifest's published **size** (no file
     * bytes are hashed here).
     *
     * It does read and JSON-parse the small cached `manifest.json`, so it is **not** a pure `stat`,
     * but it deliberately does **not** SHA-256 the ~31 MB of model bytes — that is what keeps it
     * main-thread safe (the previous full-hash implementation was not). The full size **and**
     * SHA-256 verification runs inside [downloadModels], off the main thread; [triggerPaddleDownload]
     * always enters that coroutine (once), so a same-size but corrupt model is still detected and
     * re-fetched.
     *
     * **Why the installed-release marker is the first check.** Every release ships identically named
     * files, so a file that is merely *present* says nothing about which variant it holds — in
     * particular `ppocrv6_small_det.onnx` is the dialogue detector in `ocr-manga-v4` and the SFX
     * detector in `ocr-manga-v3`. A size-only check cannot see that either (a file matches *its own*
     * release's manifest perfectly). The marker is written only after a run installed **and
     * hash-verified** every file of a release, so a stale marker — or none at all, which is every
     * pre-marker install — reports "not downloaded". That is what makes an in-place user re-download
     * the dialogue detector instead of silently keeping the SFX one an older build gave them, and
     * what invalidates a stale detector when the user switches variant in Settings.
     */
    val isPaddleDownloaded: Boolean
        get() {
            // KUN -->
            val target = selection
            if (!installedReleaseMatches(modelsDir, target.release)) return false
            if (!requiredModelFiles().all { it.isFile && it.length() > 0L }) return false
            // A cached manifest from another release (or one that does not parse) cannot vouch for
            // these files. The marker already proves the bytes, so a manually deleted manifest is
            // not treated as "needs a download" — it only costs the cheap size cross-check.
            val manifest = loadCachedManifest() ?: return true
            if (!cachedManifestIsCurrent(manifest, target.release, target.baseUrl)) return false
            // KUN <--
            return requiredModelFiles().all { file ->
                val entry = manifest.files.firstOrNull { it.name == file.name }
                entry != null && modelFileHasExpectedSize(file, entry)
            }
        }

    /** The model files the engine needs at runtime (the `.so`s ship in the APK). Backs [isPaddleDownloaded]. */
    fun requiredModelFiles(): List<File> = requiredModels.map { File(modelsDir, it) }

    // KUN -->
    // ------------------------------------------------------------------ what is actually installed
    // The selection is a *request*; these three report the *disk*. A caller that wants to know
    // whether the selected weights are really the ones the engine will load must use them (see
    // `PaddleOcrEngine`, which keys its reload on `installedModelIdentity` rather than on the
    // selection, and `OcrRuntimePolicy.shouldMigrateModels`, the launch-time upgrade migration).

    /** Release the last completed install recorded, or null when nothing is recorded (pre-marker). */
    val installedRelease: String? get() = installedReleaseMarker(modelsDir)

    /** The release the current selection resolves to — what a migration must install. */
    val selectedRelease: String get() = selection.release

    /**
     * True when at least one model file of the selection exists (cheap `stat`, no hashing). Used only
     * to tell a legacy install from a fresh one: bytes present with no marker means "some earlier
     * build installed models", empty means "never downloaded".
     */
    val hasAnyInstalledModelFiles: Boolean get() = requiredModelFiles().any { it.isFile && it.length() > 0L }
    // KUN <--

    /** The manifest cached by the last [fetchManifest], or null when none is on disk. */
    private fun loadCachedManifest(): OcrModelManifest? {
        val file = File(modelsDir, MANIFEST_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<OcrModelManifest>(file.readText()) }.getOrNull()
    }

    /**
     * Runtime, user-opt-in entry point (the settings CTA **and** the status-row retry). Non-suspend
     * and main-thread safe: it only schedules the work. The coroutine re-verifies every model — size
     * **and** SHA-256 — off the main thread and (re-)downloads anything missing or corrupt, so
     * calling it on each Paddle selection is cheap and self-healing rather than a one-shot gate.
     *
     * **Single-flight, then converge.** A run already in flight is never started twice: a trigger
     * arriving while a run is active is coalesced into it, so two runs can never delete each other's
     * `<name>.part` scratch files or race on `manifest.json`. But a coalesced trigger is not simply
     * dropped, because the running pass snapshotted *its* target: flipping DIALOGUE -> SFX ->
     * DIALOGUE used to leave the SFX install in place with the dialogue selection, and nothing
     * scheduled the dialogue release again. So after every successful pass the run re-reads the
     * selection and keeps going until the **currently selected** release is the installed one
     * (bounded by [MAX_CONVERGE_PASSES], so two repeatedly flipped selections end in an explicit
     * failure instead of a loop). [SingleFlightGate.runExclusive] releases the gate in a `finally`,
     * so a **failed** pass is immediately retryable and is reported via [state].
     */
    fun triggerPaddleDownload() {
        // KUN -->
        // Inert until the user opts in: no coroutine, no network, no model files.
        if (ocrDisabled()) return
        // KUN <--
        scope.launch {
            var passes = 0
            while (true) {
                when (val result = downloadGate.runExclusive { runLock.withLock { downloadModels() } }) {
                    null -> {
                        // Another run owns the gate; its own loop re-checks the selection when it
                        // finishes, so this window is not lost.
                        logcat(LogPriority.INFO) {
                            "ModelDownloader: download already in progress; ignoring duplicate trigger"
                        }
                        return@launch
                    }
                    else -> result.exceptionOrNull()?.let {
                        val message = it.message ?: "Unknown error"
                        _state.value = ModelDownloadState.Failed(message)
                        xLogE("ModelDownloader: model download failed: $message", it)
                        return@launch
                    }
                }
                passes++
                // The selection may have changed while the pass was in flight (the user flipping the
                // variant, or one of our triggers being coalesced away): the pass installed *its*
                // target, so only now can we read the truth off disk.
                if (installedReleaseMatches(modelsDir, selection.release)) {
                    _state.value = ModelDownloadState.Completed
                    return@launch
                }
                if (passes >= MAX_CONVERGE_PASSES) {
                    val message = "the selected model release (${selection.release}) is not installed " +
                        "after $passes download passes"
                    _state.value = ModelDownloadState.Failed(message)
                    xLogE("ModelDownloader: $message")
                    return@launch
                }
                logcat(LogPriority.INFO) {
                    "ModelDownloader: selection changed during the run; downloading ${selection.release}"
                }
            }
        }
    }

    /**
     * Fetches and parses the release manifest (and caches it beside the models).
     *
     * [target] defaults to the current selection; a caller that already snapshotted one (so a
     * preference flip mid-run cannot mix model sets) passes it in.
     */
    suspend fun fetchManifest(target: ModelRelease = preferences.resolveModelRelease()): Result<OcrModelManifest> {
        // KUN -->
        if (ocrDisabled()) return Result.failure(ocrDisabledFailure())
        // KUN <--
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("${target.baseUrl}/$MANIFEST_NAME").build()
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
        // KUN -->
        // Nothing at all happens while the feature is off — not even `.part`/stale-file cleanup.
        if (ocrDisabled()) return@withContext Result.failure(ocrDisabledFailure())
        // KUN <--
        // Snapshot the selection: switching the variant mid-download must not mix model sets.
        val target = preferences.resolveModelRelease()
        val targetRelease = target.release
        val targetBaseUrl = target.baseUrl
        // The detector file name is the same in every release; the *release* is what selects its
        // bytes, so both come from the resolved release rather than from a shared constant.
        val required = listOf(target.det.detModel, target.rec.recModel, DICT_TXT)

        // Drop any `.part` scratch files an interrupted earlier run left behind.
        cleanupPartFiles()

        // Offline-friendly fast path: the last completed install recorded the release we still
        // select AND the cached manifest was fetched from that release AND fully accounts for every
        // required file, and every file verifies (size + SHA-256) — nothing to download. The marker
        // is what stops a leftover model set from pinning an old variant forever when a future
        // release reuses the same file names (the detector name is shared by all of them).
        loadCachedManifest()?.let { cached ->
            if (installedReleaseMatches(modelsDir, targetRelease) &&
                cachedManifestIsUsable(cached, targetRelease, targetBaseUrl, modelsDir, required)
            ) {
                cleanupStaleModels()
                return@withContext Result.success(Unit)
            }
        }

        val manifest = fetchManifest(target).getOrElse { return@withContext Result.failure(it) }
        val entries = mutableListOf<Pair<OcrModelFile, String>>()
        for (name in required) {
            val entry = manifest.files.firstOrNull { it.name == name }
                ?: return@withContext Result.failure(IllegalStateException("Manifest is missing $name"))
            if (entry.sha256.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Manifest is missing a SHA-256 for $name"))
            }
            entries += entry to "$targetBaseUrl/${entry.path ?: "models/${entry.name}"}"
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

        // Only after every current model landed: prune what an earlier release installed, then
        // record the release this directory now holds (all files hash-verified above), which is what
        // `isPaddleDownloaded` and the fast path trust next time.
        cleanupStaleModels()
        writeInstalledReleaseMarker(modelsDir, targetRelease)
        Result.success(Unit)
    }

    /**
     * Imports an archive the user picked. Extracts it into a scratch dir, verifies the staged model
     * files against the manifest **before** anything is copied into [modelsDir], and only then
     * installs them — so a bad or partial archive can never clobber a working install.
     *
     * **Intentionally-unwired API (F1).** `contract §3.4` schedules **no import UI** (the settings
     * screen only exposes the download CTA), so nothing in `:app` calls this today. It is kept — and
     * kept unit-tested — as a ready, checksum-gated entry point for a future "import a model
     * archive" CTA; [runLock] already serialises it against [triggerPaddleDownload], so wiring it
     * later needs no concurrency change. It is not dead code: do not delete it.
     */
    suspend fun importFromUri(uri: Uri): Result<Unit> = runLock.withLock {
        withContext(Dispatchers.IO) {
            // KUN -->
            if (ocrDisabled()) return@withContext Result.failure(ocrDisabledFailure())
            // KUN <--
            runCatching {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected file")
                // The release the imported archive must correspond to; resolved once so a switch
                // midway cannot make us vouch for the wrong set.
                val target = preferences.resolveModelRelease()
                // `use` spans the whole resolution + staging block, so the stream is closed even when
                // the offline path bails out (no cached manifest) before extraction (F5).
                input.use { stream ->
                    // Prefer the live manifest; fall back to the one cached beside the models so
                    // import still verifies (and is not a silent no-op) offline.
                    val manifest = fetchManifest(target).getOrElse {
                        loadCachedManifest() ?: error("No model manifest available to verify the archive")
                    }
                    // The archive is untrusted. Extract it into a throwaway scratch dir, verify it
                    // there, then copy ONLY the model files the engine loads into modelsDir — never an
                    // arbitrary path, in particular not a planted `lib/<abi>/*.so` that
                    // `PaddleOcrEngine.loadNativeLibraries` would `System.load`. The scratch dir is
                    // removed whatever happens.
                    val scratch = File(appContext.cacheDir, "ocr-import-${System.nanoTime()}")
                    scratch.mkdirs()
                    try {
                        extractZipInto(scratch, stream)
                        val required = requiredModels
                        verifyStagedModels(scratch, manifest, required)
                        installRequiredModels(scratch, modelsDir, required)
                        // Only vouch for the selection when the manifest we verified against *is*
                        // that release's: the offline fallback can hold an older cached manifest, and
                        // a wrong marker would make the engine trust the wrong bytes.
                        if (cachedManifestIsCurrent(manifest, target.release, target.baseUrl)) {
                            writeInstalledReleaseMarker(modelsDir, target.release)
                        }
                    } finally {
                        scratch.deleteRecursively()
                    }
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
        val required = requiredModels
        for (name in LEGACY_MODELS) {
            if (name in required) continue
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
        val removed = removeOwnedPartFiles(modelsDir, requiredModels)
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

        /**
         * Upper bound on the "the selection changed while the pass was in flight" loop. Two passes
         * converge for every single flip; the third only matters if the user keeps flipping, and at
         * that point an explicit failure (retryable from the status row) is better than spinning.
         */
        private const val MAX_CONVERGE_PASSES = 3

        // PP-OCRv6 small: ONNX graphs (the shipped backend is ONNX Runtime). The detector and
        // recogniser file names come from the selection ([ModelRelease]); the dictionary is the same
        // file in every release.
        private const val DICT_TXT = "ppocrv6_small_dict.txt"

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

/**
 * Name of the marker recording which model release was fully installed and hash-verified into the
 * engine directory. Kept beside the models, never downloaded.
 */
internal const val INSTALLED_RELEASE_MARKER = "installed-release.txt"

/**
 * The release tag recorded by the last **completed** model install in [dir], or null when nothing is
 * recorded (a pre-marker install, or a run that never finished).
 *
 * Every release publishes the same file names — in particular the shared detector
 * `ppocrv6_small_det.onnx`, whose bytes are the only thing that differs between the dialogue and
 * SFX variants — so the presence of a model file proves nothing about *which* variant it holds.
 * This marker is what makes "the selected release is the one on disk" provable without hashing.
 */
internal fun installedReleaseMarker(dir: File): String? {
    val file = File(dir, INSTALLED_RELEASE_MARKER)
    if (!file.isFile) return null
    return file.readText().trim().ifEmpty { null }
}

/** True when [dir] holds a completed install of [release] (see [installedReleaseMarker]). */
internal fun installedReleaseMatches(dir: File, release: String): Boolean =
    installedReleaseMarker(dir) == release

/**
 * Prefix of an identity for a directory whose release is **not** recorded (every pre-marker
 * install, and a hand-staged cache). Such files are usable but of unknown identity, so nothing may
 * claim they are the selected release.
 */
internal const val UNKNOWN_INSTALL = "unknown-install"

/**
 * Cheap identity of the model files in [dir], built from each file's length and last-modified time
 * (`stat` only — never hashes the ~31 MB of weights). Renaming a freshly written `<name>.part` into
 * place updates both, so a **replacement is visible even when the file name and the marker are
 * unchanged** — which is exactly the case that used to leave the old detector in native memory.
 */
internal fun modelFileFingerprint(dir: File, names: Collection<String>): String =
    names.joinToString("|") { name ->
        val file = File(dir, name)
        if (file.isFile) "$name:${file.length()}:${file.lastModified()}" else "$name:absent"
    }

/**
 * What the engine may claim about the weights it loads from [dir]: the release the downloader
 * recorded, or [UNKNOWN_INSTALL] when nothing is recorded, **plus** the files' own fingerprint.
 *
 * This deliberately does not consult the selection. Every release publishes identically named files
 * (in particular the shared `ppocrv6_small_det.onnx`), so an identity derived from the selection says
 * nothing about the bytes on disk: it made an upgrading install load the SFX detector and record the
 * dialogue release, and made a later replacement invisible to the reload check. A hand-staged cache
 * with no marker therefore reports [UNKNOWN_INSTALL] and is allowed exactly one reload when a
 * completed download writes the marker (the fingerprint part changes too, so one comparison catches
 * both). Whatever the reason, an identity that differs from the one an init recorded forces a
 * reload — see `PaddleOcrEngine.ensureModelVariant`.
 */
internal fun installedModelIdentity(dir: File, names: Collection<String>): String =
    "${installedReleaseMarker(dir) ?: UNKNOWN_INSTALL}|${modelFileFingerprint(dir, names)}"

/**
 * Records [release] as the installed model set. Written only after every required file of that
 * release verified (size + SHA-256), and staged through `<name>.part` + rename so an interrupted
 * write can never leave a half marker behind.
 */
internal fun writeInstalledReleaseMarker(dir: File, release: String) {
    dir.mkdirs()
    val marker = File(dir, INSTALLED_RELEASE_MARKER)
    val temp = File(dir, "$INSTALLED_RELEASE_MARKER.part")
    try {
        temp.writeText(release)
        if (!temp.renameTo(marker)) {
            temp.copyTo(marker, overwrite = true)
            temp.delete()
        }
    } finally {
        if (temp.exists()) temp.delete()
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
 *
 * Each file is copied to `<name>.part` and then renamed into place, so a mid-copy `IOException`
 * leaves the previous good model untouched instead of a truncated one (F6). The `<name>.part`
 * scratch name is the same one [removeOwnedPartFiles] cleans, so an interrupted copy is pruned later.
 */
internal fun installRequiredModels(from: File, to: File, names: Collection<String>): List<String> {
    if (!from.isDirectory) return emptyList()
    to.mkdirs()
    val installed = mutableListOf<String>()
    for (name in names) {
        val source = findModelFile(from, name) ?: continue
        val target = File(to, name)
        val temp = File(to, "$name.part")
        try {
            source.copyTo(temp, overwrite = true)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
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
 * Runs [body] under this gate and returns its value, or `null` when a run was already in flight
 * (coalesced). The gate is **always** released — including when [body] throws — so a failed run is
 * immediately retryable (F7). Extracted from [ModelDownloader.triggerPaddleDownload] so the
 * release-on-failure contract is unit-testable without an Android `Context`.
 */
internal suspend fun <T> SingleFlightGate.runExclusive(body: suspend () -> T): T? =
    if (!tryAcquire()) {
        null
    } else {
        try {
            body()
        } finally {
            release()
        }
    }

/**
 * True when [manifest] was fetched from the release the app still pins. The release tag is the
 * **primary** identifier and must match; [OcrModelManifest.baseUrl] is only a fallback for a manifest
 * that carries no release at all, and must be explicitly present (non-blank) and equal.
 *
 * F4: [OcrModelManifest.baseUrl] defaults to `""` precisely so a manifest that **omits** `baseUrl`
 * no longer trivially matches the pinned default — previously `manifest.baseUrl == baseUrl` was
 * always true for such a manifest, so a cached manifest from any release looked current and could
 * pin a stale model set forever. A manifest whose release differs is now refused even when its
 * `baseUrl` happens to match.
 */
internal fun cachedManifestIsCurrent(manifest: OcrModelManifest, release: String, baseUrl: String): Boolean =
    if (manifest.release.isNotBlank()) {
        manifest.release == release
    } else {
        manifest.baseUrl.isNotBlank() && manifest.baseUrl == baseUrl
    }

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
