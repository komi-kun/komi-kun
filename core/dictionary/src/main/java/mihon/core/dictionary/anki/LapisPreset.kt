// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported/adapted from Chimahon's `chimahon/anki/LapisPreset.kt` (GPL-3.0). The bundled note model
// is optional; when the user has a Lapis-like model the default field map is applied. Hard-coded
// "chimahon" names become "komikun" (plan 05 §B3).
package mihon.core.dictionary.anki

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object LapisPreset {
    const val MODEL_NAME = "Lapis"
    const val FALLBACK_MODEL_NAME = "Lapis (Komikun)"
    const val DEFAULT_DECK_NAME = "Komikun"

    val fields: List<String> = listOf(
        "Expression",
        "ExpressionFurigana",
        "ExpressionReading",
        "ExpressionAudio",
        "SelectionText",
        "MainDefinition",
        "DefinitionPicture",
        "Sentence",
        "SentenceFurigana",
        "SentenceAudio",
        "Picture",
        "Glossary",
        "Hint",
        "IsWordAndSentenceCard",
        "IsClickCard",
        "IsSentenceCard",
        "IsAudioCard",
        "PitchPosition",
        "PitchCategories",
        "Frequency",
        "FreqSort",
        "MiscInfo",
    )

    val defaultFieldMap: Map<String, String> = linkedMapOf(
        "Expression" to "{${Marker.EXPRESSION}}",
        "ExpressionFurigana" to "{${Marker.FURIGANA_PLAIN}}",
        "ExpressionReading" to "{${Marker.READING}}",
        "ExpressionAudio" to "{${Marker.AUDIO}}",
        "SelectionText" to "{${Marker.POPUP_SELECTION_TEXT}}",
        "MainDefinition" to "{${Marker.SELECTED_GLOSSARY}}",
        "Sentence" to "{${Marker.SENTENCE}}",
        "SentenceFurigana" to "{${Marker.SENTENCE_FURIGANA}}",
        "SentenceAudio" to "{${Marker.SENTENCE_AUDIO}}",
        "Picture" to "{${Marker.SCREENSHOT}}",
        "Glossary" to "{${Marker.GLOSSARY}}",
        "IsWordAndSentenceCard" to "x",
        "PitchPosition" to "{${Marker.PITCH_ACCENT_POSITIONS}}",
        "PitchCategories" to "{${Marker.PITCH_ACCENT_CATEGORIES}}",
        "Frequency" to "{${Marker.FREQUENCIES}}",
        "FreqSort" to "{${Marker.FREQUENCY_HARMONIC_RANK}}",
        "MiscInfo" to "{${Marker.MEDIA_NAME}}",
    )

    val defaultFieldMapJson: String
        get() = AnkiJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { defaultFieldMap.forEach { (key, value) -> put(key, JsonPrimitive(value)) } },
        )

    fun isBlankFieldMap(fieldMapJson: String): Boolean =
        fieldMapJson.isBlank() || fieldMapJson.trim() == "{}"

    fun isBundledModelName(name: String): Boolean =
        name.equals(MODEL_NAME, ignoreCase = true) ||
            name.equals(FALLBACK_MODEL_NAME, ignoreCase = true) ||
            name.startsWith("Lapis (Komikun ", ignoreCase = true)

    fun isLapisLikeModel(name: String, modelFields: List<String>): Boolean =
        name.contains("lapis", ignoreCase = true) || hasCoreFields(modelFields)

    fun hasCoreFields(modelFields: List<String>): Boolean {
        val names = modelFields.map { it.lowercase() }.toSet()
        return listOf("expression", "maindefinition", "sentence").all { it in names }
    }

    fun hasAllFields(modelFields: List<String>): Boolean {
        val names = modelFields.map { it.lowercase() }.toSet()
        return fields.all { it.lowercase() in names }
    }

    fun defaultFieldMapFor(modelFields: List<String>): Map<String, String> {
        if (modelFields.isEmpty()) return defaultFieldMap
        val available = modelFields.toSet()
        return defaultFieldMap.filterKeys { it in available }
    }

    fun defaultFieldMapJsonFor(modelFields: List<String>): String = mapToJson(defaultFieldMapFor(modelFields))

    fun applyDefaults(
        modelName: String,
        modelFields: List<String>,
        currentFieldMapJson: String,
    ): String {
        if (!isLapisLikeModel(modelName, modelFields)) return currentFieldMapJson
        if (isBlankFieldMap(currentFieldMapJson)) return defaultFieldMapJsonFor(modelFields)
        val current = AnkiMarkerEngine.parseFieldMap(currentFieldMapJson)
        val merged = defaultFieldMapFor(modelFields) + current
        return mapToJson(merged)
    }

    private fun mapToJson(map: Map<String, String>): String = AnkiJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject { map.forEach { (key, value) -> put(key, JsonPrimitive(value)) } },
    )
}
// KUN <--
