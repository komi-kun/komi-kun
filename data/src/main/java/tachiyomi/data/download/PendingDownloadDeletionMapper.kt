package tachiyomi.data.download

import tachiyomi.domain.download.model.PendingDownloadDeletion
import kotlin.time.Instant

/**
 * The table stores `first_hidden_at` as epoch milliseconds (INTEGER); the domain speaks [Instant].
 */
val pendingDownloadDeletionMapper: (Long, Long, Long) -> PendingDownloadDeletion =
    { chapterId, mangaId, firstHiddenAt ->
        PendingDownloadDeletion(
            chapterId = chapterId,
            mangaId = mangaId,
            firstHiddenAt = Instant.fromEpochMilliseconds(firstHiddenAt),
        )
    }
