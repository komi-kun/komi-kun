// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.presentation.more.settings.Preference
import mihon.app.di.appGraph
import tachiyomi.i18n.kun.KUNMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Global dictionary popup chrome (theme, layout, typography). Preference widgets are reused from
 * [SettingsDictionaryScreen] (02 §5.1).
 */
object SettingsDictionaryPopupScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KUNMR.strings.pref_category_dictionary_popup

    // KUN -->
    /**
     * Popup chrome belongs to the dictionaries feature: while the combined dictionary/OCR opt-in is
     * off the entry is hidden from Settings and from search (`SearchableSettings.isEnabled`), so the
     * screen is not an independently reachable backdoor. The master switch itself stays reachable
     * from [SettingsDictionaryScreen] and [SettingsOcrScreen].
     */
    override fun isEnabled(): Boolean = Injekt.get<Context>().appGraph.dictionaryOcrPreferences.isEnabled()
    // KUN <--

    @Composable
    override fun getPreferences(): List<Preference> = SettingsDictionaryScreen.popupPreferences()
}
