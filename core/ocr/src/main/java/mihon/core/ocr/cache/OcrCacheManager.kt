// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `app/src/main/java/eu/kanade/tachiyomi/data/ocr/OcrCacheManager.kt` @ f85c063.
// Adapted for komi-kun: the module cannot depend on `:data`/`:domain` (contract §1.1), so the
// download-directory / `UniFile` resolution is gone. Persistence works on plain `java.io.File`
// targets (a directory chapter's `.ocr_cache.json`, a CBZ's `<name>.cbz.ocr.json` sidecar, or the
// app-internal `ocr_cache/{sourceId}/{mangaId}/{chapterId}.json`), and the app layer resolves which
// target a chapter maps to. The JSON schema (pages + cache variants) is unchanged.
// KUN <--
package mihon.core.ocr.cache

import android.content.Context
import android.text.format.Formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.ocr.OcrPageData
import mihon.core.ocr.model.OcrTextBlock
import mihon.core.ocr.toBlockData
import mihon.core.ocr.toTextBlock
import tachiyomi.core.common.util.system.logcat
import java.io.File

/** Identifies a chapter's OCR cache location in app-internal storage. */
data class OcrCacheKey(
    val sourceId: Long,
    val mangaId: Long,
    val chapterId: Long,
)

/**
 * Persists OCR results keyed by chapter + page.
 *
 * Two storage shapes, both JSON:
 * - a directory chapter: `<chapter dir>/.ocr_cache.json`;
 * - a CBZ chapter: `<chapter.cbz>.ocr.json` sidecar;
 * - online/unresolved chapters: app-internal `ocr_cache/{sourceId}/{mangaId}/{chapterId}.json`.
 *
 * A chapter file holds `OcrChapterData` (page index → [OcrPageData]), with optional per-variant
 * maps for the different page transforms (split/merge/crop) the reader applies.
 */
