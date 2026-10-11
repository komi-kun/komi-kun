package tachiyomi.domain.manga.model

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import mihon.domain.common.PartialUpdate
import kotlin.time.Instant

class MangaUpdate(val id: Long, block: MangaUpdate.() -> Unit) : PartialUpdate() {
    var source: Long? by field(null)
    var favorite: Boolean? by field(null)
    var lastUpdate: Instant? by field(null)
    var nextUpdate: Instant? by field(null)
    var fetchInterval: Int? by field(null)
    var dateAdded: Instant? by field(null)
    var viewerFlags: Long? by field(null)
    var chapterFlags: Long? by field(null)
    var coverLastModified: Instant? by field(null)
    var url: String? by field(null)
    var title: String? by field(null)
    var artist: String? by field(null)
    var author: String? by field(null)
    var description: String? by field(null)
    var genre: List<String>? by field(null)
    var status: Long? by field(null)
    var thumbnailUrl: String? by field(null)
    var updateStrategy: UpdateStrategy? by field(null)
    var initialized: Boolean? by field(null)
    var version: Long? by field(null)
    var notes: String? by field(null)
    var memo: JsonObject? by field(null)
    // SY -->
    var filteredScanlators: List<String>? by field(null)
    // SY <--

    init {
        block()
    }
}

fun Manga.toMangaUpdate(): MangaUpdate {
    return MangaUpdate(id) {
        source = this@toMangaUpdate.source
        favorite = this@toMangaUpdate.favorite
        lastUpdate = this@toMangaUpdate.lastUpdate
        nextUpdate = this@toMangaUpdate.nextUpdate
        fetchInterval = this@toMangaUpdate.fetchInterval
        dateAdded = this@toMangaUpdate.dateAdded
        viewerFlags = this@toMangaUpdate.viewerFlags
        chapterFlags = this@toMangaUpdate.chapterFlags
        coverLastModified = this@toMangaUpdate.coverLastModified
        url = this@toMangaUpdate.url
        // SY -->
        title = this@toMangaUpdate.ogTitle
        artist = this@toMangaUpdate.ogArtist
        author = this@toMangaUpdate.ogAuthor
        thumbnailUrl = this@toMangaUpdate.ogThumbnailUrl
        description = this@toMangaUpdate.ogDescription
        genre = this@toMangaUpdate.ogGenre
        status = this@toMangaUpdate.ogStatus
        // SY <--
        updateStrategy = this@toMangaUpdate.updateStrategy
        initialized = this@toMangaUpdate.initialized
        version = this@toMangaUpdate.version
        notes = this@toMangaUpdate.notes
        memo = this@toMangaUpdate.memo
    }
}
