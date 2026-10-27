package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderSessionTargetTest {

    @Test
    fun `a request for a chapter wins over the chapter the session recorded`() {
        // The reported bug: tapping chapter 1 while the reader sat on chapter 4 opened chapter 4,
        // because the session's `chapter_id` was treated as the answer instead of the request.
        resolveChapterToOpen(
            requestedChapterId = 1L,
            lastReadChapterId = 4L,
            explicitRequest = true,
        ) shouldBe 1L
    }

    @Test
    fun `restoring a process-killed session resumes the chapter it was reading`() {
        // The recorded chapter is the position the user actually left off at; a restore must not
        // rewind to the chapter that was originally launched.
        resolveChapterToOpen(
            requestedChapterId = 1L,
            lastReadChapterId = 4L,
            explicitRequest = false,
        ) shouldBe 4L
    }

    @Test
    fun `a session with no recorded chapter opens the requested one`() {
        resolveChapterToOpen(
            requestedChapterId = 1L,
            lastReadChapterId = -1L,
            explicitRequest = false,
        ) shouldBe 1L
        resolveChapterToOpen(
            requestedChapterId = 1L,
            lastReadChapterId = -1L,
            explicitRequest = true,
        ) shouldBe 1L
    }

    @Test
    fun `an intent without a manga or chapter is not a request`() {
        ReaderSessionTarget(mangaId = 42L, chapterId = -1L).isValid shouldBe false
        ReaderSessionTarget(mangaId = -1L, chapterId = 7L).isValid shouldBe false
        ReaderSessionTarget(mangaId = 42L, chapterId = 7L).isValid shouldBe true
        ReaderSessionTarget(mangaId = 42L, chapterId = 7L, page = 3).isValid shouldBe true
    }
}
