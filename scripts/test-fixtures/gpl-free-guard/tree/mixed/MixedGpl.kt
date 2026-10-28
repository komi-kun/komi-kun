// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fixture for scripts/test-verify-gpl-free.sh: a GPL declaration in a *mixed* package — one that also
// holds an Apache file, so the package cannot be denied wholesale (the shape of
// `eu.kanade.tachiyomi.ui.dictionary`), the way `…reader.ocr.OcrHostBindings` sits next to
// `…reader.ocr.OcrViewport`.
package fixture.mixed

object GplMixedThing {
    fun run(): String = "gpl"
}
