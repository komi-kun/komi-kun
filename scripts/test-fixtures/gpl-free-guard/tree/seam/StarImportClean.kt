// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: a seam file star-importing a mixed package but using
// only its Apache declaration, so the guard must stay green — the package has no *used* GPL name.
package fixture.seam.use

import fixture.mixed.*

object StarImportCleanSeam {
    fun run(): String = ApacheMixedThing.run()
}
