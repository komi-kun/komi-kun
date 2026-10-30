// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.ui

/**
 * Decides what a romaji→kana text field should do with one `onValueChange` edit.
 *
 * It exists because the conversion must never fight the IME. A text field that rewrites its value
 * on every keystroke resets the IME's composing region, so a Japanese IME cannot build up
 * composition (it is cancelled after every character). [KanaConverter.toKanaIME] knows how to keep
 * a trailing `n`/`ny` unconverted for the *romaji* keyboard, but it has no business touching text
 * the IME is still composing.
 *
 * The rules, all pure (no Compose, no Android) so they are unit-testable:
 *
 * - `isComposing` — the IME owns the text; return `null` (`TextFieldValue.composition != null`).
 * - deletion — never converted, return `null`; re-converting a shrinking string would fight the
 *   user's backspace.
 * - a conversion that leaves text and cursor exactly as they came in — return `null`, so an equal
 *   value is never written back (a redundant write is what resets composition/selection).
 *
 * Text that is unchanged since the previous edit is still converted: when an IME composition ends
 * the platform may deliver the same text with `composition == null`, and roman text committed that
 * way must reach kana like any other keystroke.
 *
 * `null` means "keep the incoming value exactly as the platform produced it". Only a real change
 * produces a [Converted] to write back.
 */
object KanaInputConversion {

    /** A rewritten field value: the converted text and the cursor position that belongs with it. */
    data class Converted(val text: String, val cursor: Int)

    /**
     * @param newText the text the platform/IME just produced.
     * @param cursor the caret position in [newText].
     * @param previousText the text the field held before this edit.
     * @param isComposing whether an IME composition is in progress.
     * @return the value to store, or `null` to store [newText]/[cursor] untouched.
     */
    fun convert(
        newText: String,
        cursor: Int,
        previousText: String,
        isComposing: Boolean,
    ): Converted? {
        if (isComposing) return null
        if (newText.length < previousText.length) return null

        val (text, newCursor) = KanaConverter.toKanaIME(newText, cursor)
        if (text == newText && newCursor == cursor) return null
        return Converted(text, newCursor)
    }
}
