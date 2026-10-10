package tachiyomi.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import exh.metadata.metadata.base.FlatMetadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaMapper
import tachiyomi.data.manga.MergedMangaMapper
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MergedMangaReference
import tachiyomi.domain.manga.repository.CustomMangaRepository
import tachiyomi.domain.manga.repository.MangaMetadataRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.util.Date
import kotlin.math.max
import kotlin.math.min

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RestoreRepositoryImpl(
    private val database: Database,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val trackRepository: TrackRepository,
    // KMK/SY -->
    private val mangaMetadataRepository: MangaMetadataRepository,
    private val customMangaRepository: CustomMangaRepository,
    // KMK/SY <--
) : RestoreRepository {

    override suspend fun getMangaUrlsBySourceId(): Map<Long, List<String>> {
        return database.mangaQueries
            .getAllMangaSourceAndUrl()
            .awaitAsList()
            .groupBy({ it.source_id }, { it.remote_url })
    }

    override suspend fun restoreManga(
        entries: List<RestoredManga>,
        isSync: Boolean,
        update: suspend (Manga) -> MangaUpdate,
    ) {
        // KMK/SY -->
        // 1 while applying a sync so the triggers neither bump versions nor stamp last_modified_at
        val syncingFlag = if (isSync) 1L else 0L
        // KMK/SY <--
        database.transaction {
            entries.forEach { entry ->
                // A cancelled restore stops between entries and rolls the whole batch back
                currentCoroutineContext().ensureActive()
                mangaRepository.update(update(restore(entry, isSync, syncingFlag)))
            }
            if (isSync) {
                database.mangaQueries.resetIsSyncing()
                database.chapterQueries.resetIsSyncing()
            }
        }
    }

    override suspend fun updateManga(manga: Manga, isSync: Boolean) {
        updateManga(manga, isSync, if (isSync) 1L else 0L)
    }

    private suspend fun restore(entry: RestoredManga, isSync: Boolean, syncingFlag: Long): Manga {
        val dbManga = mangaRepository.getMangaByUrlAndSourceId(entry.manga.url, entry.manga.source)
        val manga = if (dbManga == null) {
            entry.manga.copy(id = insertManga(entry.manga, syncingFlag))
        } else {
            restoreExistingManga(entry.manga, dbManga, isSync, syncingFlag)
        }

        // SY -->
        // An empty list must clear the assignment, or removing a manga's only category never reaches
        // the other devices (the stale assignment stays forever).
        mangaRepository.setMangaCategories(manga.id, entry.categoryIds)
        // SY <--
        restoreChapters(manga, entry.chapters, isSync, syncingFlag)
        restoreTracking(manga, entry.tracks)
        restoreHistory(manga, entry.history)
        restoreExcludedScanlators(manga, entry.excludedScanlators)
        // KMK/SY -->
        restoreMergedMangaReferencesForManga(manga.id, entry.mergedMangaReferences)
        entry.flatMetadata?.let { restoreFlatMetadata(manga.id, it) }
        restoreEditedInfo(entry.customMangaInfo?.copy(id = manga.id))
        // KMK/SY <--
        return manga
    }

    private suspend fun restoreExistingManga(
        manga: Manga,
        dbManga: Manga,
        isSync: Boolean,
        syncingFlag: Long,
    ): Manga {
        return if (manga.version > dbManga.version) {
            updateManga(dbManga.copyFrom(manga).copy(id = dbManga.id), isSync, syncingFlag)
        } else {
            updateManga(manga.copyFrom(dbManga).copy(id = dbManga.id), isSync, syncingFlag)
        }
    }

    private fun Manga.copyFrom(newer: Manga): Manga {
        return this.copy(
            favorite = this.favorite || newer.favorite,
            // SY -->
            ogAuthor = newer.author,
            ogArtist = newer.artist,
            ogDescription = newer.description,
            ogGenre = newer.genre,
            ogThumbnailUrl = newer.thumbnailUrl,
            ogStatus = newer.status,
            // SY <--
            initialized = this.initialized || newer.initialized,
            version = newer.version,
            // SY -->
            lastModifiedAt = newer.lastModifiedAt,
            // SY <--
        )
    }

    private suspend fun updateManga(manga: Manga, isSync: Boolean, syncingFlag: Long): Manga {
        database.mangaQueries.update(
            source = manga.source,
            url = manga.url,
            // SY -->
            artist = manga.ogArtist,
            author = manga.ogAuthor,
            description = manga.ogDescription,
            genre = manga.ogGenre?.joinToString(separator = ", "),
            title = manga.ogTitle,
            status = manga.ogStatus,
            thumbnailUrl = manga.ogThumbnailUrl,
            // SY <--
            userFavoriteAtSet = true,
            userFavoriteAt = if (manga.favorite) manga.dateAdded else null,
            lastUpdate = manga.lastUpdate,
            nextUpdate = null,
            calculateInterval = null,
            initialized = manga.initialized,
            viewer = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            mangaId = manga.id,
            updateStrategy = manga.updateStrategy.let(UpdateStrategyColumnAdapter::encode),
            version = manga.version,
            // SY -->
            lastModifiedAt = if (isSync) manga.lastModifiedAt else null,
            isSyncing = syncingFlag,
            // SY <--
            notes = manga.notes,
            memo = manga.memo.let(MemoColumnAdapter::encode),
        )
        return manga
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga, syncingFlag: Long): Long {
        return database.mangaQueries.insert(
            sourceId = manga.source,
            remoteUrl = manga.url,
            // SY -->
            remoteArtist = manga.ogArtist,
            remoteAuthor = manga.ogAuthor,
            remoteDescription = manga.ogDescription,
            remoteGenre = manga.ogGenre,
            remoteTitle = manga.ogTitle,
            remoteStatus = manga.ogStatus,
            remoteCover = manga.ogThumbnailUrl,
            // SY <--
            remoteUpdateStrategy = manga.updateStrategy,
            remoteMemo = manga.memo,
            userFavoriteAt = if (manga.favorite) manga.dateAdded else null,
            userNotes = manga.notes,
            userReaderFlags = manga.viewerFlags,
            userChapterFlags = manga.chapterFlags,
            stateChapterLastUpdate = manga.lastUpdate,
            stateChapterNextUpdate = 0L,
            stateChapterFetchInterval = 0L,
            stateCoverLastModified = manga.coverLastModified,
            stateInitialized = manga.initialized,
            // SY -->
            lastModifiedAt = manga.lastModifiedAt,
            version = manga.version,
            isSyncing = syncingFlag,
            // SY <--
        )
            .awaitAsOne()
    }

    private suspend fun restoreChapters(
        manga: Manga,
        restoredChapters: List<Chapter>,
        isSync: Boolean,
        syncingFlag: Long,
    ) {
        val dbChaptersByUrl = chapterRepository.getChapterByMangaId(manga.id)
            .associateBy { it.url }

        val (existingChapters, newChapters) = restoredChapters
            .mapNotNull { backupChapter ->
                val chapter = backupChapter.copy(mangaId = manga.id)
                val dbChapter = dbChaptersByUrl[chapter.url]

                when {
                    dbChapter == null -> chapter // New chapter
                    chapter.forComparison() == dbChapter.forComparison() -> null // Same state; skip
                    else -> updateChapterBasedOnSyncState(chapter, dbChapter, isSync) // Update existed chapter
                }
            }
            .partition { it.id > 0 }

        insertNewChapters(newChapters, syncingFlag)
        updateExistingChapters(existingChapters, isSync, syncingFlag)
    }

    private fun updateChapterBasedOnSyncState(chapter: Chapter, dbChapter: Chapter, isSync: Boolean): Chapter {
        return if (isSync) {
            chapter.copy(
                id = dbChapter.id,
                bookmark = chapter.bookmark || dbChapter.bookmark,
                read = chapter.read,
                lastPageRead = chapter.lastPageRead,
                // KMK -->
                sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                dateUpload = min(chapter.dateUpload, dbChapter.dateUpload),
                // KMK <--
            )
        } else {
            chapter.copyFrom(dbChapter)
                // KMK -->
                .copy(
                    id = dbChapter.id,
                    bookmark = chapter.bookmark || dbChapter.bookmark,
                    sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                    dateUpload = min(chapter.dateUpload, dbChapter.dateUpload),
                )
                // KMK <--
                .let {
                    when {
                        dbChapter.read && !it.read -> it.copy(read = true, lastPageRead = dbChapter.lastPageRead)
                        it.lastPageRead == 0L && dbChapter.lastPageRead != 0L -> it.copy(
                            lastPageRead = dbChapter.lastPageRead,
                        )
                        else -> it
                    }
                }
        }
    }

    private fun Chapter.forComparison() =
        this.copy(
            id = 0L,
            mangaId = 0L,
            dateFetch = 0L,
            // KMK -->
            // dateUpload = 0L, some time source loses dateUpload so we overwrite with backup
            // sourceOrder = 0L, although sourceOrder will be updated on refresh, we want to avoid order mixed up anyway
            // KMK <--
            lastModifiedAt = 0L,
            version = 0L,
        )

    private suspend fun insertNewChapters(chapters: List<Chapter>, syncingFlag: Long) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chapterQueries.insert(
                    mangaId = chapter.mangaId,
                    remoteUrl = chapter.url,
                    remoteName = chapter.name,
                    remoteScanlator = chapter.scanlator,
                    remoteChapterNumber = chapter.chapterNumber,
                    remoteDateUpload = chapter.dateUpload,
                    remoteOrder = chapter.sourceOrder,
                    remoteMemo = chapter.memo,
                    userRead = chapter.read,
                    userBookmark = chapter.bookmark,
                    userLastPageRead = chapter.lastPageRead,
                    stateDateFetch = chapter.dateFetch,
                    // SY -->
                    lastModifiedAt = chapter.lastModifiedAt,
                    version = chapter.version,
                    isSyncing = syncingFlag,
                    // SY <--
                ).awaitAsOneOrNull()
            }
        }
    }

    private suspend fun updateExistingChapters(
        chapters: List<Chapter>,
        isSync: Boolean,
        syncingFlag: Long,
    ) {
        database.transaction {
            chapters.forEach { chapter ->
                database.chapterQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read,
                    bookmark = chapter.bookmark,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = null,
                    dateFetch = null,
                    // KMK -->
                    sourceOrder = chapter.sourceOrder,
                    dateUpload = chapter.dateUpload,
                    // KMK <--
                    chapterId = chapter.id,
                    version = chapter.version,
                    // SY -->
                    lastModifiedAt = if (isSync) chapter.lastModifiedAt else null,
                    isSyncing = syncingFlag,
                    // SY <--
                    memo = chapter.memo.let(MemoColumnAdapter::encode),
                )
            }
        }
    }

    private suspend fun restoreHistory(manga: Manga, restoredHistory: List<RestoredHistory>) {
        val toUpdate = restoredHistory
            .groupBy { it.chapterUrl }
            .mapNotNull { (chapterUrl, copies) ->
                val readAt = copies.maxOf { it.readAt?.time ?: 0L }
                val readDuration = copies.sumOf { it.readDuration }
                // KMK -->
                val dbHistory = database.historyQueries
                    .getHistoryByChapterUrlAndMangaId(chapterUrl, manga.id)
                    .awaitAsList()
                    .firstOrNull()
                // KMK <--

                if (dbHistory == null) {
                    // KMK -->
                    val chapter = database.chapterQueries
                        .getChapterByUrlAndMangaId(manga.id, chapterUrl)
                        .awaitAsList()
                        .firstOrNull()
                    // KMK <--
                    return@mapNotNull if (chapter == null) {
                        // Chapter doesn't exist; skip
                        null
                    } else {
                        // New history entry
                        Triple(chapter.id, Date(readAt), readDuration)
                    }
                }

                // Update history entry
                Triple(
                    dbHistory.chapter_id,
                    max(readAt, dbHistory.read_at?.time ?: 0L)
                        .takeIf { it > 0L }
                        ?.let { Date(it) },
                    max(readDuration, dbHistory.read_duration) - dbHistory.read_duration,
                )
            }

        if (toUpdate.isEmpty()) return
        database.transaction {
            toUpdate.forEach { (chapterId, readAt, readDuration) ->
                database.historyQueries.upsert(
                    chapterId = chapterId,
                    readAt = readAt,
                    readDuration = readDuration,
                )
            }
        }
    }

    private suspend fun restoreTracking(manga: Manga, restoredTracks: List<Track>) {
        val dbTrackByTrackerId = trackRepository.getTracksByMangaId(manga.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = restoredTracks
            .mapNotNull { track ->
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        mangaId = manga.id,
                    )

                if (track.lastChapterRead <= dbTrack.lastChapterRead) return@mapNotNull null

                // Update to an existing track
                dbTrack.copy(lastChapterRead = track.lastChapterRead)
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            trackRepository.upsertAll(newTracks)
        }

        if (existingTracks.isEmpty()) return
        database.transaction {
            existingTracks.forEach { track ->
                database.manga_trackQueries.update(
                    mangaId = track.mangaId,
                    trackerId = track.trackerId,
                    remoteId = track.remoteId,
                    remoteUrl = track.remoteUrl,
                    libraryId = track.libraryId,
                    title = track.title,
                    lastChapterRead = track.lastChapterRead,
                    totalChapters = track.totalChapters,
                    status = track.status,
                    score = track.score,
                    startDate = track.startDate,
                    finishDate = track.finishDate,
                    private = track.private,
                    id = track.id,
                )
            }
        }
    }

    private suspend fun restoreExcludedScanlators(manga: Manga, excludedScanlators: List<String>) {
        if (excludedScanlators.isEmpty()) return
        val existingExcludedScanlators = database.excluded_scanlatorQueries
            .getExcludedScanlatorsByMangaId(manga.id)
            .awaitAsList()
            .toSet()
        val toInsert = excludedScanlators.toSet().subtract(existingExcludedScanlators)
        if (toInsert.isNotEmpty()) {
            database.transaction {
                toInsert.forEach {
                    database.excluded_scanlatorQueries.insert(manga.id, it)
                }
            }
        }
    }

    // SY -->
    /**
     * Restore the merged manga references for the merged manga.
     *
     * @param mergeMangaId the merged manga for the references
     * @param mergedMangaReferences the references for the merged manga
     */
    private suspend fun restoreMergedMangaReferencesForManga(
        mergeMangaId: Long,
        mergedMangaReferences: List<MergedMangaReference>,
    ) {
        if (mergedMangaReferences.isEmpty()) return
        // Get merged manga references from db
        val dbMergedMangaReferences =
            database.mergedQueries.selectAll(MergedMangaMapper::map)
                .awaitAsList()

        mergedMangaReferences.forEach { reference ->
            // If the reference isn't in the db, remove the id and insert a new reference
            if (dbMergedMangaReferences.none {
                    reference.mergeUrl == it.mergeUrl &&
                        reference.mangaUrl == it.mangaUrl
                }
            ) {
                // KMK -->
                val mergedManga = database.mangaQueries
                    // KMK <--
                    .getMangaByUrlAndSource(
                        reference.mangaSourceId,
                        reference.mangaUrl,
                        MangaMapper::mapManga,
                    )
                    // KMK -->
                    .awaitAsOneOrNull()
                    // KMK <--
                    ?: return@forEach
                database.mergedQueries.insert(
                    infoManga = reference.isInfoManga,
                    getChapterUpdates = reference.getChapterUpdates,
                    chapterSortMode = reference.chapterSortMode.toLong(),
                    chapterPriority = reference.chapterPriority.toLong(),
                    downloadChapters = reference.downloadChapters,
                    mergeId = mergeMangaId,
                    mergeUrl = reference.mergeUrl,
                    mangaId = mergedManga.id,
                    mangaUrl = reference.mangaUrl,
                    mangaSource = reference.mangaSourceId,
                ).awaitAsOneOrNull()
            }
        }
    }

    private suspend fun restoreFlatMetadata(mangaId: Long, flatMetadata: FlatMetadata) {
        // KMK -->
        // Mirrors the previous GetFlatMetadataById/InsertFlatMetadata interactors, which swallowed
        // failures so a broken metadata entry could not abort the whole restore.
        val existing = try {
            mangaMetadataRepository.getMetadataById(mangaId)
        } catch (e: Exception) {
            null
        }
        if (existing != null) return
        try {
            mangaMetadataRepository.insertFlatMetadata(
                flatMetadata.copy(
                    metadata = flatMetadata.metadata.copy(mangaId = mangaId),
                    tags = flatMetadata.tags.map { it.copy(mangaId = mangaId) },
                    titles = flatMetadata.titles.map { it.copy(mangaId = mangaId) },
                ),
            )
        } catch (e: Exception) {
            // Empty
        }
        // KMK <--
    }

    private fun restoreEditedInfo(mangaInfo: CustomMangaInfo?) {
        mangaInfo ?: return
        customMangaRepository.set(mangaInfo)
    }
    // SY <--
}
