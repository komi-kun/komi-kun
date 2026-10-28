// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: a clean seam file. It shares its package with the GPL
// `FixtureGplHelper` above but never uses it, so the guard must stay green.
package fixture.seam

object CleanSeam {
    fun run(): String = "clean"
}
