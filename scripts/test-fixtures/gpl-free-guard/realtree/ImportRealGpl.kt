// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh, evaluated against the *real* tree: this import names a
// GPL type that the hand-maintained deny list never contained, so only a set derived from the SPDX
// headers can flag it. Never compiled — it lives outside every source set on purpose.
package fixture.realtree

import eu.kanade.tachiyomi.ui.reader.loader.ArchiveReadGuard

object ImportRealGplSeam {
    fun run(): String = ArchiveReadGuard::class.java.name
}
