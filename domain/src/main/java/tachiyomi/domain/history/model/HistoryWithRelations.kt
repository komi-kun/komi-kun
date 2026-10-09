package tachiyomi.domain.history.model

import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.MangaCover
import kotlin.time.Instant

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    // SY -->
    val ogTitle: String,
    // SY <--
    val chapterNumber: Double,
    // KMK -->
    val read: Boolean,
    val lastPageRead: Long,
    val totalCountCalculated: Long,
    val readCountCalculated: Long,
    // KMK <--
    val readAt: Instant?,
    val readDuration: Long,
    val coverData: MangaCover,
) {
    // SY -->
    // KMK -->
    val title: String = customMangaManager?.get(mangaId)?.title ?: ogTitle
    // KMK <--

    companion object {
        // KMK -->
        private val customMangaManager: GetCustomMangaInfo?
            get() = GetCustomMangaInfo.getInstanceOrNull()
        // KMK <--
    }
    // SY <--

    // KMK -->
    val unreadCount
        get() = totalCountCalculated - readCountCalculated
    // KMK <--
}
