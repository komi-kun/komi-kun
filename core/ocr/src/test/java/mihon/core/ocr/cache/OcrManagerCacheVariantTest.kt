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

import android.graphics.BitmapFactory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mihon.core.ocr.DetModelVariant
import mihon.core.ocr.EngineLine
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.WritingDirection
import mihon.core.ocr.merge.OcrLineMerger
import mihon.core.ocr.model.OcrTextBlock
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

    /**
     * The on-demand save path must pin the variant **before** recognising, exactly like the chapter
     * runner. A model download that lands while the page is being inferred changes
     * `modelBytesIdentity()`, and a variant read afterwards would file a page the **old** weights
     * produced under the new identity — permanently, since that identity is then the valid one.
     */
    @Test
    fun `the save path keys the page with the identity the recognition started with`(): Unit = runBlocking {
        // The download lands *during* inference: the identity changes between the two reads.
        val engine = object : OcrEngine {
            var identity: String? = "release-a|det:100:200"

            override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
                identity = "release-b|det:300:400"
                return listOf(line)
            }

            override fun modelBytesIdentity(): String? = identity
        }
        val manager = OcrManager(
            engine = engine,
            merger = mockk(relaxed = true) {
                every { merge(any(), any(), any()) } returns listOf(block)
            },
            cacheManager = cacheManager,
            store = store,
            preferences = OcrPreferences(InMemoryPreferenceStore()),
            dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val saved = mutableListOf<String>()
        coEvery { cacheManager.save(any(), any(), any(), any(), capture(saved)) } returns Unit
        mockDecodingApi()

        try {
            manager.recognizeAndCache(
                key = key,
                pageIndex = 0,
                bytes = ByteArray(16),
                language = OcrLanguage.JAPANESE,
                source = ReaderOcrSource.PADDLE,
            ) shouldBe listOf(block)
        } finally {
            unmockkAll()
        }

        saved.single() shouldEndWith "-release-a|det:100:200"
        saved.single() shouldNotContain "release-b"
    }

    /**
     * The Android decode surface `recognizePage` uses, answered without native code: a 1000x1000 page,
     * below `TILE_TRIGGER_DIMENSION`, so the whole page goes to the engine in one call.
     */
    private fun mockDecodingApi() {
        mockkStatic(BitmapFactory::class)
        // `BitmapFactory.Options()` is an android.jar stub, so its constructor must be intercepted
        // before its bounds fields can be written.
        mockkConstructor(BitmapFactory.Options::class)
        every {
            BitmapFactory.decodeByteArray(any<ByteArray>(), any<Int>(), any<Int>(), any())
        } answers {
            val options = arg<BitmapFactory.Options>(3)
            options.outWidth = 1000
            options.outHeight = 1000
            null
        }
    }

    private companion object {
        private const val SOURCE = 1L
        private const val MANGA = 2L
        private const val CHAPTER = 3L

        private val line = EngineLine(
            text = "こんにちは",
            bbox = NormalizedBBox(left = 0.1, top = 0.2, right = 0.3, bottom = 0.4),
            writingDirection = WritingDirection.TTB,
            characterSize = 20.0,
            hasJpText = true,
            hasKanji = false,
        )

        private val block = OcrTextBlock(
            xmin = 0.1f,
            ymin = 0.2f,
            xmax = 0.3f,
            ymax = 0.4f,
            lines = listOf("こんにちは"),
            vertical = true,
            language = "ja",
        )
    }
}
