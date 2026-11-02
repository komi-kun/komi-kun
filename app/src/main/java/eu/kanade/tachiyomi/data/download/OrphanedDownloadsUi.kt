package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.Kind
import eu.kanade.tachiyomi.data.download.OrphanedDownloadScanner.OrphanedDownload

/** How the orphaned-download list is ordered. */
enum class OrphanedDownloadSort {
    PATH,
    SIZE_DESC,
    SIZE_ASC,
    KIND,
}

/** How the orphaned-download list is split into sections. */
enum class OrphanedDownloadGroup {
    NONE,
    SOURCE,
    MANGA,
    KIND,
}

/** One rendered row of the orphaned-download list: either a section header or an entry. */
sealed interface OrphanedDownloadRow {
    data class Header(val key: String, val label: Label) : OrphanedDownloadRow

    data class Item(val item: OrphanedDownload) : OrphanedDownloadRow

    /**
     * What a header says, resolved to text by the screen: the grouping rule is kept free of string
     * resources so it can be unit tested on the JVM.
     */
    sealed interface Label {
        data class Source(val name: String) : Label

        data class Manga(val path: String) : Label

        data class Type(val kind: Kind) : Label
    }
}

// The decision half of the orphaned-download list ordering, kept free of Android types so it can be
// unit tested on the JVM.
//
// Everything here works on the entries the scanner already returned: the list is only ever reordered
// and sectioned, never filtered, so a row disappearing always means its file was deleted.

/**
 * The rows to render: header rows interleaved with entries when [group] is not
 * [OrphanedDownloadGroup.NONE], and a flat sorted list otherwise.
 *
 * Groups keep the order [sort] gave their members, and the sections themselves are ordered by their
 * key with the same [pathOrder] the entries use — or in [kindOrder] for
 * [OrphanedDownloadGroup.KIND] — so a header and the entries under it never disagree and reopening the
 * list always shows the same layout.
 */
fun rows(
    items: List<OrphanedDownload>,
    sort: OrphanedDownloadSort,
    group: OrphanedDownloadGroup,
): List<OrphanedDownloadRow> {
    val sorted = sortedItems(items, sort)
    if (group == OrphanedDownloadGroup.NONE) return sorted.map { OrphanedDownloadRow.Item(it) }

    // Insertion order is the sort order, which `members` keeps within each group for free.
    val members = LinkedHashMap<String, MutableList<OrphanedDownload>>()
    val labels = HashMap<String, OrphanedDownloadRow.Label>()
    sorted.forEach { item ->
        val key = groupKey(item, group)
        members.getOrPut(key) { mutableListOf() } += item
        labels.getOrPut(key) { groupLabel(key, group) }
    }

    val keys = when (group) {
        OrphanedDownloadGroup.KIND -> members.keys.sortedBy { kindOrder.indexOf(groupKind(it)) }
        else -> members.keys.sortedWith(pathOrder)
    }

    return buildList(members.size + sorted.size) {
        keys.forEach { key ->
            add(OrphanedDownloadRow.Header(key = "${group.name.lowercase()}:$key", label = labels.getValue(key)))
            members.getValue(key).forEach { add(OrphanedDownloadRow.Item(it)) }
        }
    }
}

/**
 * [selection] without the ids that are no longer in [items].
 *
 * The scan is the only source of ids, so a selection kept across a rescan or a deletion can name
 * entries that are gone; those are dropped, and everything still listed stays selected.
 */
fun retainSelection(selection: Set<String>, items: List<OrphanedDownload>): Set<String> {
    if (selection.isEmpty() || items.isEmpty()) return emptySet()
    val ids = items.mapTo(HashSet()) { it.id }
    return selection.filterTo(HashSet()) { it in ids }
}

/**
 * [items] in the order [sort] asks for.
 *
 * Every comparison ends on the path: two entries can share a size but never a path, so the order never
 * depends on what the scan happened to walk first.
 */
