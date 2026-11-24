package komikun.download

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
 *
 * [chapterUrl] is the identity token: `chapter.id` has no AUTOINCREMENT, so SQLite can hand a freed id
 * to a *different* chapter of the same manga after a delete/insert cycle. A row keyed by the id alone
 * would then name a chapter it was never queued for; the url survives that reuse, so a sweep compares
 * it before it deletes anything.
 */
data class PendingDownloadDeletion(
    val chapterId: Long,
    val mangaId: Long,
    val firstHiddenAt: Instant,
    val chapterUrl: String,
)
