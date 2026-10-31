// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: the symbol is exported in arm64-v8a and x86_64 but not armeabi-v7a. An ABI union passes
// this; a per-ABI gate must FAIL and name exactly armeabi-v7a.
package fixture.ocr

internal object Natives {
    external fun nativeHello(): Long
}
