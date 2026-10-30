// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: a seam file that star-imports a mixed package and calls
// one of its GPL *functions*. `import fixture.mixed.*` brings callables in exactly like types, so the
// package's name map has to hold them, not only its `class`/`object`/`interface` declarations.
package fixture.seam.use

import fixture.mixed.*

object StarImportFunctionViolationSeam {
    fun run(): String = gplMixedFunction()
}
