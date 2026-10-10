package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import mihon.core.migration.Migration
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.data.Database
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.GetSearchMetadata
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton

/**
 * Bridges singletons provided by the Metro [mihon.app.di.AppGraph] back into Injekt for the
 * Komikku/SY/KMK-only code that still resolves dependencies through Injekt.
 */
@Inject
class KMKMetroInteropModule(
    private val database: Database,

    private val uiPreferences: eu.kanade.domain.ui.UiPreferences,
    private val sourcePreferences: SourcePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val readerPreferences: ReaderPreferences,
    private val securityPreferences: SecurityPreferences,
    private val basePreferences: BasePreferences,
    private val connectionsPreferences: ConnectionsPreferences,
    private val syncPreferences: SyncPreferences,
    private val exhPreferences: ExhPreferences,
    private val delegateSourcePreferences: DelegateSourcePreferences,

    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val trackerManager: TrackerManager,
    private val ehEntaiUpdateHelper: EHentaiUpdateHelper,
    private val connectionsManager: ConnectionsManager,
    private val googleDriveService: GoogleDriveService,
    private val backupRestoreStatus: BackupRestoreStatus,
    private val syncStatus: SyncStatus,
    private val libraryUpdateStatus: LibraryUpdateStatus,

    private val backupCreatorFactory: BackupCreator.Factory,
    private val mangaRestorer: MangaRestorer,

    private val getCategories: GetCategories,
    private val getManga: GetManga,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val insertFlatMetadata: InsertFlatMetadata,
    private val getAllManga: GetAllManga,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val setMangaCategories: SetMangaCategories,
    private val getSearchMetadata: GetSearchMetadata,
    private val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata,
    private val getExtensionStores: GetExtensionStores,
    private val trustExtension: TrustExtension,
    private val getFavorites: GetFavorites,
    private val getApplicationRelease: GetApplicationRelease,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val addTracks: AddTracks,
    private val getTracks: GetTracks,
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val deleteSavedSearchById: DeleteSavedSearchById,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getRemoteManga: GetRemoteManga,
    private val insertSavedSearch: InsertSavedSearch,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val getExhSavedSearch: GetExhSavedSearch,
    private val toggleIncognito: ToggleIncognito,
    private val migrations: Set<Migration>,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(database)

        addSingleton(uiPreferences)
        addSingleton(sourcePreferences)
        addSingleton(libraryPreferences)
        addSingleton(readerPreferences)
        addSingleton(securityPreferences)
        addSingleton(basePreferences)
        addSingleton(connectionsPreferences)
        addSingleton(syncPreferences)
        addSingleton(exhPreferences)
        addSingleton(delegateSourcePreferences)

        addSingleton(sourceManager)
        addSingleton(downloadManager)
        addSingleton(downloadCache)
        addSingleton(trackerManager)
        addSingleton(ehEntaiUpdateHelper)
        addSingleton(connectionsManager)
        addSingleton(googleDriveService)
        addSingleton(backupRestoreStatus)
        addSingleton(syncStatus)
        addSingleton(libraryUpdateStatus)

        addSingleton(backupCreatorFactory)
        addSingleton(mangaRestorer)

        addSingleton(getCategories)
        addSingleton(getManga)
        addSingleton(getFlatMetadataById)
        addSingleton(insertFlatMetadata)
        addSingleton(getAllManga)
        addSingleton(getMergedReferencesById)
        addSingleton(networkToLocalManga)
        addSingleton(updateManga)
        addSingleton(updateMangaFromRemote)
        addSingleton(setMangaCategories)
        addSingleton(getSearchMetadata)
        addSingleton(getExhFavoriteMangaWithMetadata)
        addSingleton(getExtensionStores)
        addSingleton(trustExtension)
        addSingleton(getFavorites)
        addSingleton(getApplicationRelease)
        addSingleton(filterChaptersForDownload)
        addSingleton(addTracks)
        addSingleton(getTracks)
        addSingleton(getCustomMangaInfo)
        addSingleton(deleteSavedSearchById)
        addSingleton(getDuplicateLibraryManga)
        addSingleton(getRemoteManga)
        addSingleton(insertSavedSearch)
        addSingleton(setMangaDefaultChapterFlags)
        addSingleton(getExhSavedSearch)
        addSingleton(toggleIncognito)
        addSingleton<Set<Migration>>(migrations)

        // Exh metadata source bridge (source-api resolves these through Injekt).
        addSingleton<eu.kanade.tachiyomi.source.online.MetadataSource.GetMangaId>(getManga)
        addSingleton<eu.kanade.tachiyomi.source.online.MetadataSource.GetFlatMetadataById>(getFlatMetadataById)
        addSingleton<eu.kanade.tachiyomi.source.online.MetadataSource.InsertFlatMetadata>(insertFlatMetadata)

        addSingleton(xyz.nulldev.ts.api.http.serializer.FilterSerializer())
    }
}
