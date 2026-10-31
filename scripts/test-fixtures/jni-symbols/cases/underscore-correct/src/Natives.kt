// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: a method name with an underscore. JNI looks up `…_native_1open`, and that is exactly what
// is exported here, so the gate must PASS (the escape is applied, not skipped).
package fixture.ocr

internal object Natives {
    external fun native_open(): Long
}
