// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: `@JvmName` on its own line. The declaration *is* parseable, but the JVM name comes from
// the annotation, so the derived symbol would be wrong — it must FAIL loudly.
package fixture.ocr

internal object Natives {
    @JvmName("renamed")
    external fun nativeRenamed(): Long
}
