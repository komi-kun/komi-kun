// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class KanaConverterTest {

    @Test
    fun `toKana converts basic romaji to hiragana`() {
        KanaConverter.toKana("hiragana") shouldBe "ひらがな"
        KanaConverter.toKana("konnnichiwa") shouldBe "こんにちわ"
        KanaConverter.toKana("kon'nichiwa") shouldBe "こんにちわ"
        KanaConverter.toKana("kitte") shouldBe "きって"
        KanaConverter.toKana("gakko-") shouldBe "がっこー"
    }

    @Test
    fun `toKana converts uppercase romaji to katakana`() {
        KanaConverter.toKana("KATAKANA") shouldBe "カタカナ"
        KanaConverter.toKana("KA") shouldBe "カ"
        KanaConverter.toKana("NYA") shouldBe "ニャ"
        KanaConverter.toKana("TTE") shouldBe "ッテ"
        KanaConverter.toKana("KITTE") shouldBe "キッテ"
    }

    @Test
    fun `toKana converts punctuation and symbols`() {
        KanaConverter.toKana("ka-do.") shouldBe "かーど。"
        KanaConverter.toKana("sushi,") shouldBe "すし、"
        KanaConverter.toKana("nani!?") shouldBe "なに！？"
    }

    @Test
    fun `toKanaIME preserves incomplete single consonant`() {
        KanaConverter.toKanaIME("k", 1) shouldBe ("k" to 1)
        KanaConverter.toKanaIME("s", 1) shouldBe ("s" to 1)
        KanaConverter.toKanaIME("t", 1) shouldBe ("t" to 1)
        KanaConverter.toKanaIME("sh", 2) shouldBe ("sh" to 2)
    }

    @Test
    fun `toKanaIME converts completed syllable and adjusts cursor`() {
        KanaConverter.toKanaIME("ka", 2) shouldBe ("か" to 1)
        KanaConverter.toKanaIME("shi", 3) shouldBe ("し" to 1)
        KanaConverter.toKanaIME("tsu", 3) shouldBe ("つ" to 1)
        KanaConverter.toKanaIME("taberu", 6) shouldBe ("たべる" to 3)
    }

    @Test
    fun `toKanaIME hides single n before cursor`() {
        // Single 'n' before cursor should NOT convert to 'ん' yet
        KanaConverter.toKanaIME("n", 1) shouldBe ("n" to 1)
        KanaConverter.toKanaIME("kan", 3) shouldBe ("かn" to 2)
        KanaConverter.toKanaIME("hon", 3) shouldBe ("ほn" to 2)
        KanaConverter.toKanaIME("N", 1) shouldBe ("N" to 1)
    }

    @Test
    fun `toKanaIME converts double n to ん`() {
        KanaConverter.toKanaIME("nn", 2) shouldBe ("ん" to 1)
        KanaConverter.toKanaIME("kann", 4) shouldBe ("かん" to 2)
        KanaConverter.toKanaIME("かnn", 3) shouldBe ("かん" to 2)
        KanaConverter.toKanaIME("n'", 2) shouldBe ("ん" to 1)
    }

    @Test
    fun `toKanaIME converts n followed by vowel`() {
        KanaConverter.toKanaIME("kana", 4) shouldBe ("かな" to 2)
        KanaConverter.toKanaIME("かna", 3) shouldBe ("かな" to 2)
        KanaConverter.toKanaIME("koni", 4) shouldBe ("こに" to 2)
    }

    @Test
    fun `toKanaIME hides ny before cursor`() {
        KanaConverter.toKanaIME("ny", 2) shouldBe ("ny" to 2)
        KanaConverter.toKanaIME("kany", 4) shouldBe ("かny" to 3)
        KanaConverter.toKanaIME("NY", 2) shouldBe ("NY" to 2)
    }

    @Test
    fun `toKanaIME converts completed nya nyu nyo`() {
        KanaConverter.toKanaIME("nya", 3) shouldBe ("にゃ" to 2)
        KanaConverter.toKanaIME("nyu", 3) shouldBe ("にゅ" to 2)
        KanaConverter.toKanaIME("nyo", 3) shouldBe ("にょ" to 2)
        KanaConverter.toKanaIME("NYA", 3) shouldBe ("ニャ" to 2)
    }

    @Test
    fun `toKanaIME handles double consonants sokuon`() {
        KanaConverter.toKanaIME("tt", 2) shouldBe ("っt" to 2)
        KanaConverter.toKanaIME("tte", 3) shouldBe ("って" to 2)
        KanaConverter.toKanaIME("kitte", 5) shouldBe ("きって" to 3)
        KanaConverter.toKanaIME("kk", 2) shouldBe ("っk" to 2)
        KanaConverter.toKanaIME("kki", 3) shouldBe ("っき" to 2)
    }

    @Test
    fun `toKanaIME handles mid-string insertion`() {
        // User inserts "k" inside "あう" at index 1 -> "あkう", cursor 2
        KanaConverter.toKanaIME("あkう", 2) shouldBe ("あkう" to 2)
        // User then types "a" -> "あkaう", cursor 3
        KanaConverter.toKanaIME("あkaう", 3) shouldBe ("あかう" to 2)
    }

    @Test
    fun `toKanaIME handles empty and edge cursor positions`() {
        KanaConverter.toKanaIME("", 0) shouldBe ("" to 0)
        KanaConverter.toKanaIME("ka", 0) shouldBe ("か" to 0)
        KanaConverter.toKanaIME("ka", 10) shouldBe ("か" to 1)
    }

    @Test
    fun `convertAlphabeticToKana converts lone n and leaves non-romaji untouched`() {
        KanaConverter.convertAlphabeticToKana("nihon") shouldBe "にほん"
        KanaConverter.convertAlphabeticToKana("kanji 漢字") shouldBe "かんじ 漢字"
        KanaConverter.convertAlphabeticToKana("ka-do") shouldBe "かーど"
    }

    // KUN -->
    // wanakana case rule: katakana per *syllable* when every character of its romaji source is
    // uppercase. Expectations below are wanakana 5.3.1 `toKana` output (verified against the
    // published bundle), except where this port intentionally keeps Yomitan's `nn` handling.
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("lowercaseRomaji")
    fun `lowercase romaji converts to hiragana`(romaji: String, kana: String) {
        KanaConverter.toKana(romaji) shouldBe kana
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("uppercaseRomaji")
    fun `uppercase romaji converts to katakana`(romaji: String, kana: String) {
        KanaConverter.toKana(romaji) shouldBe kana
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("mixedCaseRomaji")
    fun `mixed case romaji is decided per syllable`(romaji: String, kana: String) {
        KanaConverter.toKana(romaji) shouldBe kana
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("specialRomaji")
    fun `n nn sokuon and long vowels convert in both cases`(romaji: String, kana: String) {
        KanaConverter.toKana(romaji) shouldBe kana
    }

    companion object {
        @JvmStatic
        fun lowercaseRomaji() = listOf(
            Arguments.of("ka", "か"),
            Arguments.of("shi", "し"),
            Arguments.of("tsu", "つ"),
            Arguments.of("chi", "ち"),
            Arguments.of("fu", "ふ"),
            Arguments.of("ji", "じ"),
            Arguments.of("sushi", "すし"),
            Arguments.of("nihon", "にほん"),
            Arguments.of("toukyou", "とうきょう"),
            Arguments.of("kitte", "きって"),
            Arguments.of("ou", "おう"),
        )

        @JvmStatic
        fun uppercaseRomaji() = listOf(
            Arguments.of("KA", "カ"),
            Arguments.of("SHI", "シ"),
            Arguments.of("TSU", "ツ"),
            Arguments.of("CHI", "チ"),
            Arguments.of("FU", "フ"),
            Arguments.of("JI", "ジ"),
            Arguments.of("SUSHI", "スシ"),
            Arguments.of("NIHON", "ニホン"),
            Arguments.of("TOUKYOU", "トウキョウ"),
            Arguments.of("KITTE", "キッテ"),
            Arguments.of("OU", "オウ"),
        )

        @JvmStatic
        fun mixedCaseRomaji() = listOf(
            // Case change on a syllable boundary: each syllable keeps its own case.
            Arguments.of("KAra", "カら"),
            Arguments.of("kaRA", "かラ"),
            Arguments.of("Aka", "アか"),
            Arguments.of("aKA", "あカ"),
            Arguments.of("KAnA", "カな"),
            Arguments.of("kataKANA", "かたカナ"),
            Arguments.of("KAta", "カた"),
            // Case change inside a syllable: not all-uppercase, so the syllable is hiragana.
            Arguments.of("Ka", "か"),
            Arguments.of("kA", "か"),
            Arguments.of("Kara", "から"),
            Arguments.of("kAra", "から"),
            Arguments.of("KaN", "かン"),
        )

        @JvmStatic
        fun specialRomaji() = listOf(
            Arguments.of("n", "ん"),
            Arguments.of("N", "ン"),
            Arguments.of("nn", "ん"),
            Arguments.of("NN", "ン"),
            Arguments.of("kan", "かん"),
            Arguments.of("KAN", "カン"),
            Arguments.of("konnnichiwa", "こんにちわ"),
            Arguments.of("kon'nichiwa", "こんにちわ"),
            Arguments.of("kka", "っか"),
            Arguments.of("KKA", "ッカ"),
            Arguments.of("katte", "かって"),
            Arguments.of("KATTE", "カッテ"),
            Arguments.of("tte", "って"),
            Arguments.of("TTE", "ッテ"),
            Arguments.of("ka-", "かー"),
            Arguments.of("KA-", "カー"),
            Arguments.of("ko-hi-", "こーひー"),
            Arguments.of("KO-HI-", "コーヒー"),
            Arguments.of("gakko-", "がっこー"),
            Arguments.of("GAKKO-", "ガッコー"),
            Arguments.of("ka-do", "かーど"),
            Arguments.of("KA-DO", "カード"),
        )
    }
    // KUN <--
}
// KUN <--
