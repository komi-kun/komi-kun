// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fixture for scripts/test-verify-gpl-free.sh: a GPL declaration in a package of its own, denied by
// fully qualified name (import or qualified use).
package fixture.gpl

object GplThing {
    fun run(): String = "gpl"
}
