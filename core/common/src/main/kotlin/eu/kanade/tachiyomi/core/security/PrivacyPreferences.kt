package eu.kanade.tachiyomi.core.security

import tachiyomi.core.common.preference.PreferenceStore

class PrivacyPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val crashlytics = preferenceStore.getBoolean("crashlytics", true)

    val analytics = preferenceStore.getBoolean("analytics", true)
}
