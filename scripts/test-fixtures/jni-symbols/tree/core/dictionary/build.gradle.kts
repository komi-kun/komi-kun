// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture for `scripts/test-verify-jni-symbols.sh` — never built by Gradle. Only the shape
// `abis_of()` reads matters: `abiFilters += listOf(...)` with the ABI list on one line.
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
}
