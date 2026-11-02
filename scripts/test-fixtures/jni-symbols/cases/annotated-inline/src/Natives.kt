// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: `@JvmName` on the declaration line renames the JVM entry point, so the derived symbol
// would be wrong. The parser cannot read this shape — it must FAIL loudly, never drop it silently.
package fixture.ocr

internal object Natives {
    @JvmName("renamed") external fun nativeRenamed(): Long
}
