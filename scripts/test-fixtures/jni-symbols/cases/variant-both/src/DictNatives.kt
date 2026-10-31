// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: the correct symbol is in the Debug tree; a *newer* Release tree carries nothing. The gate
// must read the Debug artifact the Gradle task depends on and PASS.
package fixture.dict

internal object DictNatives {
    external fun nativeThing(): Long
}
