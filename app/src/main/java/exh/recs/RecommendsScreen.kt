package exh.recs

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import exh.recs.batch.RankedSearchResults
import exh.recs.components.RecommendsScreen
import exh.recs.sources.RECOMMENDS_SOURCE
import exh.recs.sources.StaticResultPagingSource
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.SmartSearchRoute
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.Serializable as JavaSerializable

@Composable
fun RecommendsScreen(args: RecommendsArgs) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current

    val viewModel = assistedMetroViewModel<RecommendsViewModel, RecommendsViewModel.Factory> {
        create(args = args)
    }
    val state by viewModel.state.collectAsState()

    // KMK -->
    val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()

    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteViewModel.backHandler()
    }
    // KMK <--

    val onClickItem = { manga: Manga ->
        navigator.push(
            when (manga.source) {
                RECOMMENDS_SOURCE -> SmartSearchRoute(origTitle = manga.ogTitle)
                else -> MangaRoute(manga.id, true)
            },
        )
    }

    val onLongClickItem = { manga: Manga ->
        when (manga.source) {
            RECOMMENDS_SOURCE -> WebViewActivity.newIntent(context, manga.url, title = manga.title).let(context::startActivity)
            else -> {
                // KMK -->
                // Add to favorite
                bulkFavoriteViewModel.addRemoveManga(
                    manga,
                    haptic,
                )
                // KMK <--
            }
        }
    }

    RecommendsScreen(
        title = if (args is RecommendsArgs.SingleSourceManga) {
            stringResource(SYMR.strings.similar, state.title.orEmpty())
        } else {
            stringResource(SYMR.strings.rec_common_recommendations)
        },
        state = state,
        navigateUp = navigator::pop,
        getManga = @Composable { manga: Manga -> viewModel.getManga(manga) },
        onClickSource = { pagingSource ->
            // Pass class name of paging source as screens need to be serializable
            navigator.push(
                BrowseRecommendsRoute(
                    when (args) {
                        is RecommendsArgs.SingleSourceManga ->
                            BrowseRecommendsArgs.SingleSourceManga(
                                args.mangaId,
                                args.sourceId,
                                pagingSource::class.qualifiedName!!,
                            )
                        is RecommendsArgs.MergedSourceMangas ->
                            BrowseRecommendsArgs.MergedSourceMangas(
                                (pagingSource as StaticResultPagingSource).data,
                            )
                    },
                    isExternalSource = pagingSource.associatedSourceId == null,
                ),
            )
        },
        onClickItem = { onClickItem(it) },
        onLongClickItem = { onLongClickItem(it) },
    )

    // KMK -->
    BulkFavoriteDialogs(
        bulkFavoriteViewModel = bulkFavoriteViewModel,
        dialog = bulkFavoriteState.dialog,
    )
    // KMK <--
}

sealed interface RecommendsArgs : JavaSerializable {
    data class SingleSourceManga(val mangaId: Long, val sourceId: Long) : RecommendsArgs
    data class MergedSourceMangas(val mergedResults: List<RankedSearchResults>) : RecommendsArgs
}

// KUN -->
/**
 * The recommendation screens take a [RecommendsArgs] / [BrowseRecommendsArgs] holding live `SManga` results, which cannot be
 * expressed as a kotlinx-serializable route. The route therefore carries the Java-serialized bytes
 * (both are `Serializable`, as they were for Voyager) so Navigation 3 can still persist the stack.
 *
 * A `ByteArray` property does not break `Navigator.push`'s "don't stack a route that is already on top"
 * check: a data class compares an array property with `Arrays.equals`, so two routes built from equal
 * arguments are equal. [RecommendsRouteIdentityTest] pins that, so it is not "fixed" by hand later.
 */
@Serializable
data class RecommendsRoute(val argsBytes: ByteArray) : NavKey {
    constructor(args: RecommendsArgs) : this(args.toByteArray())

    fun args(): RecommendsArgs = argsBytes.toArgs()
}

@Serializable
data class BrowseRecommendsRoute(
    val argsBytes: ByteArray,
    val isExternalSource: Boolean,
) : NavKey {
    constructor(args: BrowseRecommendsArgs, isExternalSource: Boolean) : this(args.toByteArray(), isExternalSource)

    fun args(): BrowseRecommendsArgs = argsBytes.toArgs()
}

private fun JavaSerializable.toByteArray(): ByteArray {
    val stream = java.io.ByteArrayOutputStream()
    java.io.ObjectOutputStream(stream).use { it.writeObject(this) }
    return stream.toByteArray()
}

@Suppress("UNCHECKED_CAST")
private fun <T> ByteArray.toArgs(): T {
    return java.io.ObjectInputStream(this.inputStream()).use { it.readObject() } as T
}
// KUN <--
