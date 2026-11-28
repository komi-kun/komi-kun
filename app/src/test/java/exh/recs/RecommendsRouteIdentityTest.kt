package exh.recs

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `Navigator.push` skips a route equal to the one already on top (`if (current != route)`), so two taps on the
 * same entry open one screen instead of stacking two.
 *
 * Both recommendation routes carry their arguments as a `ByteArray`, and the compiler's generated
 * `data class` equality compares that property with `Intrinsics.areEqual` → `equals` → **reference**
 * equality, so two routes built from equal arguments would not be equal and every tap would stack a
 * duplicate screen. The routes therefore override `equals`/`hashCode` to compare the serialized bytes, and
 * these assert with the `==` operator the navigator uses — not kotest's `shouldBe`, which compares data-class
 * members after turning a `byte[]` into a list and so passes either way.
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
    fun `two recommendation routes for the same entry are equal, as the navigator's dedupe checks`() {
        (RecommendsRoute(args) == RecommendsRoute(args)) shouldBe true
        RecommendsRoute(args).hashCode() shouldBe RecommendsRoute(args).hashCode()
    }

    @Test
    fun `two browse-recommendation routes for the same source are equal, and the flag still separates them`() {
        (
            BrowseRecommendsRoute(browseArgs, isExternalSource = true) ==
                BrowseRecommendsRoute(browseArgs, isExternalSource = true)
            ) shouldBe true
        BrowseRecommendsRoute(browseArgs, isExternalSource = true).hashCode() shouldBe
            BrowseRecommendsRoute(browseArgs, isExternalSource = true).hashCode()
        (
            BrowseRecommendsRoute(browseArgs, isExternalSource = true) ==
                BrowseRecommendsRoute(browseArgs, isExternalSource = false)
            ) shouldBe false
    }

    @Test
    fun `routes for different entries are not equal`() {
        val other = RecommendsArgs.SingleSourceManga(mangaId = 3L, sourceId = 2L)
        (RecommendsRoute(args) == RecommendsRoute(other)) shouldBe false
        (
            BrowseRecommendsRoute(args = browseArgs, isExternalSource = true) ==
                BrowseRecommendsRoute(
                    args = BrowseRecommendsArgs.SingleSourceManga(1L, 2L, "MangaUpdates"),
                    isExternalSource = true,
                )
            ) shouldBe false
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
