// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: a seam file that really uses the same-package GPL
// declaration, from inside a string *template*. Stripping string-literal text must not hide this.
package fixture.seam

object StringInterpolationViolationSeam {
    fun run(): String = "gpl: ${FixtureGplHelper.run()}"
}
