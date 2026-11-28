package exh.pagepreview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import exh.pagepreview.components.PagePreviewScreen
import mihon.core.navigation.LocalNavigator

@Composable
fun PagePreviewScreen(mangaId: Long) {
    val viewModel = assistedMetroViewModel<PagePreviewViewModel, PagePreviewViewModel.Factory> {
        create(mangaId = mangaId)
    }
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val navigator = LocalNavigator.current
    PagePreviewScreen(
        state = state,
        pageDialogOpen = viewModel.pageDialogOpen,
        onPageSelected = viewModel::moveToPage,
        onOpenPage = { openPage(context, state, it) },
        onOpenPageDialog = { viewModel.pageDialogOpen = true },
        onDismissPageDialog = { viewModel.pageDialogOpen = false },
        navigateUp = navigator::pop,
    )
}

fun openPage(context: Context, state: PagePreviewState, page: Int) {
    if (state !is PagePreviewState.Success) return
    context.run {
        startActivity(ReaderActivity.newIntent(this, state.manga.id, state.chapter.id, page))
    }
}
