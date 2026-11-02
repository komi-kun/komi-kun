// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: a nested class is `Outer$Inner` and an unnamed companion is `Outer$Companion`, so both
// mangle with `_00024`. The *flat* `Outer_*` names are exported here instead, so the gate must FAIL
// and name the `_00024` symbols a parser that ignores nesting would derive.
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
