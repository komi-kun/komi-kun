package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject
import mihon.domain.common.PartialUpdate
import kotlin.time.Instant

class ChapterUpdate(val id: Long, block: ChapterUpdate.() -> Unit) : PartialUpdate() {
    var read: Boolean? by field(null)
    var bookmark: Boolean? by field(null)
    var lastPageRead: Int? by field(null)
    var dateFetch: Instant? by field(null)
    var sourceOrder: Long? by field(null)
    var url: String? by field(null)
    var name: String? by field(null)
    var dateUpload: Instant? by field(null)
    var chapterNumber: Double? by field(null)
    var scanlator: String? by field(null)
    var version: Long? by field(null)
    var memo: JsonObject? by field(null)

    init {
        block()
    }
}

fun Chapter.toChapterUpdate(): ChapterUpdate {
    return ChapterUpdate(id) {
        read = this@toChapterUpdate.read
        bookmark = this@toChapterUpdate.bookmark
        lastPageRead = this@toChapterUpdate.lastPageRead
        dateFetch = this@toChapterUpdate.dateFetch
        sourceOrder = this@toChapterUpdate.sourceOrder
        url = this@toChapterUpdate.url
        name = this@toChapterUpdate.name
        dateUpload = this@toChapterUpdate.dateUpload
        chapterNumber = this@toChapterUpdate.chapterNumber
        scanlator = this@toChapterUpdate.scanlator
        version = this@toChapterUpdate.version
        memo = this@toChapterUpdate.memo
    }
}
