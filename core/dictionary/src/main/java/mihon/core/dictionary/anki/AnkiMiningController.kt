// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Bridges the dictionary WebView's `AnkiJsBridge.addToAnki` to [AnkiCardCreator]. The app layer
// builds one of these per popup/reader session and hands it the reader context (sentence,
// offset, media, screenshot bytes) that the renderer cannot know.
package mihon.core.dictionary.anki

import android.content.Context
import kotlinx.serialization.json.JsonObject
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.LookupResult

class AnkiMiningController(
    private val context: Context,
    private val preferences: AnkiPreferences,
    private val dictionaryAccess: AnkiDictionaryAccess? = null,
) {
    /** True when Anki mining is enabled in the active profile. */
    fun isEnabled(): Boolean = preferences.enabled().get()

    /**
     * Build and save a card for [result] (the renderer's `addToAnki(resultIndex, …)` payload).
     * Missing optional context (sentence/offset/screenshot) simply degrades the corresponding
     * markers to `""`.
     */
    @Suppress("LongParameterList")
    suspend fun mine(
        result: LookupResult,
        glossaryIndex: Int? = null,
        selectedDict: String? = null,
        popupSelection: String? = null,
        forceOpen: Boolean = false,
        sentence: String = "",
        offset: Int = -1,
        media: AnkiMediaInfo? = null,
        screenshotBytes: ByteArray? = null,
        selection: String? = null,
        styles: List<DictionaryStyle> = emptyList(),
        clipboardText: String? = null,
        documentTitle: String = "",
        url: String = "",
        searchQuery: String = "",
    ): AnkiResult {
        if (!isEnabled()) return AnkiResult.NotConfigured
        return AnkiCardCreator.addToAnki(
            context = context,
            result = result,
            profile = preferences.activeProfile(),
            sentence = sentence,
            offset = offset,
            media = media,
            screenshotBytes = screenshotBytes,
            glossaryIndex = glossaryIndex,
            selection = selection,
            selectedDict = selectedDict,
            popupSelection = popupSelection,
            styles = styles,
            clipboardText = clipboardText,
            documentTitle = documentTitle,
            url = url,
            searchQuery = searchQuery,
            forceOpen = forceOpen,
            dictionaryAccess = dictionaryAccess,
        )
    }

    /** Update an existing card's `{screenshot}` once the crop has been captured (§B4). */
    suspend fun updateScreenshot(noteId: Long, field: String, bytes: ByteArray, bridge: AnkiCardBridge) {
        val filename = bridge.storeMedia(AnkiCardCreator.generateScreenshotFilename(bytes), bytes)
        bridge.updateNoteFields(noteId, mapOf(field to """<img src="$filename" />"""))
    }

    companion object {
        /** Parse a stored field-map JSON string (re-exported for the app layer). */
        fun parseFieldMap(json: String): Map<String, String> = AnkiCardCreator.parseFieldMap(json)

        /** Read a field-map value (helper for manual field-mapping UIs). */
        fun fieldMapFromObject(json: JsonObject): Map<String, String> =
            AnkiCardCreator.parseFieldMap(json.toString())
    }
}
// KUN <--
