// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.reader.ocr

import dev.zacsweers.metro.BindingContainer

/**
 * App-side Metro bindings for the OCR host seam (contract §3.6). GPL (not part of the Apache §1.3
 * seam file set).
 *
 * Created empty by W1 and registered in `AppGraph.bindingContainers`; W3 fills it with the
 * `ReaderOcrHost` app-level `@Provides`. Kept out of `@ContributesTo` so the app registers it
 * exactly once.
 */
@BindingContainer
object OcrHostBindings
