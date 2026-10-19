// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.yomitan

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import mihon.core.dictionary.audio.AudioSourceTypes
import mihon.core.dictionary.model.DictionaryProfile
import org.junit.jupiter.api.Test

/**
 * T1 gate for the pure Yomitan settings mapper (`YomitanSettingsMapper`): a real-shaped
 * sanitised fixture, unknown keys, missing groups and a newer `options.version`. No Android, no
 * native engine.
 */
class YomitanSettingsMapperTest {

    private fun fixture(): String =
        javaClass.getResourceAsStream("/yomitan/yomitan-settings-minimal.json")!!
            .bufferedReader()
            .use { it.readText() }

    private fun imported(raw: String = fixture()): YomitanSettingsImport {
        val result = YomitanSettingsMapper.map(raw)
        return (result as? YomitanImportResult.Imported)?.settings
            ?: error("expected a successful import but was $result")
    }

    @Test
    fun `maps a real-shaped settings backup`() {
        val settings = imported()

        settings.formatVersion shouldBe 0
        settings.optionsVersion shouldBe 77
        settings.activeProfileName shouldBe "Alpha"

        settings.profiles.size shouldBe 2
        val alpha = settings.profiles[0]
        alpha.name shouldBe "Alpha"
        alpha.dictionaryOrder shouldBe listOf("Dict A", "Dict B", "Freq A")
        alpha.enabledDictionaries shouldBe setOf("Dict A", "Freq A")
        alpha.languageCode shouldBe "ja"
        alpha.searchResolution shouldBe DictionaryProfile.SEARCH_RESOLUTION_WORD
        alpha.scanResolution shouldBe DictionaryProfile.SCAN_RESOLUTION_WORD
        alpha.isActive shouldBe true
        alpha.dictionaryDisplayModes shouldBe mapOf(
            "Dict A" to DictionaryProfile.DICTIONARY_DISPLAY_ALWAYS_EXPANDED,
            "Dict B" to DictionaryProfile.DICTIONARY_DISPLAY_ALWAYS_COLLAPSED,
            "Freq A" to DictionaryProfile.DICTIONARY_DISPLAY_ALWAYS_EXPANDED,
        )

        val beta = settings.profiles[1]
        beta.name shouldBe "Beta"
        beta.dictionaryOrder shouldBe listOf("Dict B", "Dict A")
        beta.enabledDictionaries shouldBe setOf("Dict B")
        beta.searchResolution shouldBe DictionaryProfile.SEARCH_RESOLUTION_LETTER
        beta.scanResolution shouldBe DictionaryProfile.SCAN_RESOLUTION_CHARACTER
        beta.isActive shouldBe false

        settings.aliases shouldBe mapOf("Dict B" to "Bee")
    }

    @Test
    fun `maps the popup and anki keys komi-kun models`() {
        val settings = imported()

        settings.popup.width shouldBe 420
        settings.popup.height shouldBe 520
        settings.popup.fontSize shouldBe 15
        settings.popup.fontFamily shouldBe "Noto Sans CJK JP"
        settings.popup.themeMode shouldBe "dark"
        settings.popup.customCss shouldNotBe null
        settings.popup.showPitchDiagram shouldBe true
        settings.popup.showPitchNumber shouldBe false
        settings.popup.showPitchText shouldBe true
        settings.popup.showFrequencyAverage shouldBe false
        settings.popup.groupTerms shouldBe true
        settings.popup.compactGlossary shouldBe false

        settings.anki.enabled shouldBe true
        settings.anki.tags shouldBe "fixture-tag"
        settings.anki.duplicateCheck shouldBe true
        settings.anki.duplicateScope shouldBe "collection"
        settings.anki.duplicateAction shouldBe "overwrite"

        settings.audio.enabled shouldBe true
        settings.audio.autoPlay shouldBe false
        settings.audio.volumePercent shouldBe 1
        settings.audio.sources.map { it.type } shouldBe listOf(AudioSourceTypes.JPOD_101)
    }

    @Test
    fun `applied reports describe every mapped key`() {
        val settings = imported()
        val appliedKeys = settings.appliedReports.map { it.key }
        appliedKeys shouldContainAll listOf(
            "Alpha",
            "Beta",
            "alias",
            "general.popupWidth",
            "general.fontSize",
            "general.popupTheme",
            "anki.enable",
            "anki.tags",
            "anki.duplicateScope",
            "anki.duplicateBehavior",
            "audio.enabled",
            "audio.autoPlay",
            "audio.volume",
            "audio.sources",
        )
    }

    @Test
    fun `groups with no komi-kun equivalent are reported as not imported`() {
        val settings = imported()
        val skippedGroups = settings.skippedReports.map { it.group }.toSet()
        skippedGroups shouldContainAll listOf(
            "scanning", "parsing", "sentenceParsing", "inputs", "clipboard",
            "accessibility", "popupWindow", "conditionGroups", "translation", "anki", "general",
        )

        // Anki card formats (deck/model/handlebars) must be flagged, never auto-converted.
        settings.skippedReports.map { it.key } shouldContain "anki.cardFormats"
        // The Yomitan API and maxResults are unmappable general keys.
        settings.skippedReports.map { it.key } shouldContainAll listOf(
            "general.enableYomitanApi",
            "general.yomitanApiServer",
            "general.maxResults",
        )
    }

