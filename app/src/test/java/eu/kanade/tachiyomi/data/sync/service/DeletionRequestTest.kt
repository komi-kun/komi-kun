package eu.kanade.tachiyomi.data.sync.service

import kotlinx.serialization.json.Json
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
            """{"items":[{"kind":"chapter","key":"c1"},{"kind":"manga","key":"1|/m"}]}""",
            body,
        )
    }

    @Test
    fun `manga-only deletions carry just the manga items`() {
        val body = json.encodeToString(
            DeletionRequest.serializer(),
            DeletionRequest(deletionItems(chapters = emptyList(), mangas = listOf("7|/m"))),
        )

        assertEquals("""{"items":[{"kind":"manga","key":"7|/m"}]}""", body)
    }
}
