// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: a seam file that star-imports a mixed package and reads
// one of its GPL top-level `const val`s. Two things must hold: the property is in the package's name
// map (callables, not only types), and it is filed under its own identifier — deriving the keyword
// `val` instead would leave this use invisible.
package fixture.seam.use

import fixture.mixed.*

object StarImportConstantViolationSeam {
    fun run(): String = GPL_MIXED_CONSTANT
}
