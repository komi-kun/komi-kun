// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named dictionary configuration (02 §4).
 *
 * This is the manga-only simplification of Chimahon's `AnkiProfile`: it keeps the dictionary
 * selection fields and drops every Anki field (deck/model/field-map/tags/dup/crop/sync), which
 * belong to the separate Anki phase (W5).
 *
 * [dictionaryOrder] defines priority (index 0 = highest). [enabledDictionaries] is a subset of
 * [dictionaryOrder]; when empty every dictionary in [dictionaryOrder] is treated as enabled.
 */
data class DictionaryProfile(
    val id: String,
    val name: String,
    val dictionaryOrder: List<String> = emptyList(),
    /** Empty = all dictionaries in [dictionaryOrder] are enabled (backwards-compatible default). */
    val enabledDictionaries: Set<String> = emptySet(),
    val dictionaryCollapseMode: String = DICTIONARY_COLLAPSE_EXPAND_ALL,
    val dictionaryDisplayModes: Map<String, String> = emptyMap(),
    /** BCP-47-style language code, e.g. "ja", "ko", "en". Empty = any / not language-specific. */
    val languageCode: String = "",
    /** OCR scan resolution (Yomitan `scanning.scanResolution`). Empty = derive from [languageCode]. */
    val scanResolution: String = "",
    /** Lookup search resolution (Yomitan `translation.searchResolution`). Empty = derive. */
    val searchResolution: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("dictionaryOrder", JSONArray(dictionaryOrder))
        put("enabledDictionaries", JSONArray(enabledDictionaries.toList()))
        put("dictionaryCollapseMode", dictionaryCollapseMode)
        put("dictionaryDisplayModes", JSONObject(dictionaryDisplayModes))
        put("languageCode", languageCode)
        put("scanResolution", scanResolution)
        put("searchResolution", searchResolution)
    }

    companion object {
        val EMPTY = DictionaryProfile(id = "", name = "")

        const val DICTIONARY_COLLAPSE_EXPAND_ALL = "expand_all"
        const val DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE = "expand_first_available"
        const val DICTIONARY_COLLAPSE_COLLAPSE_ALL = "collapse_all"
        const val DICTIONARY_COLLAPSE_CUSTOM = "custom"

        const val DICTIONARY_DISPLAY_ALWAYS_EXPANDED = "always_expanded"
        const val DICTIONARY_DISPLAY_FALLBACK = "fallback"
        const val DICTIONARY_DISPLAY_ALWAYS_COLLAPSED = "always_collapsed"

        const val SCAN_RESOLUTION_AUTO = ""
        const val SCAN_RESOLUTION_CHARACTER = "character"
        const val SCAN_RESOLUTION_WORD = "word"

        const val SEARCH_RESOLUTION_AUTO = ""
        const val SEARCH_RESOLUTION_LETTER = "letter"
        const val SEARCH_RESOLUTION_WORD = "word"

        fun fromJson(json: JSONObject): DictionaryProfile = DictionaryProfile(
            id = json.getString("id"),
            name = json.getString("name"),
            dictionaryOrder = json.optJSONArray("dictionaryOrder")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            enabledDictionaries = json.optJSONArray("enabledDictionaries")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                ?: emptySet(),
            dictionaryCollapseMode = json.optString("dictionaryCollapseMode", DICTIONARY_COLLAPSE_EXPAND_ALL),
            dictionaryDisplayModes = json.optJSONObject("dictionaryDisplayModes")
                ?.let { obj ->
                    buildMap {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, obj.optString(key, DICTIONARY_DISPLAY_FALLBACK))
                        }
                    }
                }
                ?: emptyMap(),
            languageCode = json.optString("languageCode", ""),
            scanResolution = json.optString("scanResolution", ""),
            searchResolution = json.optString("searchResolution", ""),
        )

        /** Build a brand-new profile, e.g. during one-time migration (§4). */
        fun createDefault(
            defaultName: String = "Default",
            dictionaryOrder: List<String> = emptyList(),
            languageCode: String = "",
        ): DictionaryProfile = DictionaryProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = defaultName,
            dictionaryOrder = dictionaryOrder,
            enabledDictionaries = emptySet(),
            languageCode = languageCode,
        )
    }
}
