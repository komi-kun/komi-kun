package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.sync.repository.SyncRepository

/**
 * Only a full v2 snapshot may imply deletions: a delta omits unchanged entries, so absence there
 * means nothing. Library sync must be on, or the response is not a statement about the library.
 */
internal fun canApplySnapshotDeletions(
    full: Boolean,
    protocolV2: Boolean,
    libraryEntries: Boolean,
): Boolean = full && protocolV2 && libraryEntries

/**
 * Local library manga (favourites) that the server did not return in a full snapshot.
 *
 * [remoteManga] must be the whole snapshot, including `favorite = false` entries: a row the
 * server still knows as a non-favourite is NOT deleted. An empty snapshot returns nothing
 * (fail closed) because treating "server returned no manga" as "delete the whole library" is
 * the destructive case; this guard is deliberate and tested.
 *
 * [uploadedManga] is the local upload that this snapshot answers. A candidate must also be in it:
 * a favourite added (or first favourited) after the upload was built, or one the backup creator
 * omitted, was never sent to the server, so the server cannot have tombstoned it. Without this
 * check, a race between the upload and the post-round-trip database read would hard-delete a
 * manga the user just added.
 */
internal fun mangaIdsMissingFromFullSnapshot(
    localManga: List<Manga>,
    remoteManga: List<BackupManga>,
    uploadedManga: List<BackupManga>,
    fullSnapshot: Boolean,
): List<Long> {
    if (!fullSnapshot || remoteManga.isEmpty()) return emptyList()
    val remoteKeys = remoteManga.mapTo(HashSet()) { it.source to it.url }
    val uploadedKeys = uploadedManga.mapTo(HashSet()) { it.source to it.url }
    return localManga
        .filter { it.favorite && (it.source to it.url) in uploadedKeys && (it.source to it.url) !in remoteKeys }
        .map { it.id }
}

/**
 * Whether a response's history sections may be read as complete, so an entry missing from a manga's
 * list was removed elsewhere. Only a full v2 response can say that: a delta omits the entries that
 * did not change. History sync must be on, or this device never uploaded the rows the server's list
 * would be compared against.
 */
internal fun canApplySnapshotHistoryPrune(
    full: Boolean,
    protocolV2: Boolean,
    historyEntries: Boolean,
): Boolean = full && protocolV2 && historyEntries

/**
 * Local history rows of [remoteManga] that the full snapshot no longer lists.
 *
 * Only a manga the response carries is considered: its absence from the response as a whole says
 * nothing about history, and the manga absence check owns that case. An entry of such a manga whose
 * chapter url is missing from its history list was removed by another device.
 *
 * A row read at or after [syncStart] (epoch seconds) survives: it was read during this sync, may be
 * missing from the upload the server answered, and is therefore not a deletion. Rows without a read
 * date are already removed locally and are not candidates again.
 */
internal suspend fun historyIdsMissingFromFullSnapshot(
    full: Boolean,
    protocolV2: Boolean,
    historySyncEnabled: Boolean,
    syncStart: Long,
    remoteManga: List<BackupManga>,
    syncRepository: SyncRepository,
    chapterRepository: ChapterRepository,
    historyRepository: HistoryRepository,
): List<Long> {
    if (!canApplySnapshotHistoryPrune(full, protocolV2, historySyncEnabled)) return emptyList()
    if (remoteManga.isEmpty()) return emptyList()
    val remoteUrlsByKey = remoteManga.associate { (it.source to it.url) to it.history.mapTo(mutableSetOf()) { h -> h.url } }
    // `read_at` is stored in epoch millis, the sync epoch in seconds.
    val syncStartMillis = syncStart * 1000

    val localManga = syncRepository.getAllManga().filter { (it.source to it.url) in remoteUrlsByKey }
    if (localManga.isEmpty()) return emptyList()

    val mangaIds = localManga.map { it.id }
    val chapterUrlsByMangaId = chapterRepository.getChaptersByMangaIds(mangaIds)
        .groupBy { it.mangaId }
        .mapValues { (_, chapters) -> chapters.associate { it.id to it.url } }
    val historyByMangaId = historyRepository.getHistoryByMangaIds(mangaIds)

    return localManga.flatMap { manga ->
        val remoteUrls = remoteUrlsByKey.getValue(manga.source to manga.url)
        val chapterUrlsById = chapterUrlsByMangaId[manga.id].orEmpty()
        historyByMangaId[manga.id].orEmpty().mapNotNull { history ->
            val readAt = history.readAt ?: return@mapNotNull null
            if (readAt.toEpochMilliseconds() >= syncStartMillis) return@mapNotNull null
            val url = chapterUrlsById[history.chapterId] ?: return@mapNotNull null
            history.id.takeIf { url !in remoteUrls }
        }
    }.distinct()
}
