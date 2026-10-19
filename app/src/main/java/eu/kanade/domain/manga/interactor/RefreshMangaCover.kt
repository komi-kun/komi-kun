package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.log.xLogI
import exh.log.xLogW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Recovers a stale source cover when loading the stored URL fails.
 *
 * The source is asked for the manga's details again, but only the cover URL is persisted:
 * title, description, status, chapters, etc. stay untouched. This mirrors what a manual
 * refresh would fix, without turning a failed image load into a full entry refresh.
 *
 * Source calls are deliberately conservative:
 * - only one refresh per manga at a time, and at most one per [MANGA_COOLDOWN_MS];
 * - starts are paced per source ([SOURCE_MIN_INTERVAL_MS]), with a generous ceiling
 *   ([MAX_ATTEMPTS_PER_SOURCE] per [SOURCE_WINDOW_MS]) as a safety valve;
 * - at most [MAX_CONCURRENT_REQUESTS] source requests in flight across the app.
 */
@Inject
@SingleIn(AppScope::class)
class RefreshMangaCover(
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
    private val coverCache: CoverCache,
) {

    private data class InFlightRefresh(
        val staleUrl: String,
        val result: CompletableDeferred<String?>,
    )

    private data class Replacement(
        val staleUrl: String,
        val newUrl: String,
    )

    private val inFlight = ConcurrentHashMap<Long, InFlightRefresh>()
    private val lastReplacement = ConcurrentHashMap<Long, Replacement>()
    private val lastAttemptAt = ConcurrentHashMap<Long, Long>()
    private val sourceAttempts = HashMap<Long, ArrayDeque<Long>>()
    private val sourceNextAttemptAt = HashMap<Long, Long>()
    private val sourceAttemptsLock = Any()
    private val sourceSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

    /**
     * Asks the source for a replacement cover URL. Nothing is persisted here: the image is
     * persisted with [persistReplacement] only after it has been fetched successfully.
     *
     * @param mangaId the manga whose cover failed to load.
     * @param staleUrl the URL that failed, used to ensure the entry has not been refreshed meanwhile.
     * @return the new cover URL if the source provided a different one, otherwise `null`.
     */
    suspend fun findReplacement(mangaId: Long, staleUrl: String?): String? {
        if (mangaId <= 0 || staleUrl.isNullOrBlank()) return null

        // Join an existing refresh instead of starting another one for the same entry.
        inFlight[mangaId]?.let { current ->
            return if (current.staleUrl == staleUrl) current.result.await() else null
        }

        if (isCoolingDown(mangaId)) {
            // Concurrent Coil requests may have missed the in-flight window. Give them the
            // URL that was just discovered so they can load it without asking the source again.
            return lastReplacement[mangaId]?.takeIf { it.staleUrl == staleUrl }?.newUrl
        }

        val refresh = InFlightRefresh(staleUrl, CompletableDeferred())
        val leader = inFlight.putIfAbsent(mangaId, refresh)
        if (leader != null) {
            return if (leader.staleUrl == staleUrl) leader.result.await() else null
        }

        try {
            refresh.result.complete(fetchReplacement(mangaId, staleUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xLogW("Could not refresh the cover of manga $mangaId", e)
            refresh.result.complete(null)
        } finally {
            if (!refresh.result.isCompleted) refresh.result.complete(null)
            inFlight.remove(mangaId, refresh)
        }
        return refresh.result.await()
    }

    /**
     * Persists [newUrl] for [mangaId], but only if the entry still points at [staleUrl].
     * Called after the replacement image has been fetched so a dead replacement is not stored.
     */
    suspend fun persistReplacement(mangaId: Long, staleUrl: String?, newUrl: String): Boolean {
        if (mangaId <= 0 || staleUrl.isNullOrBlank() || newUrl == staleUrl) return false
        return try {
            val manga = mangaRepository.getMangaById(mangaId)

            // A manual refresh may have updated the entry while the image was being fetched.
            if (manga.ogThumbnailUrl != staleUrl) return false
            // Never override a user-provided cover.
            if (manga.thumbnailUrl != manga.ogThumbnailUrl) return false
            if (coverCache.hasCustomCover(mangaId)) return false

            // The WHERE clause keeps a concurrent manual refresh from being overwritten even
            // if it happens between the checks above and the write. Only the URL changes:
            // keeping the timestamp stable lets the already decoded image stay in Coil's cache.
            if (!mangaRepository.updateCover(mangaId, staleUrl, newUrl)) return false
            coverCache.deleteFromCache(manga, false)
            xLogI("Refreshed stale cover for manga $mangaId")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xLogW("Could not persist the refreshed cover of manga $mangaId", e)
            false
        }
    }

    private suspend fun fetchReplacement(mangaId: Long, staleUrl: String): String? {
        val manga = mangaRepository.getMangaById(mangaId)
        val currentUrl = manga.thumbnailUrl

        // Never override a user-provided cover, and only act on the entry we failed to load.
        if (currentUrl == null || currentUrl != staleUrl) return null
        if (currentUrl != manga.ogThumbnailUrl) return null
        if (coverCache.hasCustomCover(mangaId)) return null

        val source = sourceManager.get(manga.source) as? HttpSource ?: return null
        if (!awaitSourceSlot(source.id)) {
            xLogW("Cover refresh for manga $mangaId skipped: source ${source.id} budget exhausted")
            return null
        }

        val remote = try {
            sourceSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    source.getMangaUpdate(
                        manga = manga.toSManga(),
                        chapters = emptyList(),
                        fetchDetails = true,
                        fetchChapters = false,
                    )
                }
            }
        } catch (e: CancellationException) {
            // A cancelled image request should not lock this entry out for the whole cooldown.
            throw e
        } catch (e: Exception) {
            lastAttemptAt[mangaId] = monotonicNow()
            throw e
        }.manga
        lastAttemptAt[mangaId] = monotonicNow()

        val newUrl = remote.thumbnail_url
            ?.trim()
            ?.toHttpUrlOrNull()
            ?.toString()
            ?: return null

        // The source still advertises the URL that just failed; nothing useful to try.
        if (newUrl == currentUrl) return null

        lastReplacement[mangaId] = Replacement(staleUrl, newUrl)
        return newUrl
    }

    private fun isCoolingDown(mangaId: Long): Boolean {
        val lastAttempt = lastAttemptAt[mangaId] ?: return false
        return monotonicNow() - lastAttempt < MANGA_COOLDOWN_MS
    }

    /**
     * Waits until this source may be queried again. Requests are intentionally serialized per
     * source: a host migration can leave many covers stale at once, and a one-time repair should
     * still reach the source at a modest, steady rate instead of a burst.
     */
    private suspend fun awaitSourceSlot(sourceId: Long): Boolean {
        while (true) {
            val waitMs = synchronized(sourceAttemptsLock) {
                val now = monotonicNow()
                val next = sourceNextAttemptAt[sourceId] ?: 0L
                if (now >= next) {
                    if (!hasSourceBudget(sourceId, now)) return false
                    sourceNextAttemptAt[sourceId] = now + SOURCE_MIN_INTERVAL_MS
                    recordSourceAttempt(sourceId, now)
                    return true
                }
                next - now
            }
            delay(waitMs)
        }
    }

    private fun hasSourceBudget(sourceId: Long, now: Long): Boolean {
        val attempts = sourceAttempts.getOrPut(sourceId) { ArrayDeque() }
        while (attempts.isNotEmpty() && now - attempts.first() >= SOURCE_WINDOW_MS) {
            attempts.removeFirst()
        }
        return attempts.size < MAX_ATTEMPTS_PER_SOURCE
    }

    private fun recordSourceAttempt(sourceId: Long, now: Long) {
        sourceAttempts.getOrPut(sourceId) { ArrayDeque() }.addLast(now)
    }

    private fun monotonicNow(): Long = System.nanoTime() / 1_000_000

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 2
        const val MANGA_COOLDOWN_MS = 6 * 60 * 60 * 1000L // 6 hours
        // A one-time host migration can leave a whole library stale. These are a safety valve,
        // not a typical limit: the per-manga cooldown is what prevents repeated requests.
        const val MAX_ATTEMPTS_PER_SOURCE = 200
        const val SOURCE_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
        const val SOURCE_MIN_INTERVAL_MS = 300L
    }
}
