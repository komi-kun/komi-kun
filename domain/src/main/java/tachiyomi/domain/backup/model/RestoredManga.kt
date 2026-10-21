package tachiyomi.domain.backup.model

import exh.metadata.metadata.base.FlatMetadata
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.track.model.Track
import kotlin.time.Instant

data class RestoredManga(
    val manga: Manga,
    val chapters: List<Chapter>,
    // KUN -->
    val categoryIds: List<Long>?,
    // KUN <--
    val history: List<RestoredHistory>,
    val tracks: List<Track>,
    val excludedScanlators: List<String>,
    // KMK/SY -->
    val mergedMangaReferences: List<MergedMangaReference> = emptyList(),
    val flatMetadata: FlatMetadata? = null,
    val customMangaInfo: CustomMangaInfo? = null,
    // KMK/SY <--
    // KUN -->
    /**
     * Whether the payload carried the custom-info section at all. `true` with an all-null
     * [customMangaInfo] means the user cleared every override and a winning restore may remove them
     * locally; `null`/`false` only allows merging the non-null overrides.
     */
    val customInfoIncluded: Boolean? = null,
    // KUN <--
)

data class RestoredHistory(
    val chapterUrl: String,
    val readAt: Instant?,
    val readDuration: Long,
)
