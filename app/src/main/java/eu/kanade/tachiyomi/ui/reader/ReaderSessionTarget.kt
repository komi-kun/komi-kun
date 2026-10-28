package eu.kanade.tachiyomi.ui.reader

/**
 * The manga/chapter/page a reader intent asks for.
 *
 * [page] is the page the caller wants to land on, or null to use the chapter's own restore
 * position.
 */
data class ReaderSessionTarget(
    val mangaId: Long,
    val chapterId: Long,
    val page: Int? = null,
) {
    /**
     * Whether this target names a chapter at all. An intent can reach the reader without extras
     * (the `REMOTE_ACTION` filter, for one), and such an intent must leave a running session alone.
     */
    val isValid: Boolean
        get() = mangaId != -1L && chapterId != -1L
}

/**
 * The chapter a reader session opens.
 *
 * [requestedChapterId] is the chapter the reader was asked for; [lastReadChapterId] is the chapter
 * the session itself recorded (`chapter_id` in its saved state), which exists to restore a
 * process-killed session. An [explicitRequest] always wins, so a tap opens the chapter it names.
 * Without that rule a request delivered to an already running reader is answered with the chapter
 * the session happened to be on — the activity is `singleTask`, so a second launch reaches
 * `ReaderActivity.onNewIntent` instead of creating a new reader — which is "tap chapter 1, land on
 * chapter 4".
 */
internal fun resolveChapterToOpen(
    requestedChapterId: Long,
    lastReadChapterId: Long,
    explicitRequest: Boolean,
): Long {
    if (explicitRequest) return requestedChapterId
    return lastReadChapterId.takeUnless { it == -1L } ?: requestedChapterId
}
