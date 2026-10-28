// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh, evaluated against the *real* tree: a seam file that
// star-imports the GPL-mixed `eu.kanade.tachiyomi.ui.dictionary` package and calls
// `DictionaryEntryWebView`, a GPL top-level *function*. The import line names only the package, so
// only the per-package name map can catch this — and only if that map holds callables.
// Never compiled — it lives outside every source set on purpose.
package fixture.realtree

import eu.kanade.tachiyomi.ui.dictionary.*

object StarImportRealGplFunctionSeam {
    fun run(): Any = ::DictionaryEntryWebView
}
