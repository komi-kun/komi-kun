// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: evasion (c) — a fully qualified use with no `import`
// line at all.
package fixture.seam.use

object QualifiedViolationSeam {
    fun run(): String = fixture.gpl.GplThing.run()
}
