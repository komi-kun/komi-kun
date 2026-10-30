// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * T1 gate for field-map parsing + [LapisPreset] (plan 05 §A3/B7): user-configured
 * `field → template` maps and the Lapis-like default application.
 */
class AnkiCardMappingTest {

    @Test
    fun `parseFieldMap reads a JSON object`() {
        AnkiCardCreator.parseFieldMap("""{"Expression":"{expression}","Sentence":"{cloze-body}"}""") shouldBe
            mapOf("Expression" to "{expression}", "Sentence" to "{cloze-body}")
    }

    @Test
    fun `parseFieldMap degrades empty and malformed input to an empty map`() {
        AnkiCardCreator.parseFieldMap("") shouldBe emptyMap()
        AnkiCardCreator.parseFieldMap("{}") shouldBe emptyMap()
        AnkiCardCreator.parseFieldMap("not json") shouldBe emptyMap()
    }

    @Test
    fun `Lapis default field map covers the core fields`() {
        val json = LapisPreset.defaultFieldMapJson
        json shouldContain "\"Expression\""
        json shouldContain "{expression}"
        json shouldContain "{furigana-plain}"
    }

    @Test
    fun `defaultFieldMapFor filters to the model's fields`() {
        val map = LapisPreset.defaultFieldMapFor(listOf("Expression", "MainDefinition", "Sentence"))
        map.keys shouldBe setOf("Expression", "MainDefinition", "Sentence")
    }

    @Test
    fun `hasCoreFields detects a lapis-like model`() {
        LapisPreset.hasCoreFields(listOf("Expression", "MainDefinition", "Sentence")) shouldBe true
        LapisPreset.hasCoreFields(listOf("Front", "Back")) shouldBe false
    }

    @Test
    fun `isBundledModelName recognises Lapis variants`() {
        LapisPreset.isBundledModelName("Lapis") shouldBe true
        LapisPreset.isBundledModelName("Lapis (Komikun)") shouldBe true
        LapisPreset.isBundledModelName("Custom") shouldBe false
    }

    @Test
    fun `applyDefaults fills a blank map only for lapis-like models`() {
        val fields = listOf("Expression", "MainDefinition", "Sentence")
        LapisPreset.applyDefaults("Lapis", fields, "{}") shouldContain "{expression}"
        // A non-lapis model whose fields do not overlap the Lapis core is left untouched.
        LapisPreset.applyDefaults("Custom", listOf("Front", "Back"), "{}") shouldBe "{}"
        LapisPreset.applyDefaults("Custom", listOf("Front", "Back"), "{}") shouldNotContain "{expression}"
    }

    @Test
    fun `isBlankFieldMap recognises the empty maps`() {
        LapisPreset.isBlankFieldMap("") shouldBe true
        LapisPreset.isBlankFieldMap("{}") shouldBe true
        LapisPreset.isBlankFieldMap("""{"a":"b"}""") shouldBe false
    }

    @Test
    fun `blankExtensionUrl only blanks chrome-extension URLs`() {
        AnkiCardCreator.blankExtensionUrl("chrome-extension://abc") shouldBe ""
        AnkiCardCreator.blankExtensionUrl("https://x") shouldBe "https://x"
    }

    @Test
    fun `normalizeAnkiTag replaces whitespace and drops blanks`() {
        AnkiCardCreator.normalizeAnkiTag(" a b ") shouldBe "a_b"
        AnkiCardCreator.normalizeAnkiTag("   ") shouldBe null
    }

    @Test
    fun `mergeFieldValue respects overwrite modes`() {
        AnkiCardCreator.mergeFieldValue("old", "new", "overwrite") shouldBe "new"
        AnkiCardCreator.mergeFieldValue("old", "new", "append") shouldBe "old<br>new"
        AnkiCardCreator.mergeFieldValue("old", "new", "prepend") shouldBe "new<br>old"
        AnkiCardCreator.mergeFieldValue("old", "new", "if_empty") shouldBe "old"
        AnkiCardCreator.mergeFieldValue("", "new", "if_empty") shouldBe "new"
        AnkiCardCreator.mergeFieldValue("old", "new", "skip") shouldBe "old"
        AnkiCardCreator.mergeFieldValue("old", "", "append") shouldBe "old"
        AnkiCardCreator.mergeFieldValue("", "new", "append") shouldBe "new"
    }
}
// KUN <--
