// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The synchronous result cache: key completeness, a stale page missing, atomic writes, eviction and
// clearing. The key is the whole identity, so "a field changed" must mean "a different file".
// KUN <--
package mihon.core.upscale.cache

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class UpscaleCacheTest {

    private val directory: File = Files.createTempDirectory("upscale-cache").toFile()

    @Test
    fun `the key carries every field, so changing any one of them is a different page`() {
        val cache = UpscaleCache(directory, maxBytes = { Long.MAX_VALUE })

        val base = cache.key(
            sourceId = 1,
            mangaId = 2,
            chapterId = 3,
            pageIndex = 4,
            sourceLength = 5,
            sourceLastModified = 6,
            modelId = "model",
            scale = 2,
            signature = "sig",
        )
        base shouldBe cache.key(
            sourceId = 1,
            mangaId = 2,
            chapterId = 3,
            pageIndex = 4,
            sourceLength = 5,
            sourceLastModified = 6,
            modelId = "model",
            scale = 2,
            signature = "sig",
        )

        val variants = listOf(
            cache.key(11, 2, 3, 4, 5, 6, "model", 2, "sig"),
            cache.key(1, 12, 3, 4, 5, 6, "model", 2, "sig"),
            cache.key(1, 2, 13, 4, 5, 6, "model", 2, "sig"),
            cache.key(1, 2, 3, 14, 5, 6, "model", 2, "sig"),
            cache.key(1, 2, 3, 4, 15, 6, "model", 2, "sig"),
            cache.key(1, 2, 3, 4, 5, 16, "model", 2, "sig"),
            cache.key(1, 2, 3, 4, 5, 6, "other", 2, "sig"),
            cache.key(1, 2, 3, 4, 5, 6, "model", 4, "sig"),
            cache.key(1, 2, 3, 4, 5, 6, "model", 2, "other"),
        )
        variants.forEach { variant ->
            variant shouldNotBe base
        }
        variants.toSet().size shouldBe variants.size
    }

    @Test
    fun `a re-downloaded chapter misses because sourceLastModified is part of the key`() {
        val cache = UpscaleCache(directory, maxBytes = { Long.MAX_VALUE })
        val before = cache.key(1, 2, 3, 0, 100, 1_000, "model", 2, "sig")
        val after = cache.key(1, 2, 3, 0, 100, 2_000, "model", 2, "sig")

        runBlocking { cache.put(before) { it.writeText("page") } }

        cache.get(before) shouldNotBe null
        cache.get(after) shouldBe null
    }

    @Test
    fun `put renames the part file into place and leaves no scratch file behind`() {
        val cache = UpscaleCache(directory, maxBytes = { Long.MAX_VALUE })
        val key = cache.key(1, 2, 3, 0, 100, 1_000, "model", 2, "sig")

        val file = runBlocking { cache.put(key) { part -> part.writeText("upscaled") } }

        file.isFile shouldBe true
        file.readText() shouldBe "upscaled"
        cache.get(key) shouldBe file
        directory.listFiles().orEmpty().filter { it.name.endsWith(".part") }.shouldBeEmpty()
    }

    @Test
    fun `a failed write leaves neither an entry nor a scratch file`() {
        val cache = UpscaleCache(directory, maxBytes = { Long.MAX_VALUE })
        val key = cache.key(1, 2, 3, 0, 100, 1_000, "model", 2, "sig")

        shouldThrow<IOException> {
            runBlocking {
                cache.put(key) { part ->
                    part.writeText("half")
                    throw IOException("disk full")
                }
            }
        }

        cache.get(key) shouldBe null
        directory.listFiles().orEmpty().filter { it.name.endsWith(".part") }.shouldBeEmpty()
    }

    @Test
    fun `eviction drops the oldest entry and keeps the newest`() {
        // Room for roughly two 100-byte entries.
        val cache = UpscaleCache(directory, maxBytes = { 250 })
        val keys = (0 until 3).map { cache.key(1, 2, 3, it, 100, 1_000, "model", 2, "sig") }
        val now = System.currentTimeMillis()

        runBlocking { cache.put(keys[0]) { it.writeBytes(ByteArray(100)) } }
        cache.get(keys[0])!!.setLastModified(now - TimeUnit.MINUTES.toMillis(3))
        runBlocking { cache.put(keys[1]) { it.writeBytes(ByteArray(100)) } }
        cache.get(keys[1])!!.setLastModified(now - TimeUnit.MINUTES.toMillis(2))
        runBlocking { cache.put(keys[2]) { it.writeBytes(ByteArray(100)) } }

        cache.get(keys[0]) shouldBe null
        cache.get(keys[1]) shouldNotBe null
        cache.get(keys[2]) shouldNotBe null
        cache.sizeBytes() shouldBeLessThanOrEqual 250
    }

    @Test
    fun `an entry older than the TTL is dropped on the next write`() {
        val cache = UpscaleCache(
            directory = directory,
            maxBytes = { Long.MAX_VALUE },
            ttlMillis = TimeUnit.DAYS.toMillis(30),
        )
        val stale = cache.key(1, 2, 3, 0, 100, 1_000, "model", 2, "sig")
        val fresh = cache.key(1, 2, 3, 1, 100, 1_000, "model", 2, "sig")

        runBlocking { cache.put(stale) { it.writeText("old") } }
        cache.get(stale)!!.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31))
        runBlocking { cache.put(fresh) { it.writeText("new") } }

        cache.get(stale) shouldBe null
        cache.get(fresh) shouldNotBe null
    }

    @Test
    fun `clear removes every entry`() {
        val cache = UpscaleCache(directory, maxBytes = { Long.MAX_VALUE })
        val keys = (0 until 3).map { cache.key(1, 2, 3, it, 100, 1_000, "model", 2, "sig") }
        keys.forEach { key -> runBlocking { cache.put(key) { it.writeText("page$key") } } }

        cache.clear() shouldBe 3

        keys.forEach { cache.get(it) shouldBe null }
        cache.sizeBytes() shouldBe 0L
        directory.listFiles().orEmpty().shouldBeEmpty()
    }
}
