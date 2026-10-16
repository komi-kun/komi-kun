// SPDX-License-Identifier: GPL-3.0-or-later
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.presentation.theme.colorscheme.CustomColorScheme

/**
 * Builds the dictionary WebView shell HTML (02 §2, §3.3).
 *
 * Adapted from Chimahon: the novel `FontManager` dependency is dropped (the renderer uses no
 * custom font face), and theme colors are resolved by the caller rather than through Injekt.
 */
internal fun getDictionaryBootstrapHtml(
    context: Context,
    colorScheme: ColorScheme? = null,
    isDark: Boolean? = null,
    isAmoled: Boolean = false,
    fontFamily: String = "",
    eInkMode: Boolean = false,
    paginatedScrolling: Boolean = false,
    paginatedScrollStepSize: Int = 90,
    scrollBehavior: String = "smooth",
    languageCode: String = "",
    compactGlossary: Boolean = false,
): String {
    val css = dictionaryBaseCss.getOrPut(Unit) {
        readTextAsset(context.applicationContext, "dictionary/base.css")
    }
    val js = dictionaryRendererJs.getOrPut(Unit) {
        readTextAsset(context.applicationContext, "dictionary/renderer.js").replace("</script", "<\\/script")
    }
    val kanjiJs = dictionaryKanjiRendererJs.getOrPut(Unit) {
        readTextAsset(context.applicationContext, "dictionary/kanji-renderer.js")
    }

    val fontFaceCss = if (fontFamily.isNotBlank()) {
        val ff = when (fontFamily) {
            "System Serif" -> "serif"
            "System Sans-Serif" -> "sans-serif"
            else -> fontFamily
        }
        """
          :root, body, #entries {
            font-family: '$ff' !important;
          }
        """.trimIndent()
    } else {
        ""
    }

    val dynamicThemeCss = if (colorScheme != null) {
        val accentHex = "#%06X".format(0xFFFFFF and colorScheme.primary.toArgb())
        val onAccentHex = "#%06X".format(0xFFFFFF and colorScheme.onPrimary.toArgb())
        val fgHex = "#%06X".format(0xFFFFFF and colorScheme.onSurface.toArgb())
        val bgHex = if (isAmoled && isDark == true) "#000000" else "#%06X".format(0xFFFFFF and colorScheme.surface.toArgb())
        val secondaryHex = "#%06X".format(0xFFFFFF and colorScheme.onSurfaceVariant.toArgb())
        val borderHex = if (isAmoled && isDark == true) {
            "rgba(255,255,255,0.10)"
        } else {
            "#%06X".format(0xFFFFFF and colorScheme.outlineVariant.toArgb())
        }
        val hoverHex = if (isAmoled && isDark == true) {
            "rgba(255,255,255,0.07)"
        } else {
            "#%06X".format(0xFFFFFF and colorScheme.surfaceVariant.toArgb())
        }
        val tabBgHex = if (isAmoled && isDark == true) "#0d0d0d" else "#%06X".format(0xFFFFFF and colorScheme.surfaceContainer.toArgb())
        """
          <style id="dynamic-theme">
            :root, :root[data-theme="dark"], :root[data-theme="light"] {
                --accent: $accentHex;
                --on-accent: $onAccentHex;
                --fg: $fgHex;
                --bg: $bgHex;
                --secondary: $secondaryHex;
                --border: $borderHex;
                --hover-bg: $hoverHex;
                --tab-bg: $tabBgHex;
                --pronunciation-annotation-color: $fgHex;
            }
          </style>
        """
    } else {
        ""
    }

    val eInkAttr = if (eInkMode) "true" else "false"
    val paginatedScrollingAttr = if (paginatedScrolling) "true" else "false"
    val paginatedStepAttr = if (paginatedScrolling) paginatedScrollStepSize.toString() else "90"
    val themeAttr = if (isDark == true) "dark" else "light"
    val langAttr = if (languageCode.isNotEmpty()) """lang="$languageCode" """ else ""
    val compactGlossaryAttr = if (compactGlossary) "true" else "false"
    val glossaryLayoutModeAttr = if (compactGlossary) "compact" else "default"

    return """
        <!doctype html>
        <html $langAttr data-theme="$themeAttr" data-komikun-eink-mode="$eInkAttr" data-komikun-paginated-scrolling="$paginatedScrollingAttr" data-komikun-paginated-step="$paginatedStepAttr" data-komikun-scroll-behavior="$scrollBehavior" data-compact-glossary="$compactGlossaryAttr" data-glossary-layout-mode="$glossaryLayoutModeAttr">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
          <style>$css</style>$dynamicThemeCss
          <style>$fontFaceCss</style>
          <style id="dictionary-styles"></style>
          <style id="komikun-custom-css"></style>
          <script>
            window.AnkiBridge = {
              addToAnki: function(index, glossary, selectedDict, popupSelection) {
                if (typeof AnkiJsBridge !== 'undefined') {
                  AnkiJsBridge.addToAnki(
                    String(index),
                    String(glossary != null ? glossary : '-1'),
                    selectedDict || '',
                    popupSelection || ''
                  );
                }
              },
              openInAnki: function(index, glossary, selectedDict, popupSelection) {
                if (typeof AnkiJsBridge !== 'undefined') {
                  AnkiJsBridge.openInAnki(
                    String(index),
                    String(glossary != null ? glossary : '-1'),
                    selectedDict || '',
                    popupSelection || ''
                  );
                }
              }
            };
          </script>
        </head>
        <body>
          <main id="entries" class="entries"></main>
          <script>$js</script>
          <script>$kanjiJs</script>
        </body>
        </html>
    """.trimIndent()
}

