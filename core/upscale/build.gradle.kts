// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// :core:upscale — reader page upscaling (GPL-3.0-or-later).
//
// Phase 1 (done) is the pure, Android-free half: tile geometry, reflection padding, seam blending,
// band splitting, the model catalog and the routing policy. Phase 2 adds the working engine: the ONNX
// Runtime session (`engine/`), the synchronous result cache (`cache/`), the checksum-verified model
// downloader (`model/ModelDownloader.kt`), the app-scoped `UpscaleService` and its Metro bindings.
// Phase 4 registers those bindings in `AppGraph` and puts the reader on them, through the Apache seam
// in `eu.kanade.tachiyomi.ui.reader.upscale`.
//
// Hard rule: this module must never depend on `:core:dictionary` or `:core:ocr` (contract §1.1).
// The preference the app gates on is Apache-2.0 and lives in `:core:common`
// (`tachiyomi.core.common.preference.UpscalePreferences`), never here.
// KUN <--
plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "mihon.core.upscale"

    defaultConfig {
        // KUN -->
        // The on-device benchmark (`src/androidTest/.../bench/EngineBenchmarkTest.kt`) runs through
        // the androidx runner: it reads pushed weights/pages from the test package's external files
        // dir and needs no UI automation.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // KUN <--

        ndk {
            // ONE ABI set for every native artifact (contract §1.5): the ONNX Runtime AAR ships
            // arm64-v8a / armeabi-v7a / x86 / x86_64, and 32-bit `x86` is dropped here so this
            // module never contributes an ABI the app does not build.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
}

dependencies {
    // KUN -->
    implementation(projects.core.common)
    implementation(projects.core.metro)

    implementation(libs.metro.runtime)

    // The page decoder the app already ships (`ImageDecoder`, used by the Coil decoder and OCR).
    implementation(libs.image.decoder)

    // Inference backend (MIT): `OrtTileInference` creates the session from the AAR's
    // `libonnxruntime.so`.
    implementation(libs.onnxruntime.android)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)
    implementation(kotlinx.serialization.json)

    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // On-device benchmark (`src/androidTest`): JUnit4 via the androidx runner.
    androidTestImplementation(androidx.test.ext)
    androidTestImplementation(androidx.test.runner)
    // KUN <--
}
