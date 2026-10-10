package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import exh.EXHMigrations
import exh.source.MERGED_SOURCE_ID
import tachiyomi.domain.backup.model.RestoredHistory
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.backup.repository.RestoreRepository
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import java.time.ZonedDateTime

@Inject
class MangaRestorer(
    private val restoreRepository: RestoreRepository,
    private val getCategories: GetCategories,
    private val fetchInterval: FetchInterval,
) {
    var isSync: Boolean = false
    private var now = ZonedDateTime.now()
    private var currentFetchWindow = fetchInterval.getWindow(now)

    init {
        now = ZonedDateTime.now()
        currentFetchWindow = fetchInterval.getWindow(now)
    }

    suspend fun sortByNew(backupMangas: List<BackupManga>): List<BackupManga> {
        val urlsBySource = restoreRepository.getMangaUrlsBySourceId()

        return backupMangas
            .sortedWith(
                // KMK -->
                compareBy<BackupManga> { it.source == MERGED_SOURCE_ID }
                    // KMK <--
                    .then(compareBy { it.url in urlsBySource[it.source].orEmpty() })
                    .then(compareByDescending { it.lastModifiedAt }),
            )
    }

    /**
     * Restores [backupMangas] all together, so either every one of them is restored or none is.
     */
    suspend fun restore(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategoriesByName = getCategories.await().associateBy { it.name }
        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val entries = backupMangas.map { backupManga ->
            var manga = backupManga.getMangaImpl()
            // SY -->
            manga = EXHMigrations.migrateBackupEntry(manga)
            // SY <--
            RestoredManga(
                manga = manga,
                chapters = backupManga.chapters.map { it.toChapterImpl() },
                categoryIds = backupManga.categories.mapNotNull { backupCategoryOrder ->
                    backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                        dbCategoriesByName[backupCategory.name]?.id
                    }
                },
                history = backupManga.history.map {
                    val history = it.getHistoryImpl()
                    RestoredHistory(it.url, history.readAt, history.readDuration)
                },
                tracks = backupManga.tracking.map { it.getTrackImpl() },
                excludedScanlators = backupManga.excludedScanlators,
                // SY -->
                mergedMangaReferences = backupManga.mergedMangaReferences.map {
                    EXHMigrations.migrateBackupMergedMangaReference(it).getMergedMangaReference()
                },
                flatMetadata = backupManga.flatMetadata?.getFlatMetadata(mangaId = 0L),
                customMangaInfo = backupManga.getCustomMangaInfo(),
                // SY <--
            )
        }

        restoreRepository.restoreManga(entries, isSync) {
            fetchInterval.toMangaUpdate(it, now, currentFetchWindow)
        }
    }

    // KMK/SY -->
    suspend fun updateManga(manga: Manga) {
        restoreRepository.updateManga(manga, isSync)
    }
    // KMK/SY <--

    // SY -->
    private fun BackupManga.getCustomMangaInfo(): CustomMangaInfo? {
        if (customTitle != null ||
            customArtist != null ||
            customAuthor != null ||
            customThumbnailUrl != null ||
            customDescription != null ||
            customGenre != null ||
            customStatus != 0
        ) {
            return CustomMangaInfo(
                id = 0L,
                title = customTitle,
                author = customAuthor,
                artist = customArtist,
                thumbnailUrl = customThumbnailUrl,
                description = customDescription,
                genre = customGenre,
                status = customStatus.takeUnless { it == 0 }?.toLong(),
            )
        }
        return null
    }
    // SY <--
}
