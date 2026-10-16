// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T1 gate for [AnkiDroidBridge]'s pure helpers (plan 05 §A3): the duplicate-probe checksum must
 * match AnkiDroid's `stripHTMLMedia` + SHA-1 truncation, and field-name parsing must handle both
 * the JSON-array and `\u001f` provider formats.
 */
class AnkiDroidBridgeHelpersTest {

    @Test
    fun `fieldChecksum is the first 8 hex of the SHA-1 of the stripped field`() {
        AnkiDroidBridge.fieldChecksum("test") shouldBe "a94a8fe5".toLong(16)
    }

    @Test
    fun `fieldChecksum ignores HTML markup`() {
        AnkiDroidBridge.fieldChecksum("<b>test</b>") shouldBe AnkiDroidBridge.fieldChecksum("test")
        AnkiDroidBridge.fieldChecksum("a&amp;b") shouldBe AnkiDroidBridge.fieldChecksum("a&b")
    }

    @Test
    fun `stripHTML removes tags and decodes entities`() {
        AnkiDroidBridge.stripHTML("a&amp;b&nbsp;c") shouldBe "a&b c"
        AnkiDroidBridge.stripHTML("&#39;x&#x27;") shouldBe "'x'"
        AnkiDroidBridge.stripHTML("<style>p{}</style>text") shouldBe "text"
    }

    @Test
    fun `stripHTMLMedia keeps the image filename`() {
        AnkiDroidBridge.stripHTMLMedia("""<img src="x.png">""") shouldBe " x.png "
    }

    @Test
    fun `parseFieldNames handles a JSON array of names and objects`() {
        AnkiDroidBridge.parseFieldNames("""["Expression","Reading"]""") shouldBe listOf("Expression", "Reading")
        AnkiDroidBridge.parseFieldNames("""[{"name":"Expression"}]""") shouldBe listOf("Expression")
    }

    @Test
    fun `parseFieldNames handles the field-separator format`() {
        AnkiDroidBridge.parseFieldNames("Expression\u001fReading") shouldBe listOf("Expression", "Reading")
    }

    @Test
    fun `sanitizePreferredBaseName strips unsafe characters`() {
        AnkiDroidBridge.sanitizePreferredBaseName("a b/c!") shouldBe "a_b_c"
        AnkiDroidBridge.sanitizePreferredBaseName("!!!") shouldBe "komikun_media"
    }
}
// KUN <--
