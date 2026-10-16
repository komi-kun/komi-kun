// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// :core:ocr — PaddleOCR + `.mokuro` + box-merge + overlay math (GPL-3.0-or-later).
// Scaffolded empty in W1; the OCR services land here in W3 (contract §3.3).
// Hard rule: this module must never depend on `:core:dictionary` (contract §1.1).
// KUN <--
plugins {
    id("mihon.library")
    alias(libs.plugins.metro)
}

android {
    namespace = "mihon.core.ocr"
}

dependencies {
    // KUN -->
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.metro)

    implementation(libs.metro.runtime)

    implementation(libs.image.decoder)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    // KUN <--
}
