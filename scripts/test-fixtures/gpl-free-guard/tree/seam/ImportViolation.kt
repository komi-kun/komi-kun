// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: evasion (b) is not this one — this is the original
// `import` shape the guard has always caught, kept as a fixture so the import pass cannot regress.
package fixture.seam.use

import fixture.gpl.GplThing

object ImportViolationSeam {
    fun run(): String = GplThing.run()
}