    @Test
    fun `unknown keys are ignored and reported, never fatal`() {
        val settings = imported()
        settings.skippedReports.map { it.key } shouldContain "general.futureUnknownKey"
        settings.appliedReports.map { it.key } shouldContain "general.fontSize"
    }

    @Test
    fun `a profile with missing option groups maps to empty defaults`() {
        val raw = """
            {"version":0,"options":{"version":77,"profileCurrent":0,
             "profiles":[{"name":"Bare","options":{}}]}}
        """.trimIndent()
        val settings = imported(raw)

        settings.profiles.single().apply {
            name shouldBe "Bare"
            dictionaryOrder shouldBe emptyList()
            enabledDictionaries shouldBe emptySet()
            languageCode shouldBe ""
            searchResolution shouldBe ""
            scanResolution shouldBe ""
        }
        settings.popup shouldBe YomitanImportPopup()
        settings.anki shouldBe YomitanImportAnki()
        settings.audio shouldBe YomitanImportAudio()
        settings.aliases shouldBe emptyMap()
        settings.warnings shouldBe emptyList()
    }

    @Test
    fun `a newer options version is accepted with a warning`() {
        val settings = imported(fixture().replace("\"version\": 77", "\"version\": 99"))

        settings.optionsVersion shouldBe 99
        settings.warnings.size shouldBe 1
        settings.warnings.single().contains("99") shouldBe true
        // Known keys are still mapped despite the newer schema.
        settings.popup.fontSize shouldBe 15
    }

    @Test
    fun `imports a custom-json Local Audio Server source verbatim`() {
        val raw = """
            {"version":0,"options":{"version":77,"profileCurrent":0,
             "profiles":[{"name":"P","options":{"audio":{
               "enabled":true,"autoPlay":true,"volume":80,"fallbackSoundType":"bloop",
               "enableDefaultAudioSources":false,
               "sources":[
                 {"type":"custom-json","url":"http://127.0.0.1:8765/localaudio/get/?term={term}&reading={reading}","voice":""},
                 {"type":"custom","url":"http://forvo.example/?t={term}","voice":""}
               ]}}}]}}
        """.trimIndent()
        val settings = imported(raw)

        settings.audio.enabled shouldBe true
        settings.audio.autoPlay shouldBe true
        settings.audio.volumePercent shouldBe 80
        settings.audio.fallbackSoundType shouldBe "bloop"
        settings.audio.enableDefaultAudioSources shouldBe false
        settings.audio.sources shouldBe listOf(
            mihon.core.dictionary.audio.AudioSourceConfig(
                type = AudioSourceTypes.CUSTOM_JSON,
                url = "http://127.0.0.1:8765/localaudio/get/?term={term}&reading={reading}",
            ),
            mihon.core.dictionary.audio.AudioSourceConfig(
                type = AudioSourceTypes.CUSTOM,
                url = "http://forvo.example/?t={term}",
            ),
        )
        settings.skippedReports.none { it.group == "audio" } shouldBe true
    }

    @Test
    fun `preserves the exact audio source order including repeated types`() {
        val raw = """
            {"version":0,"options":{"version":77,"profileCurrent":0,
             "profiles":[{"name":"P","options":{"audio":{
               "enableDefaultAudioSources":false,
               "sources":[
                 {"type":"custom-json","url":"http://127.0.0.1:8765/localaudio/get/?term={term}&reading={reading}","voice":""},
                 {"type":"jpod101","url":"","voice":""},
                 {"type":"custom-json","url":"http://forvo.example/json?t={term}","voice":""},
                 {"type":"custom","url":"http://third/?t={term}","voice":""}
               ]}}}]}}
        """.trimIndent()
        val settings = imported(raw)

        // Verbatim order, repeated `custom-json` type included: whatever the backup says plays
        // first must remain first.
        settings.audio.sources.map { it.type } shouldBe listOf(
            AudioSourceTypes.CUSTOM_JSON,
            AudioSourceTypes.JPOD_101,
            AudioSourceTypes.CUSTOM_JSON,
            AudioSourceTypes.CUSTOM,
        )
        settings.audio.sources.map { it.url } shouldBe listOf(
            "http://127.0.0.1:8765/localaudio/get/?term={term}&reading={reading}",
            "",
            "http://forvo.example/json?t={term}",
            "http://third/?t={term}",
        )
    }

    @Test
    fun `extracts autoplay from fallback keys when present`() {
        val rawLowercase = """
            {"version":0,"options":{"version":77,"profileCurrent":0,
             "profiles":[{"name":"P","options":{"audio":{
               "enabled":true,"autoplay":true,"sources":[]
             }}}]}}
        """.trimIndent()
        imported(rawLowercase).audio.autoPlay shouldBe true

        val rawGeneral = """
            {"version":0,"options":{"version":77,"profileCurrent":0,
             "profiles":[{"name":"P","options":{
               "general":{"autoPlayAudio":true},
               "audio":{"enabled":true,"sources":[]}
             }}]}}
        """.trimIndent()
        imported(rawGeneral).audio.autoPlay shouldBe true
    }

    @Test
    fun `malformed and non-Yomitan files are rejected without crashing`() {
        (YomitanSettingsMapper.map("not json") is YomitanImportResult.Invalid) shouldBe true
        (YomitanSettingsMapper.map("""{"formatName":"dexie"}""") is YomitanImportResult.Invalid) shouldBe true
        (YomitanSettingsMapper.map("""{"options":{"profiles":[]}}""") is YomitanImportResult.Invalid) shouldBe true
    }
}
// KUN <--
