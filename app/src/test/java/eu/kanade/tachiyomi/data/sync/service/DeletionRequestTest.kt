package eu.kanade.tachiyomi.data.sync.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.repository.PendingHistoryDeletion

/**
 * The SyncYomi v2 deletions body. The server reads top-level `items` with a `kind` and a `key`, so
 * the shape and the field order are part of the wire contract.
 */
class DeletionRequestTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `deletion items list chapters before manga`() {
        assertEquals(
            listOf(
                DeletionItem("chapter", "c1"),
                DeletionItem("manga", "1|/m"),
            ),
            deletionItems(chapters = listOf("c1"), mangas = listOf("1|/m")),
        )
    }

    @Test
    fun `the deletions body is the canonical items JSON`() {
        val body = json.encodeToString(
            DeletionRequest.serializer(),
            DeletionRequest(deletionItems(chapters = listOf("c1"), mangas = listOf("1|/m"))),
        )

        assertEquals(
            """{"items":[{"kind":"chapter","key":"c1"},{"kind":"manga","key":"1|/m"}],""" +
                """"deletedChapters":[]}""",
            body,
        )
    }

    @Test
    fun `manga-only deletions carry just the manga items`() {
        val body = json.encodeToString(
            DeletionRequest.serializer(),
            DeletionRequest(deletionItems(chapters = emptyList(), mangas = listOf("7|/m"))),
        )

        assertEquals("""{"items":[{"kind":"manga","key":"7|/m"}],"deletedChapters":[]}""", body)
    }

    @Test
    fun `the transition body carries the legacy deletedChapters shape beside the items`() {
        // An older server 2xxes the request and ignores `items`; it only tombstones the keys in
        // `deletedChapters`. Sending both keeps a still-unupgraded server honest for the chapters,
        // and a server that reads both merges and dedupes them.
        val body = json.encodeToString(
            DeletionRequest.serializer(),
            DeletionRequest(
                items = deletionItems(chapters = listOf("c1", "c2"), mangas = listOf("1|/m")),
                deletedChapters = listOf("c1", "c2"),
            ),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            listOf("chapter" to "c1", "chapter" to "c2", "manga" to "1|/m"),
            root.getValue("items").jsonArray.map {
                it.jsonObject.getValue("kind").jsonPrimitive.content to
                    it.jsonObject.getValue("key").jsonPrimitive.content
            },
        )
        assertEquals(
            listOf("c1", "c2"),
            root.getValue("deletedChapters").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    // KUN -->
    @Test
    fun `a history deletion is a history item and never a legacy chapter key`() {
        val chapterKey = "1|/m\u001f/m/ch1"
        val historyKey = "1|/m\u001f/m/ch2"
        val body = json.encodeToString(
            DeletionRequest.serializer(),
            DeletionRequest(
                items = deletionItems(
                    chapters = listOf(chapterKey),
                    mangas = listOf("1|/m"),
                    histories = listOf(historyKey),
                ),
                deletedChapters = listOf(chapterKey),
            ),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            listOf("chapter" to chapterKey, "history" to historyKey, "manga" to "1|/m"),
            root.getValue("items").jsonArray.map {
                it.jsonObject.getValue("kind").jsonPrimitive.content to
                    it.jsonObject.getValue("key").jsonPrimitive.content
            },
        )
        // The legacy list is the chapter shape: an old server reads every entry in it as a chapter
        // tombstone, so a history key there would delete the chapter instead of its history row.
        assertEquals(
            listOf(chapterKey),
            root.getValue("deletedChapters").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a response that reports applied acknowledges every kind`() {
        val chapters = listOf("c1")
        val mangas = listOf("1|/m")
        val histories = listOf("1|/m\u001f/m/h")

        val acknowledged = acknowledgedDeletions("""{"acknowledged":3,"applied":2}""", chapters, mangas, histories)

        assertEquals(chapters, acknowledged.chapters.toList())
        assertEquals(mangas, acknowledged.mangas.toList())
        assertEquals(histories, acknowledged.histories.toList())
    }

    @Test
    fun `a legacy acknowledgement keeps manga and history pending and clears the chapters`() {
        val chapters = listOf("c1")
        val mangas = listOf("1|/m")
        val histories = listOf("1|/m\u001f/m/h")

        // A pre-#335 server ignores `items` and answers `{"acknowledged":n}`; it only applies the
        // legacy `deletedChapters` list, which the request still carries.
        val acknowledged = acknowledgedDeletions("""{"acknowledged":3}""", chapters, mangas, histories)

        assertEquals(chapters, acknowledged.chapters.toList())
        assertEquals(emptyList<String>(), acknowledged.mangas.toList())
        assertEquals(emptyList<String>(), acknowledged.histories.toList())
    }

    @Test
    fun `a fully deduplicated batch still confirms the batch`() {
        // `applied` counts the rows that changed, not the entries processed: a retry of keys the
        // server already tombstoned answers 0 and must still clear the queue.
        assertTrue(deletionResponseAppliesEveryKind("""{"acknowledged":2,"applied":0}"""))
        assertFalse(deletionResponseAppliesEveryKind("""{"acknowledged":2}"""))
        assertFalse(deletionResponseAppliesEveryKind(""))
        assertFalse(deletionResponseAppliesEveryKind("<html>not json</html>"))
    }

    @Test
    fun `an unreadable body is not a confirmation`() {
        // the caller hands null over when reading the 2xx body itself failed
        assertFalse(deletionResponseAppliesEveryKind(null))

        val acknowledged = acknowledgedDeletions(
            null,
            chapters = listOf("c1"),
            mangas = listOf("1|/m"),
            histories = listOf("1|/m\u001f/m/h"),
        )
        assertEquals(listOf("c1"), acknowledged.chapters.toList())
        assertEquals(emptyList<String>(), acknowledged.mangas.toList())
        assertEquals(emptyList<String>(), acknowledged.histories.toList())
    }

    @Test
    fun `history tombstone keys are built from the urls the queue row carries`() {
        // The sync reads these rows from the database, so the key has to come from the joined
        // source, manga url and chapter url — a swapped manga/chapter url names another entry.
        val rows = listOf(
            PendingHistoryDeletion(historyId = 101L, sourceId = 7L, mangaUrl = "/m", chapterUrl = "/m/ch1"),
            PendingHistoryDeletion(historyId = 102L, sourceId = 8L, mangaUrl = "/n", chapterUrl = "/n/ch9"),
        )

        assertEquals(
            listOf("7|/m\u001f/m/ch1", "8|/n\u001f/n/ch9"),
            historyDeletionKeys(rows),
        )
    }

    @Test
    fun `a page of queued history rows becomes history items only`() {
        // A "clear all" is sent in pages, so the body of every page after the first carries history
        // items and no legacy chapter key: a history key in that list would tombstone the chapter.
        val rows = (1..HISTORY_DELETION_BATCH).map { index ->
            PendingHistoryDeletion(
                historyId = index.toLong(),
                sourceId = 1L,
                mangaUrl = "/m",
                chapterUrl = "/m/ch$index",
            )
        }

        val body = json.encodeToString(
            DeletionRequest.serializer(),
            DeletionRequest(items = deletionItems(emptyList(), emptyList(), historyDeletionKeys(rows))),
        )

        val root = Json.parseToJsonElement(body).jsonObject
        val items = root.getValue("items").jsonArray.map {
            it.jsonObject.getValue("kind").jsonPrimitive.content
        }
        assertEquals(HISTORY_DELETION_BATCH, items.size)
        assertEquals(setOf("history"), items.toSet())
        assertEquals(0, root.getValue("deletedChapters").jsonArray.size)
    }
    // KUN <--
}
