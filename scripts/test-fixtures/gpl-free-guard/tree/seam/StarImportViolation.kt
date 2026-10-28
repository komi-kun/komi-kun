// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: a seam file that star-imports a mixed package and uses
// the GPL declaration it brings in. The import line names only the package, so the import check cannot
// see it and the package is not denied wholesale — only the name-based check can catch this.
package fixture.seam.use

import fixture.mixed.*

object StarImportViolationSeam {
    fun run(): String = GplMixedThing.run()
}
