// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.i18n.kmk.KMR

/**
 * Global dictionary popup chrome (theme, layout, typography). Preference widgets are reused from
 * [SettingsDictionaryScreen] (02 §5.1).
 */
object SettingsDictionaryPopupScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_category_dictionary_popup

    @Composable
    override fun getPreferences(): List<Preference> = SettingsDictionaryScreen.popupPreferences()
}
