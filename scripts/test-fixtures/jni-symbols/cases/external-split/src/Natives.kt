// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: a split modifier list — `external` on its own line, `fun …` on the next. `nativeHello` is
// exported in every ABI and parses normally, so a gate that only counts `external fun` tokens reports
// "OK: 1" and exits 0 while `nativeSplit` is entirely missing from the check. The gate must FAIL
// loudly (exit 2) instead of dropping the declaration.
package fixture.ocr

internal object Natives {
    external fun nativeHello(): Long

    external
    fun nativeSplit(): Long
}
