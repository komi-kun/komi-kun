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
    testImplementation(kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// KUN -->
// --- T2 Yomitan engine conformance (docs/design/implementation/05-testing-and-anki.md §A1.5) ---
// The committed corpus is executed against the *real* vendored engine, dictionary import
// included. The Android `.so` is an NDK cross-build and cannot be loaded by a desktop JVM, so the
// runner loads a host build of the same `hoshidicts_jni` bridge (`scripts/build-host-conformance-lib.sh`).
//
//     ./gradlew :core:dictionary:conformanceHostTest
//
// It is a separate task rather than part of `testDebugUnitTest` because it needs a host C++
// toolchain (cmake + a C++23 stdlib, GCC >= 14 for `std::ranges::to`); every other test in this
// module stays device- and toolchain-free.
val conformanceHostLibDir = layout.buildDirectory.dir("conformance/host-lib")
// Only the linkable artifact is a conformance input; the CMake tree next to it is build scratch.
val conformanceHostLibFiles = conformanceHostLibDir.map { it.dir("lib") }

val buildHostConformanceLib = tasks.register<Exec>("buildHostConformanceLib") {
    group = "verification"
    description = "Builds the host hoshidicts_jni library used by the T2 Yomitan conformance runner."
    val outputDir = conformanceHostLibDir.get().asFile
    commandLine(
        "bash",
        layout.projectDirectory.file("scripts/build-host-conformance-lib.sh").asFile.absolutePath,
        outputDir.absolutePath,
    )
    inputs.dir(layout.projectDirectory.dir("src/main/cpp"))
    inputs.file(layout.projectDirectory.file("scripts/build-host-conformance-lib.sh"))
    outputs.dir(outputDir)
}

val conformanceHostTest = tasks.register<Test>("conformanceHostTest") {
    group = "verification"
    description = "T2 Yomitan conformance: executes the committed corpus against the host hoshidicts engine."
    dependsOn("assembleDebugUnitTest", buildHostConformanceLib)
    // The engine binary is content-hashed into this task: a rebuilt/changed native library (or any
    // change under src/main/cpp, which rebuilds it) re-runs the corpus instead of reusing a cached
    // "UP-TO-DATE" pass from an older engine.
    inputs.dir(conformanceHostLibFiles)
    // `System.loadLibrary("hoshidicts_jni")` in `HoshiDictsNative` resolves from java.library.path.
    systemProperty("java.library.path", conformanceHostLibFiles.get().asFile.absolutePath)
    filter {
        includeTestsMatching("mihon.core.dictionary.corpus.YomitanEngineConformanceTest")
    }
}

// AGP registers the unit-test component's task lazily, so reuse its classes/classpath from the
// same hook (this runs after AGP's own `afterEvaluate`).
afterEvaluate {
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    conformanceHostTest.configure {
        testClassesDirs = unitTest.testClassesDirs
        classpath = unitTest.classpath
    }
    // The conformance runner needs the host library, so no other JVM test task may pick it up.
    tasks.withType<Test>().configureEach {
        if (name != "conformanceHostTest") {
            filter {
                excludeTestsMatching("mihon.core.dictionary.corpus.YomitanEngineConformanceTest")
            }
        }
    }
}
// KUN <--
