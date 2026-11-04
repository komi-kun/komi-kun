package mihon.core.archive

class ArchiveEntry(
    val name: String,
    val isFile: Boolean,
    val isEncrypted: Boolean,
    // KUN -->
    /**
     * The entry's uncompressed size in bytes, or `-1` when the archive does not record one.
     *
     * The reader keys its upscale cache on it (`ReaderPage.sourceLength`), so a re-downloaded chapter
     * misses instead of serving a stale upscale. It never affects what the entry's stream reads.
     */
    val size: Long = -1L,
    /**
     * The entry's recorded modification time (epoch millis), or `-1` when the archive records none or
     * records a value that cannot be a time.
     *
     * Also part of the reader's upscale cache identity (`ReaderPage.sourceLastModified`), so a
     * re-packed chapter of the same size misses too. Metadata of the header libarchive already
     * parsed: reading it touches no entry data.
     */
    val lastModified: Long = -1L,
    // KUN <--
)
