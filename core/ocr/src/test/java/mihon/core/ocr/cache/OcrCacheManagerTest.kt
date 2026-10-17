// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Regression test for the nested-lock deadlock in OcrCacheManager.
//
// `load`/`save`/`hasData` used to acquire the coroutine `Mutex` and then delegate to the *locked*
// file wrappers `loadFrom`/`saveTo`/`hasDataIn`, which re-locked the same (non-reentrant) `Mutex`
// and suspended forever. Every reader OCR path and every queue path therefore hung at the cache
// check, so OCR never ran (no engine init, no cache, no blocks) and long-press fell through to the
// page-actions dialog. These tests time out if the deadlock is reintroduced.
// KUN <--
package mihon.core.ocr.cache

import android.content.Context
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.core.ocr.model.OcrTextBlock
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class OcrCacheManagerTest {

    private val context = mockk<Context>()
    private val cacheDir: File = Files.createTempDirectory("ocr-cache-test").toFile().also {
        every { context.filesDir } returns it
    }
    private val manager = OcrCacheManager(context, Json)

    private val key = OcrCacheKey(sourceId = 1L, mangaId = 2L, chapterId = 3L)
    private val blocks = listOf(
        OcrTextBlock(
            xmin = 0.1f,
            ymin = 0.2f,
            xmax = 0.3f,
            ymax = 0.4f,
            lines = listOf("こんにちは"),
            vertical = true,
            language = "ja",
        ),
    )

    @Test
    fun `keyed save and load round-trip without deadlocking`(): Unit = runBlocking {
        withTimeout(TIMEOUT_MS) {
            manager.save(key, pageIndex = 0, blocks = blocks, language = "ja", variant = "paddle")
            val loaded = manager.load(key, pageIndex = 0, variant = "paddle")

            loaded.shouldNotBeNull()
            loaded shouldHaveSize 1
            loaded.single().lines shouldBe listOf("こんにちは")
            loaded.single().language shouldBe "ja"
        }
    }

    @Test
    fun `keyed hasData does not deadlock`(): Unit = runBlocking {
        withTimeout(TIMEOUT_MS) {
            manager.hasData(key) shouldBe false
            manager.save(key, pageIndex = 0, blocks = blocks, language = "ja", variant = "paddle")
            manager.hasData(key) shouldBe true
        }
    }

    private companion object {
        /** Long enough for file I/O, short enough that a deadlock fails fast instead of hanging CI. */
        private const val TIMEOUT_MS = 10_000L
    }
}
