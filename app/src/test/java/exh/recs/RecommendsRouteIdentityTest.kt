package exh.recs

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `Navigator.push` skips a route equal to the one already on top, so two taps on the same entry open one
 * screen instead of stacking two. Both recommendation routes carry their arguments as a `ByteArray`
 * (see `RecommendsScreen.kt`), and these pin the property the dedupe relies on: a data class compares an
 * array property **by content** (`Arrays.equals` / `java.util.Arrays.hashCode`), so equal arguments give
 * equal routes. A plain `class`, or an `equals` that fell back to identity, would make every tap stack a
 * duplicate screen; this test then fails.
 */
class RecommendsRouteIdentityTest {

    private val args = RecommendsArgs.SingleSourceManga(mangaId = 1L, sourceId = 2L)

    /** The browse screen scores exactly one source, so it has its own argument type. */
    private val browseArgs = BrowseRecommendsArgs.SingleSourceManga(
        mangaId = 1L,
        sourceId = 2L,
        recommendationSourceName = "MangaDex",
    )

    @Test
    fun `two recommendation routes for the same entry are equal`() {
        RecommendsRoute(args) shouldBe RecommendsRoute(args)
        RecommendsRoute(args).hashCode() shouldBe RecommendsRoute(args).hashCode()
    }

    @Test
    fun `two browse-recommendation routes for the same source are equal, and the flag still separates them`() {
        BrowseRecommendsRoute(browseArgs, isExternalSource = true) shouldBe
            BrowseRecommendsRoute(browseArgs, isExternalSource = true)
        BrowseRecommendsRoute(browseArgs, isExternalSource = true) shouldNotBe
            BrowseRecommendsRoute(browseArgs, isExternalSource = false)
    }

    @Test
    fun `routes for different entries are not equal`() {
        val other = RecommendsArgs.SingleSourceManga(mangaId = 3L, sourceId = 2L)
        RecommendsRoute(args) shouldNotBe RecommendsRoute(other)
    }

    @Test
    fun `the route round-trips its arguments`() {
        RecommendsRoute(args).args() shouldBe args
        BrowseRecommendsRoute(browseArgs, isExternalSource = true).args() shouldBe browseArgs
        // The merged variant carries the paging results, which is why the route is not a plain data route.
        val merged = RecommendsArgs.MergedSourceMangas(emptyList())
        RecommendsRoute(merged).args().shouldBeInstanceOf<RecommendsArgs.MergedSourceMangas>()
    }
}
