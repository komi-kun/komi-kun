// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: one native whose symbol is exported in every declared ABI — the gate must pass.
package fixture.ocr

internal object Natives {
    external fun nativeHello(): Long
}
