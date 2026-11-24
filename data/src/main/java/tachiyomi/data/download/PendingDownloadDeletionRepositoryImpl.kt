package tachiyomi.data.download

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.download.model.PendingDownloadDeletion
import tachiyomi.domain.download.repository.PendingDownloadDeletionRepository
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PendingDownloadDeletionRepositoryImpl(
    private val database: Database,
) : PendingDownloadDeletionRepository {

    override suspend fun insertAll(pendingDeletions: List<PendingDownloadDeletion>) {
        if (pendingDeletions.isEmpty()) return
        database.transaction {
            pendingDeletions.forEach {
                database.pendingDownloadDeletionQueries.insert(
                    chapterId = it.chapterId,
                    mangaId = it.mangaId,
                    firstHiddenAt = it.firstHiddenAt.toEpochMilliseconds(),
                )
            }
        }
    }

    override suspend fun deleteByChapterIds(chapterIds: List<Long>) {
        // One bind variable per id, so a whole library's worth needs chunking to fit SQLite's
        // variable limit; the transaction keeps the chunks all-or-nothing.
        database.transaction {
            chapterIds.chunked(MAX_BOUND_IDS).forEach {
                database.pendingDownloadDeletionQueries.deleteByChapterIds(chapterIds = it)
            }
        }
    }

    override suspend fun deleteByMangaIds(mangaIds: List<Long>) {
        database.transaction {
            mangaIds.chunked(MAX_BOUND_IDS).forEach {
                database.pendingDownloadDeletionQueries.deleteByMangaIds(mangaIds = it)
            }
        }
    }

    override suspend fun deleteAll() {
        database.pendingDownloadDeletionQueries.deleteAll()
    }

    override suspend fun selectExpired(cutoff: Instant): List<PendingDownloadDeletion> {
        return database.pendingDownloadDeletionQueries
            .selectExpired(cutoff = cutoff.toEpochMilliseconds(), mapper = pendingDownloadDeletionMapper)
            .awaitAsList()
    }

    override suspend fun selectAll(): List<PendingDownloadDeletion> {
        return database.pendingDownloadDeletionQueries
            .selectAll(pendingDownloadDeletionMapper)
            .awaitAsList()
    }
}

private const val MAX_BOUND_IDS = 900
