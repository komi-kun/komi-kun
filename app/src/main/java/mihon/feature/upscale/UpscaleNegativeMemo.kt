// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The pages this reader has learnt it cannot upscale, so a page turn does not ask for them again.
//
// Without it a page that can never be upscaled — a colour page with no illustration model installed,
// a page over the output cap, one the decoder refuses — is re-requested on every single page turn for
// the whole chapter: each request re-reads the page's bytes, decodes it and routes it, and produces
// exactly the same "leave it alone". The memo is per session (one reader, one chapter) and bounded,
// so a long chapter of such pages cannot grow it without limit.
//
// The key is `(page identity, preference signature)`: a changed mode/scale/model/selection is a
// different question about the page, so its answer must be asked again. `reset()` (a chapter change)
// clears the whole memo, and a page that does end up with a cache entry is forgotten again.
// KUN <--
package mihon.feature.upscale

import mihon.core.upscale.PageKey

/**
 * A bounded, thread-safe set of "this page cannot be upscaled under this signature".
 *
 * Written on the engine thread (from the request's `onDone`) and read on the page-turn thread, so
 * every access is synchronized. Oldest-first eviction: the pages most likely to be asked about again
 * are the ones just read.
 */
internal class UpscaleNegativeMemo(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Unit>()

    /** True when [pageKey] already failed under [signature] and must not be asked for again. */
    fun contains(pageKey: PageKey, signature: String): Boolean =
        synchronized(lock) { entries.containsKey(keyOf(pageKey, signature)) }

    /** Remembers that [pageKey] cannot be upscaled under [signature]. */
    fun record(pageKey: PageKey, signature: String) {
        synchronized(lock) {
            val key = keyOf(pageKey, signature)
            // Re-insert so a repeated failure counts as the newest entry.
            entries.remove(key)
            entries[key] = Unit
            while (entries.size > maxEntries) {
                val oldest = entries.keys.first()
                entries.remove(oldest)
            }
        }
    }

    /** Forgets [pageKey]: it has an upscale now, so it must be served rather than skipped. */
    fun forget(pageKey: PageKey, signature: String) {
        synchronized(lock) { entries.remove(keyOf(pageKey, signature)) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** How many pages the memo holds; the bound is what keeps a long chapter from growing it. */
    val size: Int
        get() = synchronized(lock) { entries.size }

    private fun keyOf(pageKey: PageKey, signature: String): String = buildString {
        append(signature)
        append('|').append(pageKey.sourceId)
        append('|').append(pageKey.mangaId)
        append('|').append(pageKey.chapterId)
        append('|').append(pageKey.pageIndex)
        append('|').append(pageKey.sourceLength)
        append('|').append(pageKey.sourceLastModified)
    }

    companion object {
        /**
         * Enough for the pages a reader can realistically fail on in one chapter (~200 pages) plus
         * the chapter's prefetch window, without the memo itself becoming a leak.
         */
        const val DEFAULT_MAX_ENTRIES: Int = 256
    }
}
