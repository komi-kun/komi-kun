package exh.pref

import tachiyomi.core.common.preference.PreferenceStore

class DelegateSourcePreferences(
    private val preferenceStore: PreferenceStore,
) {

    val delegateSources = preferenceStore.getBoolean("eh_delegate_sources", true)

    val useJapaneseTitle = preferenceStore.getBoolean("use_jp_title", false)
}
