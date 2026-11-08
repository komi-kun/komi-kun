package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.sync.MangaTombstoneRecorder

@Inject
class DeleteMangaById(
    private val mangaRepository: MangaRepository,
    private val tombstones: MangaTombstoneRecorder,
) {

    suspend fun await(id: Long) {
        // Load first: the row is gone after the delete, and a failed delete must not leave a
        // stale tombstone. Recording after a successful delete can at worst miss one tombstone.
        val manga = mangaRepository.getMangaById(id)
        mangaRepository.deleteManga(id)
        tombstones.rememberDeletedManga(manga.source, manga.url)
    }
}
