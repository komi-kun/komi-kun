// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AudioCacheTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `sha256 produces consistent hex string`() {
        val hash = AudioCache.sha256("https://example.com/audio.mp3")
        hash shouldBe AudioCache.sha256("https://example.com/audio.mp3")
        hash.length shouldBe 64
    }

    @Test
    fun `getCachedFile returns null for blank or tts url`() {
        val ctx = mockk<Context>()
        every { ctx.cacheDir } returns tempDir

        AudioCache.getCachedFile(ctx, "") shouldBe null
        AudioCache.getCachedFile(ctx, "tts:something") shouldBe null
        AudioCache.getCachedFile(null, "https://example.com/audio.mp3") shouldBe null
    }

    @Test
    fun `getCachedFile returns file when it exists and is non-empty`() {
        val ctx = mockk<Context>()
        every { ctx.cacheDir } returns tempDir

        val url = "https://example.com/audio.mp3"
        val cacheDir = AudioCache.getCacheDir(ctx)
        val file = File(cacheDir, "${AudioCache.sha256(url)}.mp3")
        file.writeBytes(ByteArray(1024) { 1 })

        val cached = AudioCache.getCachedFile(ctx, url)
        cached shouldNotBe null
        cached?.absolutePath shouldBe file.absolutePath
    }

    @Test
    fun `getCachedFile rejects jpod dummy audio`() {
        val ctx = mockk<Context>()
        every { ctx.cacheDir } returns tempDir

        val url = "https://example.com/audio.mp3"
        val cacheDir = AudioCache.getCacheDir(ctx)
        val file = File(cacheDir, "${AudioCache.sha256(url)}.mp3")
        file.writeBytes(ByteArray(AudioCache.JPOD_DUMMY_AUDIO_BYTES.toInt()) { 0 })

        AudioCache.getCachedFile(ctx, url) shouldBe null
    }

    @Test
    fun `getCachedFile rejects empty file`() {
        val ctx = mockk<Context>()
        every { ctx.cacheDir } returns tempDir

        val url = "https://example.com/audio.mp3"
        val cacheDir = AudioCache.getCacheDir(ctx)
        val file = File(cacheDir, "${AudioCache.sha256(url)}.mp3")
        file.createNewFile()

        AudioCache.getCachedFile(ctx, url) shouldBe null
    }

    @Test
    fun `pruneIfNeeded deletes oldest files when cache exceeds maxBytes`() {
        val ctx = mockk<Context>()
        every { ctx.cacheDir } returns tempDir
        val cacheDir = AudioCache.getCacheDir(ctx)

        val file1 = File(cacheDir, "oldest.mp3").apply {
            writeBytes(ByteArray(100) { 1 })
            setLastModified(1000L)
        }
        val file2 = File(cacheDir, "middle.mp3").apply {
            writeBytes(ByteArray(100) { 2 })
            setLastModified(2000L)
        }
        val file3 = File(cacheDir, "newest.mp3").apply {
            writeBytes(ByteArray(100) { 3 })
            setLastModified(3000L)
        }

        // Total size = 300 bytes. maxBytes = 250, targetBytes = 150
        AudioCache.pruneIfNeeded(cacheDir, maxBytes = 250, targetBytes = 150)

        // file1 (oldest) and file2 (middle) should be deleted, leaving total <= 150
        file1.exists() shouldBe false
        file2.exists() shouldBe false
        file3.exists() shouldBe true
    }
}
// KUN <--
