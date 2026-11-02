// SPDX-License-Identifier: GPL-3.0-or-later
// Fixture: the normal annotated shape — several annotations on their own lines above a declaration
// that carries modifiers and is on a single line. It parses today and must keep parsing: the new
// split-`external` check has to reject only a modifier list that is actually split across lines.
package fixture.ocr

internal object Natives {
    @Suppress("unused")
    @Synchronized
    private external fun nativeHello(): Long
}
