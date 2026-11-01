// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture for `scripts/test-verify-jni-symbols.sh` — never built by Gradle. This module declares no
// ABI list at all (no `abiFilters += listOf(...)` here, and none mentioned in prose), so the gate has
// no "every declared ABI" to check and must say so instead of dying inside its own probe.
android {
    defaultConfig {
        ndk {
            version = "27.0.12077973"
        }
    }
}
