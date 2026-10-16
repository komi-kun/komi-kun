// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// App-side block source for the reader OCR overlay/dictionary path.
//
// `:core:ocr` cannot depend on `:domain`/`:app` (contract §1.1), so the manager is domain-agnostic
// and callers resolve a chapter into its cache key. This loader does that resolution for the reader
// (page -> chapter -> manga/source -> `OcrCacheKey`) and keeps the resulting blocks in memory so the
// overlay and the long-press hit-test share one cache. On-demand engine OCR off the page byte stream
// and `.mokuro` sidecar resolution are follow-ups; this wires the persisted-cache path first.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import mihon.core.ocr.cache.OcrCacheKey
import mihon.core.ocr.cache.OcrManager
import mihon.core.ocr.model.OcrTextBlock
import tachiyomi.domain.manga.interactor.GetManga

/** Identifies one page's OCR blocks: its chapter id plus the page index within the chapter. */
data class OcrPageKey(val chapterId: Long, val pageIndex: Int)

/** The [OcrPageKey] of this reader page. */
fun ReaderPage.ocrKey(): OcrPageKey = OcrPageKey(chapter.chapter.id, index)

/**
 * Resolves and caches the OCR blocks of the pages the reader is showing.
 *
 * Blocks come from the persisted OCR cache ([OcrManager.cachedBlocks]); the reader never touches the
 * GPL `OcrTextBlock` model directly — only this GPL-package helper and `ReaderOcrHostImpl` do.
 */
class ReaderOcrBlockLoader(
    private val ocrManager: OcrManager,
    private val getManga: GetManga,
    @AppCoroutineScope private val scope: CoroutineScope,
) {
    private val _blocks = MutableStateFlow<Map<OcrPageKey, List<OcrTextBlock>>>(emptyMap())
    val blocks: StateFlow<Map<OcrPageKey, List<OcrTextBlock>>> = _blocks.asStateFlow()

    private val inFlight = mutableSetOf<OcrPageKey>()

    /** Starts loading the blocks of [pages] that are not cached yet. Main-thread callers only. */
    fun ensureLoaded(pages: List<ReaderPage>) {
        pages.forEach { page ->
            val key = page.ocrKey()
            if (key.chapterId <= 0L || _blocks.value.containsKey(key) || !inFlight.add(key)) return@forEach
            scope.launch(Dispatchers.IO) {
                val loaded = runCatching { load(page) }.getOrNull()
                inFlight.remove(key)
                if (loaded != null) _blocks.update { it + (key to loaded) }
            }
        }
    }

    /** The blocks already loaded for [page] (empty until [ensureLoaded] finishes). */
    fun blocksFor(page: ReaderPage): List<OcrTextBlock> = _blocks.value[page.ocrKey()].orEmpty()

    /** Drops the in-memory cache (chapter change / viewer swap). */
    fun invalidate() {
        _blocks.update { emptyMap() }
        inFlight.clear()
    }

    private suspend fun load(page: ReaderPage): List<OcrTextBlock>? {
        val chapter = page.chapter.chapter
        val mangaId = chapter.mangaId
        val sourceId = getManga.await(mangaId)?.source ?: return null
        val source = ocrManager.sourceFor(mangaId)
        // MOKURO does not persist engine results (its sidecar is the cache); sidecar loading is a
        // follow-up, so there is nothing to load from the persistent cache for it yet.
        if (!source.persistsOcrResults) return null
        val key = OcrCacheKey(sourceId = sourceId, mangaId = mangaId, chapterId = chapter.id)
        return ocrManager.cachedBlocks(key, page.index, source)
    }
}
