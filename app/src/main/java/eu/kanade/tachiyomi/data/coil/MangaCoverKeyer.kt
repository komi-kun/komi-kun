package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.manga.model.MangaCover
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant
import tachiyomi.domain.manga.model.Manga as DomainManga

class MangaKeyer : Keyer<DomainManga> {
    override fun key(data: DomainManga, options: Options): String {
        return if (data.hasCustomCover()) {
            "${data.id};${data.coverLastModified}"
        } else {
            // KUN -->
            RecoveredCoverKeys.get(data.id, data.coverLastModified)
                ?: "${data.thumbnailUrl};${data.coverLastModified}"
            // KUN <--
        }
    }
}

class MangaCoverKeyer(
    private val coverCache: CoverCache,
) : Keyer<MangaCover> {
    override fun key(data: MangaCover, options: Options): String {
        return if (coverCache.hasCustomCover(data.mangaId)) {
            "${data.mangaId};${data.lastModified}"
        } else {
            // KUN -->
            RecoveredCoverKeys.get(data.mangaId, data.lastModified)
                ?: "${data.url};${data.lastModified}"
            // KUN <--
        }
    }
}

// KUN -->
/**
 * Remembers the Coil cache key under which an automatically recovered cover was stored.
 *
 * The recovered image is already decoded under the old model's key, while the database only
 * changes the URL and leaves the cover timestamp alone. Returning the old key for the rest of
 * the session avoids decoding the same bitmap a second time. The entry stops applying as soon
 * as the timestamp changes (manual cover edit/refresh), and is gone after a process restart,
 * where the normal URL-based key is used again.
 */
internal object RecoveredCoverKeys {
    private data class Entry(val lastModified: Instant?, val key: String)

    private val entries = ConcurrentHashMap<Long, Entry>()

    fun put(mangaId: Long, lastModified: Instant?, key: String) {
        entries[mangaId] = Entry(lastModified, key)
    }

    fun get(mangaId: Long, lastModified: Instant?): String? {
        val entry = entries[mangaId] ?: return null
        if (entry.lastModified != lastModified) {
            entries.remove(mangaId, entry)
            return null
        }
        return entry.key
    }
}
// KUN <--
