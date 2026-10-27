// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The OCR cache key must follow the model bytes the engine would load, not only the selection.
// Every release publishes identically named model files, so a page OCR'd after selecting another
// release but before its download completed ran the *old* weights: keying on the selection's release
// tag stored that page under the new tag, where it stayed valid forever once the download finished.
// The variant therefore carries the engine's `modelBytesIdentity()` on top of the generation and the
// selection-derived signature.
// KUN <--
package mihon.core.ocr.cache

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mihon.core.ocr.DetModelVariant
import mihon.core.ocr.EngineLine
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.merge.OcrLineMerger
import org.junit.jupiter.api.Test
import tachiyomi.core.common.ocr.ReaderOcrSource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class OcrManagerCacheVariantTest {

    /** An engine whose model bytes the test moves, exactly like a completed model download. */
    private class FakeEngine(var identity: String?) : OcrEngine {
        override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> = emptyList()

        override fun modelBytesIdentity(): String? = identity
    }

    private val merger = mockk<OcrLineMerger>(relaxed = true)
    private val cacheManager = mockk<OcrCacheManager>(relaxed = true)
    private val store = mockk<OcrStore>(relaxed = true)

    private val key = OcrCacheKey(sourceId = SOURCE, mangaId = MANGA, chapterId = CHAPTER)

    /** Every variant the manager has asked the cache for, in order. */
    private val variants = mutableListOf<String?>()

    @Test
    fun `the cache variant follows the installed model identity`(): Unit = runBlocking {
        val engine = FakeEngine(identity = null)
        val preferences = OcrPreferences(InMemoryPreferenceStore())
        val manager = OcrManager(
            engine = engine,
            merger = merger,
            cacheManager = cacheManager,
            store = store,
            preferences = preferences,
            dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        coEvery { cacheManager.load(any(), any(), any()) } answers {
            variants += thirdArg<String?>()
            null
        }

        val noIdentity = variantOf(manager)
        // Nothing installed (or unknown): the key keeps its generation + selection form, with no
        // dangling separator and no literal "null" claiming to be an identity.
        noIdentity shouldContain "ocr-manga-v4t15b25u140r35"
        noIdentity shouldNotContain "null"

        // Release A's weights are the ones on disk: the key names them, after the selection part.
        engine.identity = "release-a|det:100:200"
        val withA = variantOf(manager)
        withA shouldBe "$noIdentity-release-a|det:100:200"

        // The user now selects the SFX release and OCRs before its download completes. The selection
        // part of the key follows the selection, but the identity part still names the installed
        // weights, so nothing is filed as if B's bytes had produced it.
        preferences.detModelVariant.set(DetModelVariant.SFX.name)
        val sfxBeforeDownload = variantOf(manager)
        sfxBeforeDownload shouldContain "ocr-manga-v3"
        sfxBeforeDownload shouldEndWith "-release-a|det:100:200"
        (sfxBeforeDownload == withA) shouldBe false

        // The download lands: the bytes change, so the key changes again and the pages re-run.
        engine.identity = "release-b|det:300:400"
        val sfxAfterDownload = variantOf(manager)
        sfxAfterDownload shouldEndWith "-release-b|det:300:400"
        (sfxAfterDownload == sfxBeforeDownload) shouldBe false

        variants.size shouldBe 4
    }

    /** Runs one cache lookup and returns the variant the manager asked the cache for. */
    private suspend fun variantOf(manager: OcrManager): String {
        manager.cachedBlocks(key, pageIndex = 0, source = ReaderOcrSource.PADDLE)
        return variants.lastOrNull() ?: error("the manager never reached the cache")
    }

    private companion object {
        private const val SOURCE = 1L
        private const val MANGA = 2L
        private const val CHAPTER = 3L
    }
}
