// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.dictionary.host

import dev.zacsweers.metro.BindingContainer

/**
 * App-side Metro bindings for the dictionary host seam (contract §3.6). GPL (not part of the
 * Apache §1.3 seam file set).
 *
 * Created empty by W1 and registered in `AppGraph.bindingContainers`; W2 fills it with the
 * `ReaderDictionaryHost`/`DictionaryPreferences` app-level `@Provides`. Kept out of `@ContributesTo`
 * so the app registers it exactly once.
 */
@BindingContainer
object DictionaryHostBindings
