// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fixture for scripts/test-verify-gpl-free.sh: a GPL declaration that shares its package with the
// Apache seam file below, the way `…reader.ocr.OcrHostBindings` sits next to `…reader.ocr.OcrViewport`.
package fixture.seam

object FixtureGplHelper {
    fun run(): String = "gpl"
}
