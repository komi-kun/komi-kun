// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: a forward declaration recorded in the allow-list. Not exported in any ABI, so the gate
// must PASS with a warning naming the ABIs it is missing from.
package fixture.ocr

internal object Natives {
    external fun nativeMissing(): Long
}
