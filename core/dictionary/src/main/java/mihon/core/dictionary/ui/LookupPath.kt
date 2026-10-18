// SPDX-License-Identifier: GPL-3.0-or-later

package mihon.core.dictionary.ui

/**
 * A linear path of recursive lookups with a single moving **active** pointer — the same semantics as
 * browser history (docs/design/yomitan-spec/07-search-ux.md §f, "back stack").
 *
 * [entries] is the visible trail of tabs and [activeIndex] is the one currently rendered
 * (`activeIndex` is `-1` while the path is empty). The invariant is that at most one branch ever
 * exists: a new lookup from a moved-back pointer **drops everything after the active entry** and
 * appends the new term ([push]). Merely moving the pointer ([select] / [back]) never drops anything,
 * so the tail stays visible until the user actually looks something up from an earlier position.
 *
 * The logic is deliberately independent of the frame type ([T]) and free of Android/Compose
 * dependencies so it can be unit-tested directly (`LookupPathTest`).
 */
data class LookupPath<T>(
    val entries: List<T> = emptyList(),
    val activeIndex: Int = -1,
) {
    /** The entry currently shown, or `null` when nothing has been looked up yet. */
    val active: T?
        get() = entries.getOrNull(activeIndex)

    /** Whether there is an earlier entry the pointer can move back to. */
    val canGoBack: Boolean
        get() = activeIndex > 0

    /** A fresh path holding a single entry — used when the popup opens on a new query. */
    fun start(entry: T): LookupPath<T> = LookupPath(entries = listOf(entry), activeIndex = 0)

    /**
     * Move the active pointer to [index] (tapping a tab pill). The tail is **kept**; a later [push]
     * is what truncates it. Out-of-range indices are ignored.
     */
    fun select(index: Int): LookupPath<T> =
        if (index in entries.indices) copy(activeIndex = index) else this

    /** Move the pointer one step back (the `back` host). Never drops entries. */
    fun back(): LookupPath<T> =
        if (canGoBack) copy(activeIndex = activeIndex - 1) else this

    /**
     * Append a newly looked-up term: everything strictly after the active entry is truncated first,
     * then [entry] becomes the new tail and the active entry. With an empty path this is equivalent
     * to [start].
     */
    fun push(entry: T): LookupPath<T> {
        val kept = if (activeIndex < 0) emptyList() else entries.take(activeIndex + 1)
        return LookupPath(entries = kept + entry, activeIndex = kept.size)
    }
}
