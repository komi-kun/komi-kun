// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The detector-resolution setting is only real if the limit a page was prepared at reaches the
// engine. `prepareForOcr` resolves it per chunk against the chunk's own long side (see
// `ImageChunkingTest`), and this pins the next hop: `OcrManager.recognizePage` hands the chunk's
// limit to `OcrEngine.recognize(bitmap, …)`. The failure this catches is the one the setting had
// before: the engine was called without a limit, so every non-default mode silently ran at 960.
// KUN <--
package mihon.core.ocr.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mihon.core.ocr.EngineLine
import mihon.core.ocr.OcrBitmapDecoder
import mihon.core.ocr.OcrDetResolution
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.merge.OcrLineMerger
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class OcrManagerDetectorLimitTest {

    /**
     * Stands in for `PaddleOcrEngine`, recording the detector limit of every call. It is a real
     * [OcrEngine] implementation rather than a mock so the *interface* default (960) is what a call
     * omitting the argument would take — the value an unplumbed `OcrManager` would fall back to.
     */
    private class RecordingEngine : OcrEngine {
        val limits = mutableListOf<Int>()
        val sizes = mutableListOf<Pair<Int, Int>>()

        override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> = emptyList()

        override suspend fun recognize(
            bitmap: Bitmap,
            language: OcrLanguage,
            detectorLimitSide: Int,
        ): List<EngineLine> {
            limits += detectorLimitSide
            sizes += bitmap.width to bitmap.height
            return emptyList()
        }
    }

    private fun manager(engine: OcrEngine, resolution: OcrDetResolution): OcrManager = OcrManager(
        engine = engine,
        merger = mockk<OcrLineMerger>(relaxed = true),
        cacheManager = mockk<OcrCacheManager>(relaxed = true),
        store = mockk<OcrStore>(relaxed = true),
        preferences = OcrPreferences(InMemoryPreferenceStore()).apply {
            advancedTuning.set(true)
            detResolution.set(resolution.name)
        },
        dictionaryOcr = DictionaryOcrPreferences(InMemoryPreferenceStore()).apply { choose(true) },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `recognizePage hands the engine each chunk's resolved detector limit`(): Unit = runBlocking {
        val engine = RecordingEngine()
        mockDecodingApi()
        try {
            // A 3000x1200 page is above the tiling threshold, so it reaches the engine as two
            // 2400x1200 tiles.
            manager(engine, OcrDetResolution.VERY_HIGH).recognizePage(ByteArray(16), OcrLanguage.JAPANESE)
                .shouldBeEmpty()

            engine.sizes shouldBe listOf(2400 to 1200, 2400 to 1200)
            engine.limits shouldBe listOf(1600, 1600)

            // The same page at the default mode is the previous 960 behaviour — which is why the
            // assertion above is not satisfiable by a constant, and `Auto` is resolved, not passed
            // through from the mode.
            val auto = RecordingEngine()
            manager(auto, OcrDetResolution.AUTO).recognizePage(ByteArray(16), OcrLanguage.JAPANESE)
            auto.limits shouldBe listOf(1600, 1600)
        } finally {
            unmockkAll()
        }
    }

    @Test
    fun `the default mode still reaches the engine at 960`(): Unit = runBlocking {
        val engine = RecordingEngine()
        mockDecodingApi()
        try {
            manager(engine, OcrDetResolution.DEFAULT).recognizePage(ByteArray(16), OcrLanguage.JAPANESE)

            engine.limits shouldBe listOf(960, 960)
        } finally {
            unmockkAll()
        }
    }

    /**
     * The Android decode surface `recognizePage` uses, answered without native code: a 3000x1200
     * page and tiles cut from it.
     */
    private fun mockDecodingApi() {
        mockkStatic(BitmapFactory::class)
        // `BitmapFactory.Options()` is an android.jar stub, so its constructor must be intercepted
        // before its bounds fields can be written.
        mockkConstructor(BitmapFactory.Options::class)
        mockkObject(OcrBitmapDecoder)
        mockkStatic(Bitmap::class)

        every {
            BitmapFactory.decodeByteArray(any<ByteArray>(), any<Int>(), any<Int>(), any())
        } answers {
            val options = arg<BitmapFactory.Options>(3)
            options.outWidth = 3000
            options.outHeight = 1200
            null
        }
        every { OcrBitmapDecoder.decode(any<ByteArray>(), any<Int>()) } returns bitmapOf(3000, 1200)
        every { OcrBitmapDecoder.sampleSizeFor(any<Int>(), any<Int>(), any<ByteArray>()) } returns 1
        every {
            Bitmap.createBitmap(any<Bitmap>(), any<Int>(), any<Int>(), any<Int>(), any<Int>())
        } answers { bitmapOf(arg(3), arg(4)) }
    }

    /** A bitmap whose only real behaviour is its size; [width]/[height] drive the chunker. */
    private fun bitmapOf(width: Int, height: Int): Bitmap = mockk(relaxed = true) {
        every { this@mockk.width } returns width
        every { this@mockk.height } returns height
    }
}
