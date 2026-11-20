package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import androidx.annotation.ColorInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.theme.TachiyomiTheme
import mihon.app.di.appGraph

/**
 * [content] in the app's theme under the reader's own night mode, from [readerThemedContext].
 *
 * Pages sit on the reader background, so a light app over a black reader would otherwise draw dark text on black.
 * [TachiyomiTheme] reads that from the configuration, which the view-based viewers got by inflating their views
 * from a reader-themed context.
 *
 * KUN --> [seedColor] is the reader's cover seed (KMK R19), the same one [ReaderProgressIndicator] and
 * [ReaderTransitionView] were given before this moved here.
 */
@Composable
fun ReaderThemedContent(
    readerThemedContext: Context,
    // KMK -->
    @ColorInt seedColor: Int? = null,
    // KMK <--
    content: @Composable () -> Unit,
) {
    // KMK -->
    val themeCoverBased = LocalContext.current.appGraph.uiPreferences.themeCoverBased.get()
    // KMK <--
    CompositionLocalProvider(
        LocalContext provides readerThemedContext,
        LocalConfiguration provides readerThemedContext.resources.configuration,
    ) {
        TachiyomiTheme(
            // KMK -->
            seedColor = seedColor?.let { Color(it) }.takeIf { themeCoverBased },
            // KMK <--
            content = content,
        )
    }
}
