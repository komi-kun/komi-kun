// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.ocr.di

import dev.zacsweers.metro.BindingContainer

/**
 * Metro bindings for :core:ocr (contract §3.6).
 *
 * Created empty by W1 and registered in `AppGraph.bindingContainers`; W3 fills it with the OCR
 * services (`OcrManager`, `ModelDownloader`, `OcrEngine`, …). Kept out of `@ContributesTo` so the
 * app registers it exactly once.
 */
@BindingContainer
object OcrBindings
