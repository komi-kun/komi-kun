// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: an overloaded native takes the `__<signature>` suffix; both suffixes are exported, so
// the gate must PASS (and the signature derivation still has to work).
package fixture.ocr

internal object Natives {
    external fun dup(value: Int): Long

    external fun dup(value: Long): Long
}
