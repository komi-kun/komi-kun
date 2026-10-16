// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported/adapted from Chimahon's `chimahon/anki/AnkiProfile.kt` (GPL-3.0). The manga-only fork
// keeps only the Anki mining settings here; dictionary selection lives in
// `mihon.core.dictionary.model.DictionaryProfile`. Persistence uses kotlinx.serialization instead
// of `org.json` so the store stays JVM-testable (plan 05 §A3/B7).
package mihon.core.dictionary.anki

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A named Anki mining configuration: AnkiDroid deck/model/field-map/tag/duplicate/screenshot
 * settings. The list of dictionaries to use is intentionally **not** part of this profile — that
 * belongs to [mihon.core.dictionary.model.DictionaryProfile].
 */
data class AnkiProfile(
    val id: String,
    val name: String,
    val ankiEnabled: Boolean = false,
    val ankiDeck: String = "",
    val ankiModel: String = LapisPreset.MODEL_NAME,
    val ankiFieldMap: String = LapisPreset.defaultFieldMapJson,
    val ankiTags: String = DEFAULT_TAG,
    val ankiDupCheck: Boolean = true,
    val ankiDupScope: String = "deck",
    val ankiDupAction: String = "prevent",
    val ankiCropMode: String = "full",
    val ankiCropPreset: String = "full",
    val ankiSyncOnCreate: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("ankiEnabled", ankiEnabled)
        put("ankiDeck", ankiDeck)
        put("ankiModel", ankiModel)
        put("ankiFieldMap", ankiFieldMap)
        put("ankiTags", ankiTags)
        put("ankiDupCheck", ankiDupCheck)
        put("ankiDupScope", ankiDupScope)
        put("ankiDupAction", ankiDupAction)
        put("ankiCropMode", ankiCropMode)
        put("ankiCropPreset", ankiCropPreset)
        put("ankiSyncOnCreate", ankiSyncOnCreate)
    }

    /** Serialise to a compact JSON string (the value stored by the preference layer). */
    fun toJsonString(): String = AnkiJson.encodeToString(JsonObject.serializer(), toJson())

    companion object {
        val EMPTY = AnkiProfile(id = "", name = "")

        /** Replaces Chimahon's `"chimahon"` default tag (plan 05 §B3). */
        const val DEFAULT_TAG = "komikun"

        fun fromJson(json: JsonObject): AnkiProfile = AnkiProfile(
            id = json.str("id"),
            name = json.str("name"),
            ankiEnabled = json.bool("ankiEnabled", false),
            ankiDeck = json.str("ankiDeck"),
            ankiModel = json.str("ankiModel").ifBlank { LapisPreset.MODEL_NAME },
            ankiFieldMap = json.str("ankiFieldMap", "{}").let { fieldMap ->
                val model = json.str("ankiModel")
                when {
                    model.isBlank() && LapisPreset.isBlankFieldMap(fieldMap) -> LapisPreset.defaultFieldMapJson
                    LapisPreset.isBundledModelName(model) && LapisPreset.isBlankFieldMap(fieldMap) -> LapisPreset.defaultFieldMapJson
                    else -> fieldMap
                }
            },
            ankiTags = json.str("ankiTags", DEFAULT_TAG),
            ankiDupCheck = json.bool("ankiDupCheck", true),
            ankiDupScope = json.str("ankiDupScope", "deck"),
            ankiDupAction = json.str("ankiDupAction", "prevent"),
            ankiCropMode = json.str("ankiCropMode", "full"),
            ankiCropPreset = json.str("ankiCropPreset", "full"),
            ankiSyncOnCreate = json.bool("ankiSyncOnCreate", false),
        )

        /** Parse a profile from a stored JSON string; malformed input degrades to [EMPTY]. */
        fun fromJsonString(raw: String): AnkiProfile =
            runCatching { fromJson(AnkiJson.parseToJsonElement(raw).jsonObject) }.getOrElse { EMPTY }

        /** One-time migration of the legacy flat preference values into a brand-new profile. */
        fun createDefault(
            defaultName: String = "Default",
            ankiEnabled: Boolean = false,
            ankiDeck: String = "",
            ankiModel: String = LapisPreset.MODEL_NAME,
            ankiFieldMap: String = LapisPreset.defaultFieldMapJson,
            ankiTags: String = DEFAULT_TAG,
            ankiDupCheck: Boolean = true,
            ankiDupScope: String = "deck",
            ankiDupAction: String = "prevent",
            ankiCropMode: String = "full",
            ankiCropPreset: String = "full",
            ankiSyncOnCreate: Boolean = false,
        ): AnkiProfile = AnkiProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = defaultName,
            ankiEnabled = ankiEnabled,
            ankiDeck = ankiDeck,
            ankiModel = ankiModel.ifBlank { LapisPreset.MODEL_NAME },
            ankiFieldMap = when {
                ankiModel.isBlank() && LapisPreset.isBlankFieldMap(ankiFieldMap) -> LapisPreset.defaultFieldMapJson
                LapisPreset.isBundledModelName(ankiModel) && LapisPreset.isBlankFieldMap(ankiFieldMap) -> LapisPreset.defaultFieldMapJson
                else -> ankiFieldMap
            },
            ankiTags = ankiTags,
            ankiDupCheck = ankiDupCheck,
            ankiDupScope = ankiDupScope,
            ankiDupAction = ankiDupAction,
            ankiCropMode = ankiCropMode,
            ankiCropPreset = ankiCropPreset,
            ankiSyncOnCreate = ankiSyncOnCreate,
        )
    }
}

private fun JsonObject.str(key: String, default: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: default

private fun JsonObject.bool(key: String, default: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default
// KUN <--
