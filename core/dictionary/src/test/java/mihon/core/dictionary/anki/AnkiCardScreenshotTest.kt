// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.TermResult
import org.junit.jupiter.api.Test

/**
 * End-to-end gate for the `{screenshot}` field write (plan 05 §B4): the bytes the reader captured
 * must land in **every** field whose template asks for `{screenshot}`, and an absent capture must
 * leave those fields empty rather than pointing at a media file that was never stored.
 *
 * AnkiDroid itself is faked ([AnkiCardBridge]); the ContentResolver/provider path stays T4-only.
 */
class AnkiCardScreenshotTest {

    private val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    // sha1(bytes).take(12): the media name is the content hash, so re-mining the same page reuses
    // one Anki media file instead of piling up copies.
    private val filename = "komikun_dd5783bcf1e9.webp"

    private fun profile() = AnkiProfile(
        id = "p1",
        name = "Default",
        ankiEnabled = true,
        ankiDeck = "Mining",
        ankiModel = "Lapis",
        ankiFieldMap = """{"Expression":"{expression}","Picture":"{screenshot}","Extras":"<b>{screenshot}</b>"}""",
        ankiDupCheck = false,
    )

    private fun result() = LookupResult(
        matched = "猫",
        deinflected = "猫",
        process = emptyList(),
        term = TermResult(
            expression = "猫",
            reading = "ねこ",
            rules = "",
            score = 1,
            glossaries = emptyList(),
            frequencies = emptyList(),
            pitches = emptyList(),
        ),
        preprocessorSteps = 0,
    )

    @Test
    fun `captured screenshot is stored once and written to every screenshot field`() = runBlocking<Unit> {
        val bridge = FakeAnkiBridge()

        val outcome = AnkiCardCreator.addToAnki(
            context = mockk<Context>(relaxed = true),
            result = result(),
            profile = profile(),
            screenshotBytes = bytes,
            bridge = bridge,
        )

        outcome shouldBe AnkiResult.Success(noteId = 1L)
        bridge.storedMedia shouldBe listOf(filename to bytes)
        bridge.addedFields.getValue("Picture") shouldBe """<img src="$filename" />"""
        bridge.addedFields.getValue("Extras") shouldBe """<b><img src="$filename" /></b>"""
        // The rest of the card still renders: the screenshot must not displace the term.
        bridge.addedFields.getValue("Expression") shouldBe "猫"
    }

    @Test
    fun `absent screenshot leaves the fields empty and stores no media`() = runBlocking<Unit> {
        val bridge = FakeAnkiBridge()

        AnkiCardCreator.addToAnki(
            context = mockk<Context>(relaxed = true),
            result = result(),
            profile = profile(),
            bridge = bridge,
        )

        bridge.storedMedia shouldBe emptyList()
        bridge.addedFields.getValue("Picture") shouldBe ""
        bridge.addedFields.getValue("Extras") shouldBe "<b></b>"
        bridge.addedFields.getValue("Expression") shouldBe "猫"
    }

    /** Records what the card builder writes; the real bridge talks to AnkiDroid's provider. */
    private class FakeAnkiBridge : AnkiCardBridge {
        val storedMedia = mutableListOf<Pair<String, ByteArray>>()
        var addedFields: Map<String, String> = emptyMap()

        override fun hasPermission(): Boolean = true

        override suspend fun ensureDefaultDeckName(): String = "Default"

        override suspend fun ensureLapisModelName(): String = "Lapis"

        override suspend fun getDeckId(deckName: String): Long = 1L

        override suspend fun findNotes(expression: String, modelName: String?, deckId: Long?): List<Long> = emptyList()

        override suspend fun addNote(
            deckName: String,
            modelName: String,
            fields: Map<String, String>,
            tags: List<String>,
        ): Long {
            addedFields = fields
            return 1L
        }

        override suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>): Any? = null

        override suspend fun storeMedia(filename: String, data: ByteArray): String {
            storedMedia += filename to data
            return filename
        }

        override suspend fun storeMedia(source: AnkiSentenceAudioSource): String = error("no sentence audio in a manga card")

        override fun triggerSync() = Unit
    }
}
// KUN <--
