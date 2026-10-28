// SPDX-License-Identifier: Apache-2.0
//
// Fixture for scripts/test-verify-gpl-free.sh, evaluated against the *real* tree: a seam file in
// `eu.kanade.tachiyomi.ui.reader.ocr` (the Apache `OcrViewport`/`ReaderOcrHost` package) using the GPL
// `OcrHostBindings` beside it, with no import and no qualifier. Never compiled — it lives outside
// every source set on purpose.
package eu.kanade.tachiyomi.ui.reader.ocr

object SamePackageRealGplSeam {
    fun run(): Any = OcrHostBindings
}
