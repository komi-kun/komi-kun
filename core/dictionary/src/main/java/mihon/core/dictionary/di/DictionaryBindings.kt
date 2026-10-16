// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import mihon.core.dictionary.engine.DictionaryEngine
import mihon.core.dictionary.engine.HoshiDictionaryEngine

/**
 * Metro bindings for :core:dictionary (contract §3.6).
 *
 * Registered in `AppGraph.bindingContainers` — kept out of `@ContributesTo` so the app
 * registers it exactly once.
 */
@BindingContainer
object DictionaryBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesDictionaryEngine(): DictionaryEngine = HoshiDictionaryEngine()
}
