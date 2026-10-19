// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.ocr.paddle

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import mihon.core.ocr.EngineLine
import mihon.core.ocr.NormalizedBBox
import mihon.core.ocr.OcrEngineType
import mihon.core.ocr.OcrLanguage
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.WritingDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.IOException

class RoutingOcrEngineTest {

    private val context = mockk<Context>(relaxed = true)
    private val localEngine = mockk<PaddleOcrEngine>(relaxed = true)
    private val vlServerEngine = mockk<PaddleOcrVlEngine>(relaxed = true)
    private val vlOnDeviceEngine = mockk<PaddleOcrVlOnDeviceEngine>(relaxed = true)
    private val prefStore = InMemoryPreferenceStore()
    private val preferences = OcrPreferences(prefStore)
    private val failoverWarnings = mutableListOf<String>()
    private val router = RoutingOcrEngine(
        context = context,
        preferences = preferences,
        localEngine = localEngine,
        vlServerEngine = vlServerEngine,
        vlOnDeviceEngine = vlOnDeviceEngine,
        onFailoverWarning = { failoverWarnings.add(it) },
    )

    private val dummyBytes = byteArrayOf(1, 2, 3)
    private val sampleLine = EngineLine(
        text = "テスト",
        bbox = NormalizedBBox(0.1, 0.1, 0.2, 0.5),
        writingDirection = WritingDirection.TTB,
        language = OcrLanguage.JAPANESE,
    )

    @Test
    fun `routes to local engine when PP_OCRV6_SMALL is selected`() = runBlocking {
        preferences.ocrEngineType.set(OcrEngineType.PP_OCRV6_SMALL.id)
        coEvery { localEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) } returns listOf(sampleLine)

        val result = router.recognize(dummyBytes, OcrLanguage.JAPANESE)
        assertEquals(listOf(sampleLine), result)
        coVerify(exactly = 1) { localEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) }
        coVerify(exactly = 0) { vlServerEngine.recognize(any(), any()) }
        coVerify(exactly = 0) { vlOnDeviceEngine.recognize(any(), any()) }
    }

    @Test
    fun `routes to vl server engine when PADDLEOCR_VL and server mode selected`() = runBlocking {
        preferences.ocrEngineType.set(OcrEngineType.PADDLEOCR_VL.id)
        preferences.paddleVlExecutionMode.set(OcrPreferences.MODE_SERVER)
        coEvery { vlServerEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) } returns listOf(sampleLine)

        val result = router.recognize(dummyBytes, OcrLanguage.JAPANESE)
        assertEquals(listOf(sampleLine), result)
        coVerify(exactly = 1) { vlServerEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) }
        coVerify(exactly = 0) { localEngine.recognize(any(), any()) }
        coVerify(exactly = 0) { vlOnDeviceEngine.recognize(any(), any()) }
    }

    @Test
    fun `routes to vl on-device engine when PADDLEOCR_VL and on-device mode selected`() = runBlocking {
        preferences.ocrEngineType.set(OcrEngineType.PADDLEOCR_VL.id)
        preferences.paddleVlExecutionMode.set(OcrPreferences.MODE_ON_DEVICE)
        coEvery { vlOnDeviceEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) } returns listOf(sampleLine)

        val result = router.recognize(dummyBytes, OcrLanguage.JAPANESE)
        assertEquals(listOf(sampleLine), result)
        coVerify(exactly = 1) { vlOnDeviceEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) }
        coVerify(exactly = 0) { localEngine.recognize(any(), any()) }
        coVerify(exactly = 0) { vlServerEngine.recognize(any(), any()) }
    }

    @Test
    fun `falls back to local engine when vl engine fails and fallback is enabled`() = runBlocking {
        preferences.ocrEngineType.set(OcrEngineType.PADDLEOCR_VL.id)
        preferences.paddleVlExecutionMode.set(OcrPreferences.MODE_SERVER)
        preferences.paddleVlFallback.set(true)
        coEvery { vlServerEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) } throws IOException("Connection refused")
        coEvery { localEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) } returns listOf(sampleLine)

        val result = router.recognize(dummyBytes, OcrLanguage.JAPANESE)
        assertEquals(listOf(sampleLine), result)
        assertEquals(1, failoverWarnings.size)
        assertEquals("Connection refused", failoverWarnings[0])
        coVerify(exactly = 1) { vlServerEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) }
        coVerify(exactly = 1) { localEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) }
    }

    @Test
    fun `throws exception when vl engine fails and fallback is disabled`() {
        preferences.ocrEngineType.set(OcrEngineType.PADDLEOCR_VL.id)
        preferences.paddleVlExecutionMode.set(OcrPreferences.MODE_SERVER)
        preferences.paddleVlFallback.set(false)
        coEvery { vlServerEngine.recognize(dummyBytes, OcrLanguage.JAPANESE) } throws IOException("Connection refused")

        assertThrows(IOException::class.java) {
            runBlocking {
                router.recognize(dummyBytes, OcrLanguage.JAPANESE)
            }
        }
    }

    @Test
    fun `closes local and on-device engines on close`() {
        router.close()
        verify(exactly = 1) { localEngine.close() }
        verify(exactly = 1) { vlOnDeviceEngine.close() }
    }
}
// KUN <--
