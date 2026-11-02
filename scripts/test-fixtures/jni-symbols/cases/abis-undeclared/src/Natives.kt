// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: one native in a module whose build file declares no abiFilters at all. There is no ABI
// list to check, so the gate must refuse with its diagnostic rather than exit silently.
package fixture.ocr

internal object Natives {
    external fun nativeHello(): Long
}
