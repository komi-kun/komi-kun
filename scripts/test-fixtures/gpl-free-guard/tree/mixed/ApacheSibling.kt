// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh: the Apache half of `fixture.mixed`, the reason that
// package is not denied wholesale. A seam file may star-import it and use this declaration.
package fixture.mixed

object ApacheMixedThing {
    fun run(): String = "apache"
}