class OcrCacheManager(
    private val context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()

    // ---------------------------------------------------------------- internal (online) storage

    /** The app-internal cache file for [key] (`filesDir/ocr_cache/.../{chapterId}.json`). */
    fun internalCacheFile(key: OcrCacheKey): File = File(mangaDir(key.sourceId, key.mangaId), "${key.chapterId}.json")

    /** The sidecar file for a CBZ chapter (`<name>.cbz` → `<name>.cbz.ocr.json`). */
    fun sidecarFile(cbzFile: File): File = File(cbzFile.parentFile, "${cbzFile.name}$SIDECAR_SUFFIX")

    /** The OCR cache file inside a directory-based chapter. */
    fun directoryCacheFile(chapterDir: File): File = File(chapterDir, CACHE_FILE_NAME)

    suspend fun save(
        key: OcrCacheKey,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
        variant: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock { saveToFile(internalCacheFile(key), pageIndex, blocks, language, variant) }
    }

    suspend fun load(
        key: OcrCacheKey,
        pageIndex: Int,
        variant: String? = null,
    ): List<OcrTextBlock>? = withContext(Dispatchers.IO) {
        mutex.withLock { loadFromBlocking(internalCacheFile(key), pageIndex, variant) }
    }

    suspend fun hasData(key: OcrCacheKey): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { hasDataInBlocking(internalCacheFile(key)) }
    }

    suspend fun deleteChapter(key: OcrCacheKey) = withContext(Dispatchers.IO) {
        mutex.withLock { internalCacheFile(key).delete() }
    }

    /** Deletes every cached page for a manga (all its chapters). */
    suspend fun deleteManga(sourceId: Long, mangaId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock { mangaDir(sourceId, mangaId).deleteRecursively() }
    }

    /** Human-readable total size of the app-internal OCR cache. */
    suspend fun readableSize(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            var total = 0L
            internalDir().walkTopDown().forEach { file -> if (file.isFile) total += file.length() }
            Formatter.formatFileSize(context, total)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { internalDir().deleteRecursively() }
    }

    // ---------------------------------------------------------------- file-based core

    /** Saves one page's blocks into [file] (creating it if needed). */
    suspend fun saveTo(
        file: File,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
        variant: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock { saveToFile(file, pageIndex, blocks, language, variant) }
    }

    /** Loads one page's blocks from [file], or null when absent/unreadable. */
    suspend fun loadFrom(
        file: File,
        pageIndex: Int,
        variant: String? = null,
    ): List<OcrTextBlock>? = withContext(Dispatchers.IO) {
        mutex.withLock { loadFromBlocking(file, pageIndex, variant) }
    }

    /** True when [file] holds at least one cached page. */
    suspend fun hasDataIn(file: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { hasDataInBlocking(file) }
    }

    suspend fun deleteIn(file: File) = withContext(Dispatchers.IO) {
        mutex.withLock { file.delete() }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Non-locking write used by both [save] and [saveTo]. Callers must hold [mutex]: the coroutine
     * [Mutex] is **not** reentrant, so the keyed entry points must not delegate to the locked
     * [saveTo]/[loadFrom]/[hasDataIn] wrappers (that deadlocked the reader before the OCR cache
     * existed, so no OCR ever ran — see `ort-v6-migration.md` §6/§9).
     */
    private fun saveToFile(
        file: File,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
        variant: String?,
    ) {
        val data = readChapterData(file).withPage(
            pageIndex,
            OcrPageData(
                blocks = blocks.map { it.toBlockData() },
                language = language,
                version = CURRENT_VERSION,
            ),
            variant,
        )
        writeChapterData(file, data)
    }

    /** Non-locking read used by both [load] and [loadFrom]; callers must hold [mutex]. */
    private fun loadFromBlocking(file: File, pageIndex: Int, variant: String?): List<OcrTextBlock>? {
        if (!file.isFile) return null
        return try {
            readChapterData(file).page(pageIndex, variant)?.blocks?.map { it.toTextBlock() }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OcrCache: failed to read ${file.name}" }
            null
        }
    }

    /** Non-locking probe used by both [hasData] and [hasDataIn]; callers must hold [mutex]. */
    private fun hasDataInBlocking(file: File): Boolean {
        val data = readChapterData(file)
        return data.pages.isNotEmpty() || data.variants.isNotEmpty()
    }

    private fun readChapterData(file: File): OcrChapterData {
        if (!file.isFile) return OcrChapterData()
        return try {
            val content = file.readText()
            if (content.isBlank()) OcrChapterData() else json.decodeFromString(content)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OcrCache: corrupt cache ${file.name}, resetting" }
            OcrChapterData()
        }
    }

    private fun writeChapterData(file: File, data: OcrChapterData) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrCache: failed to write ${file.name}" }
        }
    }

    private fun internalDir(): File = File(context.filesDir, INTERNAL_OCR_DIR)

    private fun mangaDir(sourceId: Long, mangaId: Long): File =
        File(File(internalDir(), sourceId.toString()), mangaId.toString())

    companion object {
        private const val CACHE_FILE_NAME = ".ocr_cache.json"
        private const val SIDECAR_SUFFIX = ".ocr.json"
        private const val INTERNAL_OCR_DIR = "ocr_cache"
        private const val CURRENT_VERSION = 2
    }
}

/** All cached OCR data for one chapter. */
@Serializable
data class OcrChapterData(
    val pages: Map<Int, OcrPageData> = emptyMap(),
    val variants: Map<String, Map<Int, OcrPageData>> = emptyMap(),
    val version: Int = 2,
)

private fun OcrChapterData.page(pageIndex: Int, cacheVariant: String?): OcrPageData? =
    if (cacheVariant == null) pages[pageIndex] else variants[cacheVariant]?.get(pageIndex)

private fun OcrChapterData.withPage(
    pageIndex: Int,
    pageData: OcrPageData,
    cacheVariant: String?,
): OcrChapterData {
    if (cacheVariant == null) return copy(pages = pages + (pageIndex to pageData))
    val variantPages = variants[cacheVariant].orEmpty() + (pageIndex to pageData)
    return copy(variants = variants + (cacheVariant to variantPages))
}