private fun readTextAsset(context: Context, assetPath: String): String =
    context.assets.open(assetPath).use { input -> input.readBytes().toString(Charsets.UTF_8) }

private val dictionaryBaseCss = java.util.concurrent.ConcurrentHashMap<Unit, String>()
private val dictionaryRendererJs = java.util.concurrent.ConcurrentHashMap<Unit, String>()
private val dictionaryKanjiRendererJs = java.util.concurrent.ConcurrentHashMap<Unit, String>()

internal fun getDictionaryColorScheme(isDark: Boolean, isAmoled: Boolean, seedColor: Int): ColorScheme =
    CustomColorScheme(seed = Color(seedColor), style = PaletteStyle.TonalSpot).getColorScheme(isDark, isAmoled, false)

/**
 * Colors for the dictionary popup / entry WebView.
 *
 * Theme mode `"app"` follows the app theme, but is overridden by the dictionary custom color,
 * e-ink mode and [forceDefaultTheme].
 */
data class DictionaryThemeResolved(
    val isDark: Boolean,
    val isAmoled: Boolean,
    val seedColor: Int,
    val useAppTheme: Boolean,
)

internal fun resolveDictionaryTheme(
    themeMode: String,
    customColor: Int,
    eInkMode: Boolean,
    forceDefaultTheme: Boolean,
    systemIsDark: Boolean,
    appThemeMode: ThemeMode,
    appAmoled: Boolean,
    appColorTheme: Int,
): DictionaryThemeResolved {
    val useAppTheme = themeMode == "app" && !eInkMode && !forceDefaultTheme
    val hasCustomColor = customColor != 0 && !forceDefaultTheme && !useAppTheme
    val seedColor = if (hasCustomColor) customColor else appColorTheme
    val appIsDark = when (appThemeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemIsDark
    }
    val isAmoled = when {
        useAppTheme -> appAmoled
        themeMode == "pure_black" -> true
        else -> false
    }
    val isDark = when {
        useAppTheme -> appIsDark
        themeMode == "dark" || themeMode == "pure_black" -> true
        themeMode == "light" -> false
        hasCustomColor -> Color(seedColor).luminance() < 0.5f
        else -> systemIsDark
    }
    return DictionaryThemeResolved(
        isDark = isDark,
        isAmoled = isAmoled,
        seedColor = seedColor,
        useAppTheme = useAppTheme,
    )
}
