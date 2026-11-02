// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: a nested class is `Outer$Inner` and an unnamed companion is `Outer$Companion`, so both
// mangle with `_00024`. Exported correctly here, so the gate must PASS.
package fixture.ocr

class Outer {
    class Inner {
        external fun nativeWay(): Long
    }
}

class CompanionHolder {
    companion object {
        external fun nativeCompanion(): Long
    }
}
