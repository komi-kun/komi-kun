// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: evasion (a) — a seam file calling a GPL declaration
// that lives in its *own* package. There is no `import` line and no package qualifier, so only the
// structural same-package check can see it.
package fixture.seam

object SamePackageViolationSeam {
    fun run(): String = FixtureGplHelper.run()
}
