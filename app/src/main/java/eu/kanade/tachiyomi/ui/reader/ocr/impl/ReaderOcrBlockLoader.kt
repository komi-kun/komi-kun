// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// App-side block source for the reader OCR overlay/dictionary path.
//
// `:core:ocr` cannot depend on `:domain`/`:app` (contract §1.1), so the manager is domain-agnostic
// and callers resolve a chapter into its cache key. This loader does that resolution for the reader
// (page -> chapter -> manga/source -> `OcrCacheKey`) and keeps the resulting blocks in memory so the
// overlay and the long-press hit-test share one cache.
//
// The full source policy is implemented here (fix B1):
//   1. `.mokuro` sidecar when the source uses mokuro (AUTOMATIC/MOKURO) — sibling file, downloaded
//      chapter dir or CBZ sidecar, parsed through `MokuroChapterLoader`;
//   2. the persisted OCR cache (the pre-OCRed path) when the source persists results;
//   3. engine OCR over the page byte stream, persisted under the same cache key the queue worker
//      uses, so an on-demand page and a pre-OCRed chapter converge on one cache entry (fix B4).
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.cache.MokuroChapterLoader
import mihon.core.ocr.cache.OcrCacheKey
import mihon.core.ocr.cache.OcrManager
import mihon.core.ocr.model.OcrTextBlock
import mihon.core.ocr.mokuro.ImageFileInfo
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/** Identifies one page's OCR blocks: its chapter id plus the page index within the chapter. */
data class OcrPageKey(val chapterId: Long, val pageIndex: Int)

/** The [OcrPageKey] of this reader page. */
fun ReaderPage.ocrKey(): OcrPageKey = OcrPageKey(chapter.chapter.id, index)

/**
 * Resolves and caches the OCR blocks of the pages the reader is showing.
 *
 * Blocks come from the `.mokuro` sidecar and/or the persisted OCR cache ([OcrManager.cachedBlocks]);
 * when neither yields anything the engine runs over the page byte stream and the result is persisted
 * under the worker's cache key. The reader never touches the GPL `OcrTextBlock` model directly —
 * only this GPL-package helper and `ReaderOcrHostImpl` do.
 */
