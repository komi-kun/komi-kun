// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Typed result of parsing a Yomitan settings backup (`yomitan-settings-*.json`). This is the
// "plan" the importer applies, kept free of Android/PreferenceStore so it can be unit-tested on
// the JVM. See `YomitanSettingsMapper` for the mapping and `YomitanSettingsApplier` (app layer)
// for the write-back.
package mihon.core.dictionary.yomitan

import mihon.core.dictionary.audio.AudioSourceConfig

/** One applied or skipped item, shown verbatim in the import summary. */
data class YomitanImportReport(
    /** Yomitan option group, e.g. `general`, `popupWindow`, `scanning`, `anki`, `profiles`. */
    val group: String,
    /** Yomitan key path (`general.fontSize`) or an aggregate label (`scanning`). */
    val key: String,
    /** Plain data: the applied value, a count, or the offending value. Never prose. */
    val detail: String,
    val applied: Boolean,
)

/** One Yomitan profile mapped onto a komi-kun `DictionaryProfile`. */
data class YomitanImportProfile(
    val name: String,
    val dictionaryOrder: List<String>,
    val enabledDictionaries: Set<String>,
    val dictionaryDisplayModes: Map<String, String>,
    val languageCode: String,
    val searchResolution: String,
    val scanResolution: String,
    val isActive: Boolean,
)

/** Global popup/display preferences, taken from the active Yomitan profile. `null` = leave as-is. */
data class YomitanImportPopup(
    val width: Int? = null,
    val height: Int? = null,
    val fontSize: Int? = null,
    val fontFamily: String? = null,
    val themeMode: String? = null,
    val customCss: String? = null,
    val showPitchDiagram: Boolean? = null,
    val showPitchNumber: Boolean? = null,
    val showPitchText: Boolean? = null,
    val showFrequencyAverage: Boolean? = null,
    val groupTerms: Boolean? = null,
    val compactGlossary: Boolean? = null,
)

/** Global Anki mining preferences, taken from the active Yomitan profile. `null` = leave as-is. */
data class YomitanImportAnki(
    val enabled: Boolean? = null,
    val tags: String? = null,
    val duplicateCheck: Boolean? = null,
    val duplicateScope: String? = null,
    val duplicateAction: String? = null,
)

/**
 * Word-audio preferences from the active Yomitan profile's `audio` group. `null` scalars = leave
 * as-is; [sources] is the `type`-discriminated `audio.sources[]` copied verbatim so custom /
 * `custom-json` sources (e.g. a Local Audio Server URL) survive the import intact.
 */
data class YomitanImportAudio(
    val enabled: Boolean? = null,
    val autoPlay: Boolean? = null,
    val volumePercent: Int? = null,
    val enableDefaultAudioSources: Boolean? = null,
    val fallbackSoundType: String? = null,
    val sources: List<AudioSourceConfig> = emptyList(),
)

/** A fully-parsed, validated Yomitan settings backup ready to preview/apply. */
data class YomitanSettingsImport(
    /** Top-level backup `version` (Yomitan's settings-format version, currently 0). */
    val formatVersion: Int,
    /** `options.version` (the Yomitan options-schema version, currently 77). */
    val optionsVersion: Int,
    /** Name of the Yomitan profile `options.profileCurrent` points at, if any. */
    val activeProfileName: String?,
    val profiles: List<YomitanImportProfile>,
    val popup: YomitanImportPopup,
    val anki: YomitanImportAnki,
    val audio: YomitanImportAudio = YomitanImportAudio(),
    /** dictionary name → display alias, only for aliases that differ from the name. */
    val aliases: Map<String, String>,
    /** Every key the importer looked at, applied or not. */
    val reports: List<YomitanImportReport>,
    /** Non-fatal notes (newer schema, malformed values), shown above the summary. */
    val warnings: List<String>,
) {
    val appliedReports: List<YomitanImportReport> get() = reports.filter { it.applied }
    val skippedReports: List<YomitanImportReport> get() = reports.filterNot { it.applied }

    companion object {
        /** Newest Yomitan `options.version` this mapper understands (2026-09 backup: 77). */
        const val KNOWN_OPTIONS_VERSION = 77
    }
}

/** Result of [YomitanSettingsMapper.map]. */
sealed interface YomitanImportResult {
    data class Imported(val settings: YomitanSettingsImport) : YomitanImportResult

    /** The file is not a Yomitan settings backup (or is malformed); [reason] is diagnostic. */
    data class Invalid(val reason: String) : YomitanImportResult
}
// KUN <--
