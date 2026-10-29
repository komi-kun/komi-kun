package mihon.app.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.service.DelayedTrackingUpdateWorker
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadWorker
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.data.library.MetadataUpdateWorker
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ocr.OcrHostBindings
import eu.kanade.tachiyomi.ui.reader.ocr.impl.ReaderOcrPopupAnchor
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.setting.track.BaseOAuthLoginActivity
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.CrashLogUtil
import exh.GalleryAdder
import exh.eh.EHentaiUpdateHelper
import exh.eh.EHentaiUpdateWorker
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.dictionary.di.DictionaryBindings
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.metro.IsDebugBuild
import mihon.core.ocr.di.OcrBindings
import mihon.core.ocr.paddle.ModelDownloader
import mihon.data.cache.ChapterCache
import mihon.dictionary.host.DictionaryHostBindings
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.source.interactor.UpdateMangaFromRemote
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.debug.repository.DebugRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergeSettings
import tachiyomi.domain.manga.interactor.GetMergedEntriesById
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.source.repository.SavedSearchRepository
import tachiyomi.domain.source.repository.SourceMigrationRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack

@DependencyGraph(
    scope = AppScope::class,
    // KUN -->
    // Feature bindings live in workstream-owned containers (contract §3.6); AppBindings is W1-only.
    bindingContainers = [
        AppBindings::class,
        DictionaryBindings::class,
        OcrBindings::class,
        DictionaryHostBindings::class,
        OcrHostBindings::class,
    ],
    // KUN <--
)
interface AppGraph : ViewModelGraph {
    fun inject(app: App)
    fun inject(mainActivity: MainActivity)
    fun inject(readerActivity: ReaderActivity)
    fun inject(webViewActivity: WebViewActivity)
    fun inject(baseOAuthLoginActivity: BaseOAuthLoginActivity)
    fun inject(libraryUpdateWorker: LibraryUpdateWorker)
    fun inject(metadataUpdateWorker: MetadataUpdateWorker)
    fun inject(backupRestoreWorker: BackupRestoreWorker)
    fun inject(backupCreateWorker: BackupCreateWorker)
    fun inject(delayedTrackingUpdateWorker: DelayedTrackingUpdateWorker)
    fun inject(downloadWorker: DownloadWorker)
    // KUN -->
    fun inject(appUpdateDownloadJob: AppUpdateDownloadJob)
    fun inject(syncDataJob: SyncDataJob)
    // KUN <--
    fun inject(notificationReceiver: NotificationReceiver)
    fun inject(notificationReceiver: SecureActivityDelegateImpl)
    fun inject(extensionInstallActivity: ExtensionInstallActivity)
    // KUN -->
    fun inject(eHentaiUpdateWorker: EHentaiUpdateWorker)
    // KUN <--

    val context: Context

    val viewModelFactory: MetroViewModelFactory

    val basePreferences: BasePreferences
    val uiPreferences: UiPreferences
    val readerPreferences: ReaderPreferences
    val networkPreferences: NetworkPreferences
    val libraryPreferences: LibraryPreferences
    val sourcePreferences: SourcePreferences
    val trackPreferences: TrackPreferences
    val backupPreferences: BackupPreferences
    val storagePreferences: StoragePreferences
    val privacyPreferences: PrivacyPreferences
    val securityPreferences: SecurityPreferences
    val downloadPreferences: DownloadPreferences

    val crashLogUtil: CrashLogUtil

    val downloadManager: DownloadManager

    val updateChecker: AppUpdateChecker

    val trustExtension: TrustExtension

    val sourceManager: SourceManager
    val trackerManager: TrackerManager
    val extensionManager: ExtensionManager
    val chapterCache: ChapterCache
    val downloadCache: DownloadCache

    // KUN -->
    val connectionsManager: ConnectionsManager
    val connectionsPreferences: ConnectionsPreferences
    val syncPreferences: SyncPreferences
    val exhPreferences: ExhPreferences
    val delegateSourcePreferences: DelegateSourcePreferences
    val coverCache: CoverCache
    val pagePreviewCache: PagePreviewCache
    val googleDriveService: GoogleDriveService
    val toggleIncognito: ToggleIncognito
    val getAllManga: GetAllManga
    val getChaptersByMangaId: GetChaptersByMangaId
    val getFlatMetadataById: GetFlatMetadataById
    val deleteFavoriteEntries: DeleteFavoriteEntries
    val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata
    val getTracks: GetTracks
    val galleryAdder: GalleryAdder
    val getExtensionStores: GetExtensionStores
    val backupRestoreStatus: BackupRestoreStatus
    val libraryUpdateStatus: LibraryUpdateStatus
    val syncStatus: SyncStatus
    val ehEntaiUpdateHelper: EHentaiUpdateHelper
    val getManga: GetManga
    val insertFlatMetadata: InsertFlatMetadata
    val getMergedEntriesById: GetMergedEntriesById
    val getMergeSettings: GetMergeSettings
    val networkToLocalManga: NetworkToLocalManga
    val updateMangaFromRemote: UpdateMangaFromRemote
    val filterChaptersForDownload: FilterChaptersForDownload
    val debugRepository: DebugRepository
    val savedSearchRepository: SavedSearchRepository
    val sourceMigrationRepository: SourceMigrationRepository
    // KUN -->

    // W2 dictionary feature: exposed so Composable screens (popup/tab/settings) can reach
    // the repository and preferences via `context.appGraph` (plan 02 §5.3; the GPL impls
    // themselves are bound by `DictionaryHostBindings`/`@ContributesBinding`).
    val dictionaryPreferences: DictionaryPreferences
    val dictionaryRepository: DictionaryRepository

    // Live anchor of the OCR block whose dictionary popup is open, so the GPL popup can follow the
    // page without touching the Apache seam (see `ReaderOcrPopupAnchor`).
    val readerOcrPopupAnchor: ReaderOcrPopupAnchor
    // KUN <--

    // KUN -->
    // W3 OCR feature: exposed so the stateless `SettingsOcrScreen` reaches the **graph-scoped**
    // singleton via `context.appGraph` instead of building its own instance. Exactly one
    // `ModelDownloader` must drive state and downloads — its single-flight gate and `_state` flow
    // are only meaningful when every caller (the source-picker CTA, the status-row retry) shares it.
    val modelDownloader: ModelDownloader

    // The single dictionary/OCR opt-in (`pref_dictionary_ocr_enabled`): the setup surfaces, the
    // settings master switches, the navigation filter and every runtime gate read this one instance.
    val dictionaryOcrPreferences: DictionaryOcrPreferences
    // KUN <--

    val json: Json
    val protoBuf: ProtoBuf
    val xml: XML
    val networkHelper: NetworkHelper
    val javaScriptEngine: JavaScriptEngine

    val getFavorites: GetFavorites
    val getCategories: GetCategories
    val resetViewerFlags: ResetViewerFlags
    val resetCategoryFlags: ResetCategoryFlags
    val addTracks: AddTracks
    val upsertTrack: UpsertTrack

    val getExtensionStoreCountAsFlow: GetExtensionStoreCountAsFlow

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context, @Provides @IsDebugBuild isDebugBuild: Boolean): AppGraph
    }
}
