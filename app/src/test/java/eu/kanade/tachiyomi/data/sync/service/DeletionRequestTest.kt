package eu.kanade.tachiyomi.data.sync.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
    // KUN <--
}
