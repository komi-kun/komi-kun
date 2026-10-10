package mihon.app.di.injekt

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.manga.interactor.CreateSortTag
import eu.kanade.domain.manga.interactor.DeleteSortTag
import eu.kanade.domain.manga.interactor.GetPagePreviews
import eu.kanade.domain.manga.interactor.GetSortTag
import eu.kanade.domain.manga.interactor.ReorderSortTag
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.CreateSourceCategory
import eu.kanade.domain.source.interactor.DeleteSourceCategory
import eu.kanade.domain.source.interactor.GetExhSavedSearch
import eu.kanade.domain.source.interactor.GetSourceCategories
import eu.kanade.domain.source.interactor.RenameSourceCategory
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.eh.EHentaiUpdateHelper
import exh.pref.DelegateSourcePreferences
import exh.source.ExhPreferences
import mihon.core.migration.Migration
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.data.Database
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChapterByUrl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.service.HistoryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.libraryUpdateError.interactor.DeleteLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateError.interactor.GetLibraryUpdateErrorWithRelations
import tachiyomi.domain.libraryUpdateError.interactor.InsertLibraryUpdateErrors
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.GetLibraryUpdateErrorMessages
import tachiyomi.domain.libraryUpdateErrorMessage.interactor.InsertLibraryUpdateErrorMessages
import tachiyomi.domain.manga.interactor.DeleteByMergeId
import tachiyomi.domain.manga.interactor.DeleteFavoriteEntries
import tachiyomi.domain.manga.interactor.DeleteMangaById
import tachiyomi.domain.manga.interactor.GetAllManga
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetExhFavoriteMangaWithMetadata
import tachiyomi.domain.manga.interactor.GetFavoriteEntries
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaForDownloading
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.interactor.GetSearchMetadata
import tachiyomi.domain.manga.interactor.InsertFavoriteEntries
import tachiyomi.domain.manga.interactor.InsertFavoriteEntryAlternative
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import tachiyomi.domain.manga.interactor.InsertMergedReference
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.source.interactor.CountFeedSavedSearchBySourceId
import tachiyomi.domain.source.interactor.CountFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.DeleteSavedSearchById
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceIdFeed
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.InsertSavedSearch
import tachiyomi.domain.source.interactor.ReorderFeed
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.domain.upcoming.service.UpcomingPreferences
import tachiyomi.domain.updates.service.UpdatesPreferences
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
    private val networkPreferences: NetworkPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val readerPreferences: ReaderPreferences,
    private val downloadPreferences: DownloadPreferences,
    private val backupPreferences: BackupPreferences,
    private val storagePreferences: StoragePreferences,
    private val upcomingPreferences: UpcomingPreferences,
    private val updatesPreferences: UpdatesPreferences,
    private val historyPreferences: HistoryPreferences,
    private val privacyPreferences: PrivacyPreferences,
    private val securityPreferences: SecurityPreferences,
    private val basePreferences: BasePreferences,
    private val connectionsPreferences: ConnectionsPreferences,
    private val syncPreferences: SyncPreferences,
    private val exhPreferences: ExhPreferences,
    private val delegateSourcePreferences: DelegateSourcePreferences,

    private val sourceManager: SourceManager,
    private val chapterCache: ChapterCache,
    private val downloadProvider: DownloadProvider,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val trackerManager: TrackerManager,
    private val storageManager: StorageManager,
    private val androidStorageFolderProvider: AndroidStorageFolderProvider,
    private val uniFileTempFileManager: UniFileTempFileManager,
    private val pagePreviewCache: PagePreviewCache,
    private val ehEntaiUpdateHelper: EHentaiUpdateHelper,
    private val imageSaver: ImageSaver,
    private val connectionsManager: ConnectionsManager,
    private val googleDriveService: GoogleDriveService,
    private val backupRestoreStatus: BackupRestoreStatus,
    private val syncStatus: SyncStatus,
    private val libraryUpdateStatus: LibraryUpdateStatus,

    private val backupCreatorFactory: BackupCreator.Factory,
    private val mangaRestorer: MangaRestorer,
    private val libraryUpdateNotifier: LibraryUpdateNotifier,

    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val extensionStoreRepository: ExtensionStoreRepository,

    private val getCategories: GetCategories,
    private val getManga: GetManga,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val insertFlatMetadata: InsertFlatMetadata,
    private val getAllManga: GetAllManga,
    private val getMergedReferencesById: GetMergedReferencesById,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getHistory: GetHistory,
    private val getLibraryManga: GetLibraryManga,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateManga: UpdateManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val setMangaCategories: SetMangaCategories,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val insertFavoriteEntryAlternative: InsertFavoriteEntryAlternative,
    private val getSearchMetadata: GetSearchMetadata,
    private val getExhFavoriteMangaWithMetadata: GetExhFavoriteMangaWithMetadata,
    private val getChapterByUrl: GetChapterByUrl,
    private val updateExtensionStores: UpdateExtensionStores,
    private val getExtensionStores: GetExtensionStores,
    private val trustExtension: TrustExtension,
    private val getFavorites: GetFavorites,
    private val getApplicationRelease: GetApplicationRelease,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val upsertTrack: UpsertTrack,
    private val upsertHistory: UpsertHistory,
    private val updateCategory: UpdateCategory,
    private val hideCategory: HideCategory,
    private val createCategoryWithName: CreateCategoryWithName,
    private val removeHistory: RemoveHistory,
    private val addTracks: AddTracks,
    private val getTracks: GetTracks,
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val countFeedSavedSearchBySourceId: CountFeedSavedSearchBySourceId,
    private val countFeedSavedSearchGlobal: CountFeedSavedSearchGlobal,
    private val deleteByMergeId: DeleteByMergeId,
    private val deleteFavoriteEntries: DeleteFavoriteEntries,
    private val deleteFeedSavedSearchById: DeleteFeedSavedSearchById,
    private val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors,
    private val deleteMangaById: DeleteMangaById,
    private val deleteSavedSearchById: DeleteSavedSearchById,
    private val getChapter: GetChapter,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getFavoriteEntries: GetFavoriteEntries,
    private val getLibraryUpdateErrorMessages: GetLibraryUpdateErrorMessages,
    private val getLibraryUpdateErrorWithRelations: GetLibraryUpdateErrorWithRelations,
    private val getMergedMangaForDownloading: GetMergedMangaForDownloading,
    private val getRemoteManga: GetRemoteManga,
    private val getSavedSearchBySourceId: GetSavedSearchBySourceId,
    private val getSavedSearchBySourceIdFeed: GetSavedSearchBySourceIdFeed,
    private val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed,
    private val insertFavoriteEntries: InsertFavoriteEntries,
    private val insertFeedSavedSearch: InsertFeedSavedSearch,
    private val insertLibraryUpdateErrorMessages: InsertLibraryUpdateErrorMessages,
    private val insertLibraryUpdateErrors: InsertLibraryUpdateErrors,
    private val insertMergedReference: InsertMergedReference,
    private val insertSavedSearch: InsertSavedSearch,
    private val reorderFeed: ReorderFeed,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val createSortTag: CreateSortTag,
    private val deleteSortTag: DeleteSortTag,
    private val getPagePreviews: GetPagePreviews,
    private val getSortTag: GetSortTag,
    private val reorderSortTag: ReorderSortTag,
    private val createSourceCategory: CreateSourceCategory,
    private val deleteSourceCategory: DeleteSourceCategory,
    private val getExhSavedSearch: GetExhSavedSearch,
    private val getSourceCategories: GetSourceCategories,
    private val renameSourceCategory: RenameSourceCategory,
    private val toggleIncognito: ToggleIncognito,
    private val migrations: Set<Migration>,
) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(database)

        addSingleton(uiPreferences)
        addSingleton(sourcePreferences)
        addSingleton(networkPreferences)
        addSingleton(libraryPreferences)
        addSingleton(readerPreferences)
        addSingleton(downloadPreferences)
        addSingleton(backupPreferences)
        addSingleton(storagePreferences)
        addSingleton(upcomingPreferences)
        addSingleton(updatesPreferences)
        addSingleton(historyPreferences)
        addSingleton(privacyPreferences)
        addSingleton(securityPreferences)
        addSingleton(basePreferences)
        addSingleton(connectionsPreferences)
        addSingleton(syncPreferences)
        addSingleton(exhPreferences)
        addSingleton(delegateSourcePreferences)

        addSingleton(sourceManager)
        addSingleton(chapterCache)
        addSingleton(downloadProvider)
        addSingleton(downloadManager)
        addSingleton(downloadCache)
        addSingleton(trackerManager)
        addSingleton(storageManager)
        addSingleton(androidStorageFolderProvider)
        addSingleton(uniFileTempFileManager)
        addSingleton(pagePreviewCache)
        addSingleton(ehEntaiUpdateHelper)
        addSingleton(imageSaver)
        addSingleton(connectionsManager)
        addSingleton(googleDriveService)
        addSingleton(backupRestoreStatus)
        addSingleton(syncStatus)
        addSingleton(libraryUpdateStatus)

        addSingleton(backupCreatorFactory)
        addSingleton(mangaRestorer)
        addSingleton(libraryUpdateNotifier)

        addSingleton<MangaRepository>(mangaRepository)
        addSingleton<ChapterRepository>(chapterRepository)
        addSingleton<ExtensionStoreRepository>(extensionStoreRepository)

        addSingleton(getCategories)
        addSingleton(getManga)
        addSingleton(getFlatMetadataById)
        addSingleton(insertFlatMetadata)
        addSingleton(getAllManga)
        addSingleton(getMergedReferencesById)
        addSingleton(getChaptersByMangaId)
        addSingleton(getHistory)
        addSingleton(getLibraryManga)
        addSingleton(networkToLocalManga)
        addSingleton(updateManga)
        addSingleton(updateMangaFromRemote)
        addSingleton(setMangaCategories)
        addSingleton(setMangaChapterFlags)
        addSingleton(insertFavoriteEntryAlternative)
        addSingleton(getSearchMetadata)
        addSingleton(getExhFavoriteMangaWithMetadata)
        addSingleton(getChapterByUrl)
        addSingleton(updateExtensionStores)
        addSingleton(getExtensionStores)
        addSingleton(trustExtension)
        addSingleton(getFavorites)
        addSingleton(getApplicationRelease)
        addSingleton(filterChaptersForDownload)
        addSingleton(upsertTrack)
        addSingleton(upsertHistory)
        addSingleton(updateCategory)
        addSingleton(hideCategory)
        addSingleton(createCategoryWithName)
        addSingleton(removeHistory)
        addSingleton(addTracks)
        addSingleton(getTracks)
        addSingleton(getCustomMangaInfo)
        addSingleton(countFeedSavedSearchBySourceId)
        addSingleton(countFeedSavedSearchGlobal)
        addSingleton(deleteByMergeId)
        addSingleton(deleteFavoriteEntries)
        addSingleton(deleteFeedSavedSearchById)
        addSingleton(deleteLibraryUpdateErrors)
        addSingleton(deleteMangaById)
        addSingleton(deleteSavedSearchById)
        addSingleton(getChapter)
        addSingleton(getDuplicateLibraryManga)
        addSingleton(getFavoriteEntries)
        addSingleton(getLibraryUpdateErrorMessages)
        addSingleton(getLibraryUpdateErrorWithRelations)
        addSingleton(getMergedMangaForDownloading)
        addSingleton(getRemoteManga)
        addSingleton(getSavedSearchBySourceId)
        addSingleton(getSavedSearchBySourceIdFeed)
        addSingleton(getSavedSearchGlobalFeed)
        addSingleton(insertFavoriteEntries)
        addSingleton(insertFeedSavedSearch)
        addSingleton(insertLibraryUpdateErrorMessages)
        addSingleton(insertLibraryUpdateErrors)
        addSingleton(insertMergedReference)
        addSingleton(insertSavedSearch)
        addSingleton(reorderFeed)
        addSingleton(setMangaDefaultChapterFlags)
        addSingleton(createSortTag)
        addSingleton(deleteSortTag)
        addSingleton(getPagePreviews)
        addSingleton(getSortTag)
        addSingleton(reorderSortTag)
        addSingleton(createSourceCategory)
        addSingleton(deleteSourceCategory)
        addSingleton(getExhSavedSearch)
        addSingleton(getSourceCategories)
        addSingleton(renameSourceCategory)
        addSingleton(toggleIncognito)
        addSingleton<Set<Migration>>(migrations)

        // Exh metadata source bridge (source-api resolves these through Injekt).
        addSingleton<eu.kanade.tachiyomi.source.online.MetadataSource.GetMangaId>(getManga)
        addSingleton<eu.kanade.tachiyomi.source.online.MetadataSource.GetFlatMetadataById>(getFlatMetadataById)
        addSingleton<eu.kanade.tachiyomi.source.online.MetadataSource.InsertFlatMetadata>(insertFlatMetadata)

        addSingleton(xyz.nulldev.ts.api.http.serializer.FilterSerializer())
    }
}
