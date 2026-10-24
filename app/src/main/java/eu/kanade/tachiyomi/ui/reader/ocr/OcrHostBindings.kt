// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.reader.ocr

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.ocr.impl.ReaderOcrBlockLoader
import kotlinx.coroutines.CoroutineScope
import mihon.core.metro.AppCoroutineScope
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.cache.OcrManager
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager

/**
 * App-side Metro bindings for the OCR host seam (contract §3.6). GPL (not part of the Apache §1.3
 * seam file set).
 *
 * Created empty by W1 and registered in `AppGraph.bindingContainers`; W3 fills it with the app-level
 * OCR services the reader host needs. The `ReaderOcrHost` binding itself is contributed by
 * `@ContributesBinding` on `ReaderOcrHostImpl` (contract §3.6b) — no entry is needed here. Kept out
 * of `@ContributesTo` so the app registers it exactly once.
 */
@BindingContainer
object OcrHostBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideReaderOcrBlockLoader(
        ocrManager: OcrManager,
        engine: OcrEngine,
        getManga: GetManga,
        downloadProvider: DownloadProvider,
        sourceManager: SourceManager,
        dictionaryPreferences: DictionaryPreferences,
        // KUN --> the combined dictionary/OCR opt-in gates the loader's work entry points.
        dictionaryOcrPreferences: DictionaryOcrPreferences,
        // KUN <--
        @AppCoroutineScope scope: CoroutineScope,
    ): ReaderOcrBlockLoader = ReaderOcrBlockLoader(
        ocrManager = ocrManager,
        engine = engine,
        getManga = getManga,
        downloadProvider = downloadProvider,
        sourceManager = sourceManager,
        dictionaryPreferences = dictionaryPreferences,
        scope = scope,
        // KUN -->
        dictionaryOcrPreferences = dictionaryOcrPreferences,
        // KUN <--
    )
}
