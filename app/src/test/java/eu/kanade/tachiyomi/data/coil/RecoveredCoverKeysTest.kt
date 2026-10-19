package eu.kanade.tachiyomi.data.coil

import coil3.request.Options
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import kotlin.time.Instant

class RecoveredCoverKeysTest {

    @Test
    fun `returns the stored key while the cover timestamp is unchanged`() {
        val mangaId = 42L
        val timestamp = Instant.fromEpochMilliseconds(1_000)

        RecoveredCoverKeys.put(mangaId, timestamp, "old;1000")

        RecoveredCoverKeys.get(mangaId, timestamp) shouldBe "old;1000"
    }

    @Test
    fun `ignores the stored key after a manual cover change`() {
        val mangaId = 43L
        val timestamp = Instant.fromEpochMilliseconds(1_000)

        RecoveredCoverKeys.put(mangaId, timestamp, "old;1000")

        RecoveredCoverKeys.get(mangaId, Instant.fromEpochMilliseconds(2_000)) shouldBe null
        // The stale entry is dropped as well.
        RecoveredCoverKeys.get(mangaId, timestamp) shouldBe null
    }

    @Test
    fun `cover keyer uses the recovered key until the timestamp changes`() {
        val mangaId = 44L
        val timestamp = Instant.fromEpochMilliseconds(1_000)
        val newTimestamp = Instant.fromEpochMilliseconds(2_000)
        val coverCache = mockk<CoverCache>()
        every { coverCache.hasCustomCover(mangaId) } returns false
        val cover = MangaCover(
            mangaId = mangaId,
            sourceId = 1L,
            isMangaFavorite = true,
            ogUrl = "https://new.example/cover.jpg",
            lastModified = timestamp,
        )

        RecoveredCoverKeys.put(mangaId, timestamp, "old;1000")
        val keyer = MangaCoverKeyer(coverCache)
        val options = mockk<Options>()

        keyer.key(cover, options) shouldBe "old;1000"
        keyer.key(cover.copy(lastModified = newTimestamp), options) shouldBe
            "https://new.example/cover.jpg;$newTimestamp"
    }
}