private fun sortedItems(items: List<OrphanedDownload>, sort: OrphanedDownloadSort): List<OrphanedDownload> = when (sort) {
    OrphanedDownloadSort.PATH -> items.sortedWith(compareBy(pathOrder) { it.relativePath })
    OrphanedDownloadSort.SIZE_DESC ->
        items.sortedWith(compareByDescending<OrphanedDownload> { it.sizeBytes }.thenBy(pathOrder) { it.relativePath })
    OrphanedDownloadSort.SIZE_ASC ->
        items.sortedWith(compareBy<OrphanedDownload> { it.sizeBytes }.thenBy(pathOrder) { it.relativePath })
    OrphanedDownloadSort.KIND ->
        items.sortedWith(compareBy<OrphanedDownload> { kindOrder.indexOf(it.kind) }.thenBy(pathOrder) { it.relativePath })
}

/**
 * The section an entry belongs to.
 *
 * Source and manga are read off the path, which is `<source folder>/<manga folder>/<entry…>`. A
 * single chapter entry belongs to the folder that contains it, while an orphaned manga folder — and an
 * orphaned source folder — belongs to itself: its own path is a folder path, and sending it to its
 * parent would name a section with the folder above it and split it from the folder it names. An
 * entry with no separator at all is its own group, so a section header always has a name.
 */
private fun groupKey(item: OrphanedDownload, group: OrphanedDownloadGroup): String = when (group) {
    OrphanedDownloadGroup.SOURCE -> item.relativePath.substringBefore('/')
    OrphanedDownloadGroup.MANGA -> when (item.kind) {
        Kind.CHAPTER -> item.relativePath.substringBeforeLast('/')
        Kind.MANGA, Kind.SOURCE -> item.relativePath
    }
    OrphanedDownloadGroup.KIND -> item.kind.name
    // Unreachable: `rows` renders a flat list without headers for NONE.
    OrphanedDownloadGroup.NONE -> error("No grouping has no section key")
}

private fun groupLabel(key: String, group: OrphanedDownloadGroup): OrphanedDownloadRow.Label = when (group) {
    OrphanedDownloadGroup.SOURCE -> OrphanedDownloadRow.Label.Source(key)
    OrphanedDownloadGroup.MANGA -> OrphanedDownloadRow.Label.Manga(key)
    OrphanedDownloadGroup.KIND -> OrphanedDownloadRow.Label.Type(groupKind(key))
    // Unreachable: `rows` renders a flat list without headers for NONE.
    OrphanedDownloadGroup.NONE -> error("No grouping has no section label")
}

private fun groupKind(key: String): Kind = Kind.valueOf(key)

/**
 * The one ordering both the entries and their section keys use: case-insensitive first, so `alpha`
 * and `Beta` sort as neighbours instead of every uppercase name coming before every lowercase one,
 * and byte-exact second, so two names differing only in case still get a stable, path-derived order.
 */
private val pathOrder: Comparator<String> = compareBy<String>({ it.lowercase() }, { it })

/**
 * The order the kind sections are shown in, and the order [OrphanedDownloadSort.KIND] uses.
 *
 * Derived from the enum's declaration order — chapters, then orphaned manga folders, then orphaned
 * source folders — so it cannot drift from [Kind] when a value is added.
 */
private val kindOrder: List<Kind> = Kind.entries

/**
 * The ids a select-all may take: single chapter entries only.
 *
 * A whole manga folder that matches no manga, or a source folder whose extension is merely uninstalled
 * right now, is selected one at a time: deleting a whole folder from one tap is too easy to do by
 * accident.
 */
fun selectableIds(items: List<OrphanedDownload>): Set<String> =
    items.filter { it.kind == Kind.CHAPTER }.mapTo(HashSet()) { it.id }

/** Whether every selectable entry of [items] is selected; false when there is nothing to select. */
fun isAllSelected(items: List<OrphanedDownload>, selection: Set<String>): Boolean {
    val selectable = selectableIds(items)
    return selectable.isNotEmpty() && selection.containsAll(selectable)
}

/** The total size of the entries of [items] whose id is in [selection]. */
fun selectedSizeBytes(items: List<OrphanedDownload>, selection: Set<String>): Long =
    items.filter { it.id in selection }.sumOf { it.sizeBytes }
