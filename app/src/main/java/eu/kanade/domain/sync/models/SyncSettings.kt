package eu.kanade.domain.sync.models

data class SyncSettings(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val tracking: Boolean = true,
    val history: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    val privateSettings: Boolean = false,

    // SY -->
    val customInfo: Boolean = true,
    val readEntries: Boolean = true,
    val savedSearchesFeeds: Boolean = true,
    // SY <--
) {
    // KUN -->
    /**
     * A force push uploads every manga payload as-is, so sections omitted from that payload would
     * replace the server's data with empty values library-wide. Require all of them to be enabled.
     */
    val supportsForcePush: Boolean
        get() = libraryEntries && categories && chapters && tracking && history && customInfo
    // KUN <--
}