class ReaderOcrBlockLoader(
    private val ocrManager: OcrManager,
    private val getManga: GetManga,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val dictionaryPreferences: DictionaryPreferences,
    @AppCoroutineScope private val scope: CoroutineScope,
) {
    private val _blocks = MutableStateFlow<Map<OcrPageKey, List<OcrTextBlock>>>(emptyMap())
    val blocks: StateFlow<Map<OcrPageKey, List<OcrTextBlock>>> = _blocks.asStateFlow()

    private val inFlight = ConcurrentHashMap.newKeySet<OcrPageKey>()

    /** Earliest retry time (ns) for a page that could not be resolved yet; bounded retry rate. */
    private val retryAfter = ConcurrentHashMap<OcrPageKey, Long>()

    /** Starts loading the blocks of [pages] that are not cached yet. Main-thread callers only. */
    fun ensureLoaded(pages: List<ReaderPage>) {
        val now = System.nanoTime()
        pages.forEach { page ->
            val key = page.ocrKey()
            if (key.chapterId <= 0L || _blocks.value.containsKey(key)) return@forEach
            retryAfter[key]?.let { if (now < it) return@forEach }
            if (!inFlight.add(key)) return@forEach
            scope.launch(Dispatchers.IO) {
                val loaded = runCatching { load(page) }.getOrNull()
                inFlight.remove(key)
                if (loaded != null) {
                    // A completed resolution (even an empty one) is cached; null retries later.
                    retryAfter.remove(key)
                    _blocks.update { it + (key to loaded) }
                } else {
                    retryAfter[key] = System.nanoTime() + RETRY_INTERVAL_NANOS
                }
            }
        }
    }

    /** The blocks already loaded for [page] (empty until [ensureLoaded] finishes). */
    fun blocksFor(page: ReaderPage): List<OcrTextBlock> = _blocks.value[page.ocrKey()].orEmpty()

    /** Drops the in-memory cache (chapter change / viewer swap). */
    fun invalidate() {
        _blocks.update { emptyMap() }
        inFlight.clear()
        retryAfter.clear()
    }

    /**
     * Resolves one page through the source policy (03 §4, fix B1). Returns null only when the page
     * cannot be resolved *yet* (manga missing, page bytes unavailable); a completed resolution with
     * nothing to show returns an empty list so the loader stops retrying it.
     */
    private suspend fun load(page: ReaderPage): List<OcrTextBlock>? {
        val chapter = page.chapter.chapter
        val manga = getManga.await(chapter.mangaId) ?: return null
        val source = ocrManager.sourceFor(chapter.mangaId)
        val key = ocrManager.cacheKey(
            sourceId = manga.source,
            mangaId = chapter.mangaId,
            chapterId = chapter.id,
        )

        if (source.usesMokuro) {
            loadMokuroBlocks(page, manga)?.let { return it }
            // MOKURO-only: the sidecar *is* the cache and there is no engine fallback.
            if (!source.persistsOcrResults) return emptyList()
        }

        if (source.persistsOcrResults) {
            ocrManager.cachedBlocks(key, page.index, source)?.let { return it }
        }

        return runEngineOcr(page, key, source, chapter.mangaId, manga.source)
    }

    // ---------------------------------------------------------------- mokuro

    /** Loads the page's blocks from a `.mokuro` sidecar, or null when none applies. */
    private suspend fun loadMokuroBlocks(page: ReaderPage, manga: Manga): List<OcrTextBlock>? {
        val chapter = page.chapter.chapter
        val source = sourceManager.get(manga.source) ?: return null
        val chapterFile = downloadProvider.findChapterDir(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.ogTitle,
            source = source,
        ) ?: return null

        val sidecar = findMokuroSidecar(chapterFile) ?: return null
        val content = runCatching {
            sidecar.openInputStream().use { it.readBytes().decodeToString() }
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "ReaderOcr: failed to read mokuro sidecar ${sidecar.name}" }
            return null
        }

        val imageFiles = page.chapter.pages.orEmpty().mapNotNull { it.toImageFileInfo() }
        return MokuroChapterLoader.blocksForPage(content, imageFiles, page.index)
    }

    /**
     * Finds a chapter's `.mokuro` sidecar: inside a directory chapter, or beside a directory/CBZ
     * chapter (sibling file). Returned as a `UniFile` so it also works on storage-access-framework
     * locations where no plain path exists.
     */
    private fun findMokuroSidecar(chapterFile: UniFile): UniFile? {
        val parent = chapterFile.parentFile
        val presentNames = buildSet {
            if (chapterFile.isDirectory) {
                chapterFile.listFiles().orEmpty().mapNotNullTo(this) { it.name }
            }
            parent?.listFiles().orEmpty().mapNotNullTo(this) { it.name }
        }

        val chosen = MokuroChapterLoader.selectSidecar(chapterFile.name.orEmpty(), presentNames) ?: return null
        val inside = if (chapterFile.isDirectory) chapterFile.findFile(chosen) else null
        return inside ?: parent?.findFile(chosen)
    }

    private fun ReaderPage.toImageFileInfo(): ImageFileInfo? {
        val name = (imageUrl ?: url).substringAfterLast('/').substringBefore('?')
        if (name.isBlank()) return null
        return ImageFileInfo(
            name = name,
            relativePath = name,
            basename = name.substringBeforeLast('.'),
        )
    }

    // ---------------------------------------------------------------- engine

    /** Runs the engine over the page byte stream and persists the result (fix B1/B4). */
    private suspend fun runEngineOcr(
        page: ReaderPage,
        key: OcrCacheKey,
        source: ReaderOcrSource,
        mangaId: Long,
        sourceId: Long,
    ): List<OcrTextBlock>? {
        val bytes = pageBytes(page) ?: return null
        val language = resolveLanguage(mangaId, sourceId)
        return runCatching {
            ocrManager.recognizeAndCache(key, page.index, bytes, language, source)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "ReaderOcr: engine OCR failed for page ${page.index}" }
            emptyList()
        }
    }

    private fun pageBytes(page: ReaderPage): ByteArray? {
        val stream = page.stream ?: return null
        return runCatching { stream().use { it.readBytes() } }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun resolveLanguage(mangaId: Long, sourceId: Long): OcrLanguage {
        val profile = dictionaryPreferences.profileResolver.resolve(mangaId = mangaId, sourceId = sourceId)
        return OcrLanguage.entries.firstOrNull { it.bcp47.equals(profile.languageCode, ignoreCase = true) }
            ?: DEFAULT_LANGUAGE
    }

    private companion object {
        private val DEFAULT_LANGUAGE = OcrLanguage.JAPANESE

        /** Minimum gap between retries of a page that is not resolvable yet (bounds DB/disk churn). */
        private const val RETRY_INTERVAL_NANOS = 1_000_000_000L
    }
}
