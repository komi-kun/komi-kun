package tachiyomi.domain.manga.model

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

data class MangaUpdate(
    val id: Long,
    val source: Long? = null,
    val favorite: Boolean? = null,
    val lastUpdate: Instant? = null,
    val nextUpdate: Instant? = null,
    val fetchInterval: Int? = null,
    val dateAdded: Instant? = null,
    val viewerFlags: Long? = null,
    val chapterFlags: Long? = null,
    val coverLastModified: Instant? = null,
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
    val updateStrategy: UpdateStrategy? = null,
    val initialized: Boolean? = null,
    val version: Long? = null,
    val notes: String? = null,
    val memo: JsonObject? = null,
    // SY -->
    val filteredScanlators: List<String>? = null,
    // SY <--
)

fun Manga.toMangaUpdate(): MangaUpdate {
    return MangaUpdate(
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
        nextUpdate = nextUpdate,
        fetchInterval = fetchInterval,
        dateAdded = dateAdded,
        viewerFlags = viewerFlags,
        chapterFlags = chapterFlags,
        coverLastModified = coverLastModified,
        url = url,
        // SY -->
        title = ogTitle,
        artist = ogArtist,
        author = ogAuthor,
        thumbnailUrl = ogThumbnailUrl,
        description = ogDescription,
        genre = ogGenre,
        status = ogStatus,
        // SY <--
        updateStrategy = updateStrategy,
        initialized = initialized,
        version = version,
        notes = notes,
        memo = memo,
    )
}
