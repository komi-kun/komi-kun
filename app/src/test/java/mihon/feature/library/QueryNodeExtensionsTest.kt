package mihon.feature.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class QueryNodeExtensionsTest {

    private fun item(
        id: Long = 1L,
        sourceId: Long = 1L,
        sourceName: String = "MangaDex",
        title: String = "Title",
    ): LibraryItem {
        val manga = Manga.create().copy(id = id, source = sourceId, ogTitle = title)
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = manga,
                categories = listOf(0L),
                totalChapters = 0L,
                readCount = 0L,
                bookmarkCount = 0L,
                bookmarkReadCount = 0L,
                chapterFlags = 0L,
                latestUpload = null,
                chapterFetchedAt = null,
                lastRead = null,
            ),
            sourceName = sourceName,
            sourceLanguage = "en",
            badges = LibraryItem.Badges(
                downloadCount = 0,
                unreadCount = 0L,
                isLocal = false,
                sourceLanguage = "en",
            ),
        )
    }

    private fun matches(query: String, item: LibraryItem): Boolean = QueryNode.from(query).matches(item)

    @Test
    fun `id colon is id equality`() {
        val target = item(id = 123L)
        matches("id:123", target) shouldBe true
        matches("id=123", target) shouldBe true
        matches("id:124", target) shouldBe false
        matches("-id:123", target) shouldBe false
    }

    @Test
    fun `numeric src matches the source id`() {
        val target = item(id = 5L, sourceId = 123L, sourceName = "MangaDex")
        matches("src:123", target) shouldBe true
        matches("srcid:123", target) shouldBe true
        matches("src:124", target) shouldBe false
        // It must not match a source *name* that happens to contain the digits
        matches("src:123", item(id = 5L, sourceId = 7L, sourceName = "Source 123")) shouldBe false
    }

    @Test
    fun `non numeric src matches the source name`() {
        val target = item(sourceId = 7L, sourceName = "MangaDex")
        matches("src:dex", target) shouldBe true
        // A numeric term is resolved as a source id, so it does not match this source
        matches("src:123", target) shouldBe false
    }

    @Test
    fun `src local matches the local source`() {
        // LocalSource.ID is 0; the name deliberately does not contain "local"
        matches("src:local", item(sourceId = 0L, sourceName = "MangaDex")) shouldBe true
        matches("src:local", item(sourceId = 123L, sourceName = "MangaDex")) shouldBe false
    }

    @Test
    fun `wildcards match substrings`() {
        val target = item(title = "Foobar")
        matches("foo*", target) shouldBe true
        matches("foo%", target) shouldBe true
        matches("f?o", target) shouldBe true
        matches("*bar", target) shouldBe true
        matches("f*z", target) shouldBe false
    }

    @Test
    fun `plain terms stay substring matches`() {
        matches("oob", item(title = "Foobar")) shouldBe true
        matches("nope", item(title = "Foobar")) shouldBe false
    }
}
