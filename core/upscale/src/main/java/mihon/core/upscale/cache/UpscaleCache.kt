// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The upscaled-page cache: one image file per (page × model × engine) key.
//
// **Lookup must not suspend.** The reader asks "is this page already upscaled?" while deciding how to
// decode it, on a path that cannot wait for a coroutine. So `get` is a synchronous `File.isFile` —
// no index file, no metadata JSON, no directory scan — and everything that costs more (writing,
// pruning, sizing) happens on the engine thread instead.
//
// **The key is the identity.** It carries the source, the chapter's `sourceLength` and
// `sourceLastModified` (a re-downloaded chapter therefore misses instead of serving a stale upscale),
// the model, the scale, `UpscalePreferences.cacheSignature()` and [ENGINE_GENERATION] (bump it when
// the pipeline's output changes). Only the image is stored: the key says what it is.
//
// Bounded by `UpscalePreferences.cacheMb` and a 30-day TTL, oldest-first on write.
// KUN <--
package mihon.core.upscale.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Bumped whenever the pipeline's *output* for the same key changes (a different resize kernel, a
 * blending change, a fixed bug): old entries stop matching instead of being served as current.
 */
const val ENGINE_GENERATION: String = "v1"

/**
 * A synchronous, bounded, TTL'd disk cache of upscaled pages.
 *
 * Entries are files named after the SHA-256 of their key; the key itself is never a path.
 */
class UpscaleCache(
    /** Where the entries live (a cache directory: the system may delete it). */
    val directory: File,
    /** Current budget in bytes, read on every write so a changed preference applies immediately. */
    private val maxBytes: () -> Long,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val writeLock = Mutex()

    /**
     * The deterministic key of one upscaled page.
     *
     * Every field is part of it: two results are interchangeable only when all of them match.
     */
    fun key(
        sourceId: Long,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        sourceLength: Long,
        sourceLastModified: Long,
        modelId: String,
        scale: Int,
        signature: String,
    ): String = buildString {
        append(ENGINE_GENERATION)
        append(";source=").append(sourceId)
        append(";manga=").append(mangaId)
        append(";chapter=").append(chapterId)
        append(";page=").append(pageIndex)
        append(";length=").append(sourceLength)
        append(";modified=").append(sourceLastModified)
        append(";model=").append(modelId)
        append(";scale=").append(scale)
        append(";signature=").append(signature)
    }

    /**
     * The cached file for [key], or `null` when there is none.
     *
     * One `File.isFile`: no index, no scan, no TTL check (expiry is enforced when the cache is
     * pruned, see [put]). Safe to call from the page-decode path.
     */
    fun get(key: String): File? = File(directory, fileName(key)).takeIf { it.isFile }

    /**
     * Writes one entry: [write] fills a `.part` file, which is then renamed into place, so a crash or
     * a failed write leaves either the previous entry or none — never a partial image. Then prunes.
     */
    suspend fun put(key: String, write: (File) -> Unit): File {
        directory.mkdirs()
        val name = fileName(key)
        val part = File(directory, "$name$PART_SUFFIX")
        val target = File(directory, name)
        return withContext(Dispatchers.IO) {
            try {
                write(part)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            } finally {
                if (part.exists()) part.delete()
            }
            writeLock.withLock { prune() }
            target
        }
    }

    /** Removes every entry and returns how many there were. */
    fun clear(): Int {
        val entries = directory.listFiles().orEmpty()
        var removed = 0
        for (entry in entries) {
            if (entry.isFile && entry.delete()) removed++
        }
        return removed
    }

    /** Bytes the entries take. Expired entries are deleted by the next write, not here. */
    fun sizeBytes(): Long = entries().sumOf { it.file.length() }

    private class Entry(val file: File, val writtenAt: Long)

    private fun entries(): List<Entry> =
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
            .map { Entry(it, it.lastModified()) }
            .sortedBy { it.writtenAt }

    /** Drops expired entries, then the oldest until the budget holds (the newest always survives). */
    private fun prune() {
        val entries = entries().toMutableList()
        val cutoff = now() - ttlMillis
        val expired = entries.filter { it.writtenAt < cutoff }
        expired.forEach { it.file.delete() }
        entries.removeAll(expired.toSet())

        val max = maxBytes()
        var size = entries.sumOf { it.file.length() }
        var index = 0
        while (size > max && index < entries.size - 1) {
            val entry = entries[index++]
            val length = entry.file.length()
            if (entry.file.delete()) size -= length
        }
    }

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ENTRY_SUFFIX
    }

    companion object {
        /** How long an entry stays valid after it was written. */
        val DEFAULT_TTL_MILLIS: Long = TimeUnit.DAYS.toMillis(30)

        private const val ENTRY_SUFFIX = ".img"
        private const val PART_SUFFIX = ".part"
    }
}
