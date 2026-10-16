// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// :core:dictionary — the vendored `hoshidicts` engine (submodule + JNI) and the Kotlin
// dictionary facade (GPL-3.0-or-later). Build file is frozen for all waves (contract §3.2).
// The native toolchain (NDK/CMake) is pinned in AndroidConfig.
// KUN <--
import mihon.buildlogic.AndroidConfig

plugins {
    id("mihon.library")
    alias(libs.plugins.metro)
}

android {
    namespace = "mihon.core.dictionary"
    ndkVersion = AndroidConfig.NDK_VERSION

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // ONE ABI set for all native artifacts (contract §1.5).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    // Guarded options in the vendored fork (contract §1.6/§7).
                    "-DHOSHIDICTS_BUILD_CLI=OFF",
                    "-DHOSHIDICTS_BUILD_BENCHMARKS=OFF",
                )
                cppFlags += "-Oz"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = AndroidConfig.CMAKE_VERSION
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.metro)

    implementation(androidx.annotation)

    implementation(libs.metro.runtime)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
