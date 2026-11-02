// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: only a Release build exists. Reading it would validate a build the Gradle task does not
// produce, so the gate must FAIL with an explicit message instead of falling back.
package fixture.dict

internal object DictNatives {
    external fun nativeThing(): Long
}
