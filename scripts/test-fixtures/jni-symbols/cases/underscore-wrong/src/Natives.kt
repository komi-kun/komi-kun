// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: a method name with an underscore. JNI looks up `…_native_1open`; a `.so` exporting the
// unescaped `…_native_open` can never be bound, so the gate must FAIL and name the escaped symbol.
package fixture.ocr

internal object Natives {
    external fun native_open(): Long
}
