// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.repository

import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.DictionaryProfileStore

/**
 * Resolves the active [DictionaryProfile] for a reader/tab session (02 §4).
 *
 * Priority (highest first): manga override → source override → language auto-match →
 * global active profile. The anime/novel override levels of Chimahon's resolver are dropped
 * for the manga-only port (02 §4).
 */
class DictionaryProfileResolver(
    private val profileStore: DictionaryProfileStore,
    private val readMangaOverride: (mangaId: Long) -> String,
    private val readSourceOverride: (sourceId: Long) -> String,
) {

    fun resolve(
        mangaId: Long = 0L,
        sourceId: Long = 0L,
        sourceLang: String = "",
    ): DictionaryProfile {
        val profiles = profileStore.getProfiles()
        if (profiles.isEmpty()) return profileStore.getActiveProfile()

        // 1. Manga-level override
        if (mangaId != 0L) {
            val found = profiles.firstOrNull { it.id == readMangaOverride(mangaId) }
            if (found != null) return found
        }

        // 2. Source-level override
        if (sourceId != 0L) {
            val found = profiles.firstOrNull { it.id == readSourceOverride(sourceId) }
            if (found != null) return found
        }

        // 3. Language auto-match (skip for "all" / empty / unknown)
        if (sourceLang.isNotBlank() && sourceLang != "all") {
            val langMatch = profiles.firstOrNull { it.languageCode.equals(sourceLang, ignoreCase = true) }
            if (langMatch != null) return langMatch
        }

        // 4. Global active profile
        return profileStore.getActiveProfile()
    }

    companion object {
        /** Preference key for a manga-level profile override. */
        fun mangaOverrideKey(mangaId: Long) = "pref_dict_profile_manga_$mangaId"

        /** Preference key for a source-level profile override. */
        fun sourceOverrideKey(sourceId: Long) = "pref_dict_profile_source_$sourceId"
    }
}
