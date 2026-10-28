// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: a seam file that shares its package with the GPL
// `FixtureGplHelper` but only *mentions* the name in a string literal — a log tag is prose, not a
// reference, so the guard must stay green.
package fixture.seam

object StringLiteralCleanSeam {
    private const val TAG = "FixtureGplHelper"

    fun run(): String = TAG
}
