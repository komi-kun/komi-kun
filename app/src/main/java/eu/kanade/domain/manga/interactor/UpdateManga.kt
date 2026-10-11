package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Clock
import kotlin.time.Instant

@Inject
class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        return mangaRepository.update(mangaUpdate)
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return mangaRepository.updateAll(mangaUpdates)
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        dateTime: LocalDateTime = Clock.System.now().toLocalDateTime(timeZone),
        window: ClosedRange<Instant>? = fetchInterval.getWindow(dateTime.date, timeZone),
        foundRelease: Boolean = false,
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, timeZone, window, foundRelease),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Clock.System.now()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(
            MangaUpdate(
                id = mangaId,
                coverLastModified = Clock.System.now(),
            ),
        )
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Clock.System.now()
            false -> null
        }
        return mangaRepository.update(
            MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded),
        )
    }
}
