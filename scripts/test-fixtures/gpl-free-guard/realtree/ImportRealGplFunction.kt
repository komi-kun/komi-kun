// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh, evaluated against the *real* tree: `DictionaryEntryWebView`
// is a GPL top-level *function*, not a type, so the guard must derive callables as well as classes.
// Never compiled — it lives outside every source set on purpose.
package fixture.realtree

import eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView

object ImportRealGplFunctionSeam {
    fun run(): Any = ::DictionaryEntryWebView
}
