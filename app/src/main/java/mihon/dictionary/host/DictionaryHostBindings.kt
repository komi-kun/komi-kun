// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.dictionary.host

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import mihon.core.dictionary.engine.DictionaryEngine
import mihon.core.dictionary.repository.DictionaryRepository
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.preference.PreferenceStore

/**
 * App-side Metro bindings for the dictionary host seam (contract §3.6). GPL (not part of the
 * Apache §1.3 seam file set).
 *
 * Created empty by W1 and registered in `AppGraph.bindingContainers`; W2 fills it with the
 * app-level `@Provides` for the dictionary repository and preferences. The
 * `ReaderDictionaryHost` binding itself is contributed by `@ContributesBinding` on
 * `mihon.dictionary.impl.ReaderDictionaryHostImpl` (contract §3.6b) — no entry is needed here.
 * Kept out of `@ContributesTo` so the app registers it exactly once.
 */
@BindingContainer
object DictionaryHostBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideDictionaryPreferences(preferenceStore: PreferenceStore): DictionaryPreferences =
        DictionaryPreferences(preferenceStore)

    @Provides
    @SingleIn(AppScope::class)
    fun provideDictionaryRepository(
        engine: DictionaryEngine,
        context: Context,
        // KUN --> the repository takes the combined dictionary/OCR opt-in (off = no engine work).
        dictionaryOcr: DictionaryOcrPreferences,
        // KUN <--
    ): DictionaryRepository = DictionaryRepository(engine, context, dictionaryOcr)
}
