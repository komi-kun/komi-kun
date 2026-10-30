// SPDX-License-Identifier: GPL-3.0-or-later
//
// Fixture for scripts/test-verify-gpl-free.sh: a GPL declaration in a *mixed* package — one that also
// holds an Apache file, so the package cannot be denied wholesale (the shape of
// `eu.kanade.tachiyomi.ui.dictionary`), the way `…reader.ocr.OcrHostBindings` sits next to
// `…reader.ocr.OcrViewport`.
//
// The function and the `const val` are what a star import brings in besides the type: the per-package
// name map must hold top-level *callables* too, and the property's own identifier must be derived
// rather than the `val` keyword.
package fixture.mixed

object GplMixedThing {
    fun run(): String = "gpl"
}

fun gplMixedFunction(): String = "gpl-function"

internal const val GPL_MIXED_CONSTANT: String = "gpl-const"
