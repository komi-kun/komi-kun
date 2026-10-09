package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

data class ChapterUpdate(
    val id: Long,
    val mangaId: Long? = null,
    val read: Boolean? = null,
    val bookmark: Boolean? = null,
    val lastPageRead: Long? = null,
    val dateFetch: Instant? = null,
    val sourceOrder: Long? = null,
    val url: String? = null,
    val name: String? = null,
    val dateUpload: Instant? = null,
    val chapterNumber: Double? = null,
    val scanlator: String? = null,
    val version: Long? = null,
    val memo: JsonObject? = null,
)

fun Chapter.toChapterUpdate(): ChapterUpdate {
    return ChapterUpdate(
        id,
        mangaId,
        read,
        bookmark,
        lastPageRead,
        dateFetch,
        sourceOrder,
        url,
        name,
        dateUpload,
        chapterNumber,
        scanlator,
        version,
        memo,
    )
}
