// KUN -->
// Routes for the screens this fork (komi-kun / Komikku / TachiyomiSY) adds on top of Mihon's
// `AppRoutes.kt`. They live in the app module rather than in `:core:navigation` because they are
// fork-only, and the two recommendation routes carry app-module argument types.
//
// Everything on a route must be `@Serializable`: Navigation 3 persists the whole back stack.
package mihon.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object SortTagRoute : NavKey

@Serializable
data object SourceCategoryRoute : NavKey

@Serializable
data object BiometricTimesRoute : NavKey

@Serializable
data object AppCustomThemeColorPickerRoute : NavKey

@Serializable
data object OrphanedDownloadsRoute : NavKey

@Serializable
data object SyncSettingsSelectorRoute : NavKey

@Serializable
data object SyncTriggerOptionsRoute : NavKey

@Serializable
data object DictionaryManagementRoute : NavKey

@Serializable
data object DictionaryAudioSettingsRoute : NavKey

@Serializable
data object DictionaryBackupImportRoute : NavKey

/** The one-time dictionary/OCR setup screen (see `DictionaryOcrSetupPolicy`). */
@Serializable
data object DictionaryOcrSetupRoute : NavKey

@Serializable
data object LibraryUpdateErrorRoute : NavKey

@Serializable
data object SettingsDebugRoute : NavKey

@Serializable
data object DiscordAccountsRoute : NavKey

@Serializable
data object DiscordLoginRoute : NavKey

@Serializable
data object BatchAddRoute : NavKey

/** The updates and history tabs, opened as screens from the More screen. */
@Serializable
data object UpdatesTabRoute : NavKey

@Serializable
data object HistoryTabRoute : NavKey

@Serializable
data class WhatsNewRoute(
    val currentVersion: String,
    val versionName: String,
    val changelogInfo: String,
    val releaseLink: String,
) : NavKey

@Serializable
data class ComingUpdatesRoute(
    val versionName: String,
    val changelogInfo: String,
    val releaseLink: String,
    val downloadLink: String,
    // KUN -->
    val digest: String? = null,
    // KUN <--
) : NavKey

@Serializable
data class MangaDexFollowsRoute(val sourceId: Long) : NavKey

@Serializable
data class PagePreviewRoute(val mangaId: Long) : NavKey

@Serializable
data class SourceFeedRoute(val sourceId: Long) : NavKey

@Serializable
data class PaletteRoute(val seedColor: Int?) : NavKey

@Serializable
data class MetadataViewRoute(
    val mangaId: Long,
    val sourceId: Long,
    val seedColor: Int?,
) : NavKey

/** Smart search: pick a source to search the entry to merge with. */
@Serializable
data class SmartSearchRoute(
    val origTitle: String,
    val origMangaId: Long? = null,
) : NavKey

/** Smart search inside one source: search that source for the entry to merge with. */
@Serializable
data class SmartSearchSourceRoute(
    val sourceId: Long,
    val origTitle: String,
    val origMangaId: Long? = null,
) : NavKey

/** The extensions list filtered to a source's search, opened from a manga's source row. */
@Serializable
data class ExtensionsRoute(val searchSource: String? = null) : NavKey
// KUN <--
