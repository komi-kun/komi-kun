package tachiyomi.domain.download.model

import kotlin.time.Instant

/**
 * A downloaded chapter that a persistent chapter filter hid from the chapter list of its library
 * manga, with the time it was *first* seen hidden.
 *
 * The pair is what makes the grace period honest: [firstHiddenAt] survives every later pass that
 * still sees the chapter hidden, so a download that keeps disappearing for a moment at a time is
 * never deleted, and only a chapter that has stayed hidden for the whole period is a candidate.
 * [mangaId] lets the whole queue of one manga be dropped at once (it left the library, it was
 * deleted, its filter changed).
 *
 * Neither id is a foreign key: the chapter or the manga may be deleted by a library sync while the
 * row is pending, and the row stays readable and droppable when it is.
 */
data class PendingDownloadDeletion(
    val chapterId: Long,
    val mangaId: Long,
    val firstHiddenAt: Instant,
)
