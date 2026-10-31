package eu.kanade.tachiyomi.ui.reader

import android.Manifest
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.reader.ChapterListDialog
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderNavigationOverlay
import eu.kanade.presentation.reader.ReaderNavigationOverlayState
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.ReaderData
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrHost
import eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrSession
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.LocalPageColorFilter
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTwoFingerTapDetector
import eu.kanade.tachiyomi.ui.reader.viewer.OutsideTapDismissDetector
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import exh.source.isEhBasedSource
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import mihon.dictionary.host.ReaderDictionaryHost
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.UrlUtils
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.graphics.Color as ComposeColor

class ReaderActivity : BaseActivity() {

    private val graph: AppGraph by lazy { metroGraph() }

    companion object {

        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?/* SY --> */, page: Int? = null/* SY <-- */): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                // SY -->
                putExtra("page", page)
                // SY <--
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    @Inject private lateinit var readerPreferences: ReaderPreferences

    @Inject private lateinit var preferences: BasePreferences

    // KUN -->
    // Apache-2.0 reader OCR/dictionary seams. The GPL implementations and their DI bindings live in
    // `eu.kanade.tachiyomi.ui.reader.ocr.impl` / `mihon.dictionary.impl`; this file never sees them.
    @Inject private lateinit var readerOcrHost: ReaderOcrHost

    /**
     * This reader's OCR overlay session — created in [onCreate], closed in [onDestroy], and the only
     * owner of the overlay's per-reader state (viewport, active block, popup anchor). The host is
     * app-scoped and hands out one session per reader; a session never outlives its reader, so
     * neither can the state it holds.
     */
    private var ocrSession: ReaderOcrSession? = null

    @Inject private lateinit var readerDictionaryHost: ReaderDictionaryHost

    /**
     * The combined dictionary/OCR opt-in. Every reader entry point checks it before doing any
     * dictionary/OCR work, so "off" means the feature is inert rather than merely invisible.
     */
    @Inject private lateinit var dictionaryOcrPreferences: DictionaryOcrPreferences
    // KUN <--

    // KMK -->
    @Inject private lateinit var uiPreferences: UiPreferences
    val themeCoverBased get() = uiPreferences.themeCoverBased.get()
    // KMK <--

    // AM (CONNECTIONS) -->
    @Inject private lateinit var connectionsPreferences: ConnectionsPreferences
    // <-- AM (CONNECTIONS)

    val viewModel by viewModels<ReaderViewModel> { graph.viewModelFactory }
    val settingsViewModel by viewModels<ReaderSettingsViewModel> {
        viewModelFactory {
            initializer {
                ReaderSettingsViewModel(
                    readerState = viewModel.state,
                    // KUN -->
                    // The fork's settings view model takes the reader state and the manga's setters
                    // rather than the ReaderViewModel itself. `readingMode` is resolved through the same
                    // `getMangaReadingMode` this activity builds the viewer from, so the settings page
                    // and the viewer agree, auto-webtoon resolution (R45) included.
                    onChangeReadingMode = viewModel::setMangaReadingMode,
                    onChangeOrientation = viewModel::setMangaOrientationType,
                    // KUN <--
                    preferences = readerPreferences,
                    basePreferences = preferences,
                    // KUN -->
                    resolveReadingMode = { ReadingMode.fromPreference(viewModel.getMangaReadingMode()) },
                    // KUN <--
                )
            }
        }
    }
    private var assistUrl: String? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    // KUN -->
    // Constructed lazily (TachiyomiSY 68fd60274e): the field's dependencies are not ready at
    // property-initialisation time, which crashed the reader on open.
    private val displayRefreshHost by lazy { DisplayRefreshHost() }
    // KUN <--

    // KUN -->
    private val navigationOverlayState = ReaderNavigationOverlayState()
    // KUN <--

    // KUN -->
    /**
     * Two-finger tap detector for the OCR overlay toggle (`ReaderPreferences.ocrTwoFingerGestureEnabled`).
     * It is fed from [dispatchTouchEvent] and is deliberately non-consuming, so it works identically
     * across the pager, webtoon and WebGPU backends without touching their page-turn/zoom/pan gestures.
     */
    private val ocrTwoFingerTapDetector by lazy {
        OcrTwoFingerTapDetector(
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            tapTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(600L),
        ).apply {
            // KUN -->
            // Two gates: the combined dictionary/OCR opt-in (nothing runs while it is off) and the
            // existing "allow the gesture" preference.
            isEnabled = {
                dictionaryOcrPreferences.isEnabled() && readerPreferences.ocrTwoFingerGestureEnabled.get()
            }
            // KUN <--
            onTap = { onOcrTwoFingerTap() }
        }
    }

    /**
     * Passive single-finger tap detector that dismisses the **non-modal** dictionary popup when the
     * tap lands outside it. Fed from [dispatchTouchEvent] and deliberately non-consuming: the page
     * keeps scrolling/zooming/paging under the popup, and only a confirmed outside tap closes it.
     * While no popup is open [ReaderDictionaryHost.popupBounds] is null and the detector is a no-op.
     *
     * A tap that lands on an OCR block is *not* treated as an outside tap: the single-tap path
     * ([onPageSingleTap]) is about to move the popup to that block, so dismissing first would only
     * make the popup flicker closed and reopen.
     */
    private val dictionaryOutsideTapDetector by lazy {
        OutsideTapDismissDetector(
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            tapTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong(),
        ).apply {
            boundsProvider = {
                readerDictionaryHost.popupBounds()?.let { rect ->
                    OutsideTapDismissDetector.Bounds { x, y -> rect.contains(x, y) }
                }
            }
            onOutsideTap = { x, y ->
                // KUN -->
                // Same rule as the tap handlers below: a hit test is only run for a visible overlay.
                val targetsBlock = ReaderOcrOverlayPolicy.acceptsLookup(
                    masterEnabled = dictionaryOcrPreferences.isEnabled(),
                    overlayEnabled = readerPreferences.ocrOverlayEnabled.get(),
                    menuVisible = viewModel.state.value.menuVisible,
                ) && ocrSession?.hitsBlock(Offset(x, y)) == true
                // KUN <--
                if (!targetsBlock) readerDictionaryHost.dismiss()
            }
        }
    }

    /** Scratch buffer for the compose overlay's screen location; UI thread only. */
    private val composeOverlayLocation = IntArray(2)

    /**
     * The Compose content root (`setComposeContent`) - the window-relative origin every OCR overlay
     * and dictionary popup coordinate is reported in. It replaced the layout's `compose_overlay`
     * ComposeView when the reader moved to one Compose root (upstream 00335b2a70).
     */
    val composeOverlayRoot: View get() = findViewById(android.R.id.content)
    // KUN <--

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    // KUN -->
    // Whether the viewer is still being loaded. The Compose root draws the loading chrome over it
    // (upstream 00335b2a70) instead of the activity adding a progress view to its container.
    private var isLoadingViewer by mutableStateOf(false)
    // KUN <--

    /**
     * Viewer used to display the pages (pager, webtoon, ...).
     */
    private val viewerFlow = MutableStateFlow<Viewer?>(null)
    private val viewer: Viewer? inline get() = viewerFlow.value

    var isScrollingThroughPages = false
        private set

    // KUN -->
    /**
     * Called when a chapter is opened while this reader instance still exists.
     *
     * This activity is `singleTask` and [newIntent] adds `FLAG_ACTIVITY_CLEAR_TOP`, so opening a
     * chapter while a reader task is alive (e.g. after tapping the manga title in the reader's
     * toolbar and picking a chapter on the series page) brings the running reader forward instead
     * of creating a new one, and the framework hands the intent here. Ignoring it, as the default
     * does, answers the tap with the chapter the survivor was already on ("tap chapter 1, land on
     * chapter 4"), so the request is forwarded to the view model, which opens it on the same path a
     * fresh launch takes.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val target = ReaderSessionTarget(
            mangaId = intent.getLongExtra("manga", -1L),
            chapterId = intent.getLongExtra("chapter", -1L),
            page = intent.getIntExtra("page", -1).takeUnless { it == -1 },
        )
        // Intents that carry no chapter (the `REMOTE_ACTION` filter, for one) are not requests.
        if (!target.isValid) return

        // Adopt the intent: `seedColorStatic()` reads the manga from it, and it is what a recreation
        // starts from.
        setIntent(intent)
        // Whatever the dictionary popup was anchored to belongs to the chapter being left. The OCR
        // session stays — it belongs to this reader, which continues — so it only releases the
        // chapter's state.
        ocrSession?.reset()

        viewModel.openChapter(target)
    }
    // KUN <--

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        // KUN -->
        // This reader's OCR overlay session. Created here — after the graph is built and before the
        // Compose tree that composes the overlay — and closed in `onDestroy`; the session (not the
        // app-scoped host) owns the overlay's viewport, active block and popup anchor, so a reader's
        // state cannot outlive it. A recreation asks the host for the session the closed one handed
        // over, which is what keeps an open lookup across a rotation.
        ocrSession = readerOcrHost.newSession()

        // Upstream 00335b2a70 draws the reader through Compose: one Compose root holds the viewer,
        // the chrome and the dialogs, replacing the XML binding the activity used to inflate.
        setComposeContent {
            ReaderScreen()
        }
        // KUN <--

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }

        NotificationReceiver.dismissNotification(
            this,
            viewModel.mangaId.hashCode(),
            Notifications.ID_NEW_CHAPTERS,
        )

        settingsViewModel.brightness
            .onEach(::setCustomBrightnessValue)
            .launchIn(lifecycleScope)
        // KUN -->
        // The tap zones are drawn by the Compose overlay: the viewer publishes its navigator into
        // `settingsViewModel.navigation` (upstream 00335b2a70) and this pushes it into the overlay.
        // The fork reads the "new user" flag here rather than through the settings view model, so the
        // overlay is still shown once to a reader new to the app.
        val showOverlayForNewUser = readerPreferences.showNavigationOverlayNewUser.get().also { first ->
            if (first) readerPreferences.showNavigationOverlayNewUser.set(false)
        }
        settingsViewModel.navigation
            .filterNotNull()
            .onEach { navigation ->
                val showOnStart = showOverlayForNewUser ||
                    settingsViewModel.general.value.navigationOverlayOnStart
                navigationOverlayState.setNavigation(navigation, showOnStart)
            }
            .launchIn(lifecycleScope)
        // The display profile is a loaded image the SSIV decoders read (KMK), not a value in the
        // settings plane, so it keeps its own observer.
        preferences.displayProfile.changes()
            .onEach { setDisplayProfile(it) }
            .launchIn(lifecycleScope)
        // The dual page engine's own reactions to the SY layout preferences. Step 4 rebuilds it.
        readerPreferences.pageLayout.changes()
            .drop(1)
            .onEach {
                viewModel.setDoublePages(
                    // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                    (viewer as? PagerViewer)
                        ?.doublePages
                        ?: false,
                    // KUN <--
                )
            }
            .launchIn(lifecycleScope)

        readerPreferences.dualPageSplitPaged.changes()
            .drop(1)
            .onEach {
                // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                if (viewer !is PagerViewer) return@onEach
                // KUN <--
                reloadChapters(
                    !it &&
                        when (ReaderPreferences.PageLayout.fromValue(readerPreferences.pageLayout.get())) {
                            ReaderPreferences.PageLayout.DOUBLE_PAGES -> true
                            ReaderPreferences.PageLayout.AUTOMATIC ->
                                resources.configuration.orientation ==
                                    Configuration.ORIENTATION_LANDSCAPE
                            else -> false
                        },
                    true,
                )
            }
            .launchIn(lifecycleScope)
        // KUN <--
        setMenuVisibility(viewModel.state.value.menuVisible)

        // EXH -->
        enableExhAutoScroll()
        // EXH <--

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.initError }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setInitialChapterError)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page /* SY --> */, event.secondPage /* SY <-- */)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    // KUN -->
    // KMK: the reader's chrome is drawn in the cover-seeded theme (R19). `setComposeOverlay` wrapped
    // the old `ComposeView` in it; the Compose root does the same, so the seed survives the move.
    @Composable
    private fun ReaderScreen() {
        // KMK -->
        TachiyomiTheme(
            seedColor = seedColorState().takeIf { themeCoverBased },
            typography = MaterialTheme.typography.copy(
                bodyLarge = MaterialTheme.typography.bodySmall,
            ),
        ) {
            val context = LocalContext.current
            // KMK <--
            val state by viewModel.state.collectAsState()
            // KUN -->
            // The viewer is owned by the activity (upstream ca028cb657): the OCR overlay maps
            // through the viewer the reader is currently drawing.
            val viewer by viewerFlow.collectAsState()
            // KUN <--
            val general by settingsViewModel.general.collectAsState()
            val showPageNumber by readerPreferences.showPageNumber.collectAsState()
            // KUN -->
            val dictionaryOcrEnabled by dictionaryOcrPreferences.enabled.collectAsState()
            val ocrOverlayEnabled by readerPreferences.ocrOverlayEnabled.collectAsState()
            // KUN <--
            val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()
            val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
            val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
            val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()

            val backgroundColor = remember(general.theme) {
                ComposeColor(baseContext.readerBackgroundColor(general.theme))
            }
            val colorOverlayBlendMode = remember(colorOverlayMode) {
                ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            navigationOverlayState.hide()
                        }
                    }
                    .then(if (general.keepScreenOn) Modifier.keepScreenOn() else Modifier),
            ) {
                ReaderViewer()

                if (isLoadingViewer) {
                    // KUN --> R20: the seed reaches this spinner from ReaderScreen's ambient theme
                    LoadingScreen()
                    // KUN <--
                    return@Box
                }

                ReaderContentOverlay(
                    brightness = state.brightnessOverlayValue,
                    color = colorOverlay.takeIf { colorOverlayEnabled },
                    colorBlendMode = colorOverlayBlendMode,
                )

                if (!state.menuVisible && showPageNumber) {
                    ReaderPageIndicator(
                        // SY -->
                        currentPage = state.currentPageText,
                        // SY <--
                        totalPages = state.totalPages,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                }

                if (flashOnPageChange) {
                    DisplayRefreshHost(hostState = displayRefreshHost)
                }

                // KUN -->
                // Composed **before** the OCR overlay so the overlay draws above the side navigator
                // (pre-merge order: the native navigator `View` preceded the Compose root). Moving
                // it back below the OCR block is guarded by `ReaderOcrOverlayWiringTest`.
                // KUN <--
                ReaderNavigationOverlay(state = navigationOverlayState)

                // KUN -->
                // OCR block outlines and text belong to the **page layer**: drawn here, between the
                // brightness/colour overlay and the reader chrome. Sibling order in a `Box` is draw
                // order, so the overlay sits **above the side navigator and below the bars**:
                // - `ReaderNavigationOverlay` is composed before it (pre-merge the navigator was the
                //   native `ReaderNavigationOverlayView`, a `FrameLayout` sibling preceding
                //   `compose_overlay` in the deleted `reader_activity.xml`, so the overlay drew over
                //   it — this order restores that);
                // - the page indicator is an earlier sibling too: it is page-layer HUD, not
                //   interactive chrome, so it must stay underneath;
                // - the top/bottom `AppBars` are composed after it and are only 0.9 (dark) / 0.95
                //   (light) opaque, hence the alpha gate below.
                // It used to be the last sibling of this `Box` (after `AppBars`), i.e. drawn over the
                // whole reader UI. It is draw-only and adds no pointer input, so it never intercepts
                // viewer gestures (plan 04 §2.1). Contents, preferences and the tap/long-press path
                // are unchanged; only the layer moved.
                //
                // While the chrome is up the overlay still *composes* but paints nothing:
                // - it must not paint, because the bars are only 0.9 (dark) / 0.95 (light) opaque,
                //   so boxes underneath would ghost through them;
                // - it must stay composed, because `Overlay` also records the viewport the tap /
                //   long-press hit-test reads (a `SideEffect`) and drives block priming plus
                //   dictionary-popup anchoring from its `produceState` loop — unmounting it while
                //   the menu is up would freeze those, and `PagerViewer`/`WebtoonViewer` still
                //   route single taps through `onPageSingleTap` with the menu open.
                // Drawn only while the combined dictionary/OCR opt-in is on AND the overlay itself
                // is enabled; a null viewport means the viewer has no mapping yet.
                //
                // It is drawn above `ReaderNavigationOverlay` and below `AppBars` for that page-layer
                // reason, and it sits below the loading screen's early return: while a new viewer
                // loads the overlay is not composed at all, and `ReaderOcrSessionImpl.Overlay` clears
                // the viewport it recorded when it leaves composition, so a swapped viewer can
                // never be hit-tested against the one before it.
                val paintOcrOverlay = ReaderOcrOverlayPolicy.visible(
                    masterEnabled = dictionaryOcrEnabled,
                    overlayEnabled = ocrOverlayEnabled,
                    menuVisible = state.menuVisible,
                )
                if (dictionaryOcrEnabled && ocrOverlayEnabled) {
                    ocrSession?.Overlay(
                        viewport = viewer?.ocrViewport(),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (paintOcrOverlay) 1f else 0f },
                    )
                }
                // KUN <--

                // KUN -->
                // Must stay **after** the OCR overlay above: it is the reader chrome, and the overlay
                // is a page-layer draw that the bars are meant to cover (they are only translucent,
                // hence the alpha gate above). Moving `AppBars` back above it is guarded by
                // `ReaderOcrOverlayWiringTest`.
                // KUN <--
                AppBars(state = state)
            }

            // KMK -->
            val externalStoragePermissionNotGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_DENIED
            val permissionRequester = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {
                    toast(KMR.strings.permission_writing_external_storage_succeed)
                },
            )
            // KMK <--

            val onDismissRequest = viewModel::closeDialog
            when (val dialog = state.dialog) {
                is ReaderViewModel.Dialog.Loading -> {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    // KMK -->
                                    color = MaterialTheme.colorScheme.primary,
                                    // KMK <--
                                )
                                Text(stringResource(MR.strings.loading))
                            }
                        },
                    )
                }

                is ReaderViewModel.Dialog.Settings -> {
                    ReaderSettingsDialog(
                        onDismissRequest = onDismissRequest,
                        onShowMenus = { setMenuVisibility(true) },
                        onHideMenus = { setMenuVisibility(false) },
                        viewModel = settingsViewModel,
                    )
                }

                is ReaderViewModel.Dialog.ReadingModeSelect -> {
                    ReadingModeSelectDialog(
                        onDismissRequest = onDismissRequest,
                        viewModel = settingsViewModel,
                        onChange = { stringRes ->
                            menuToggleToast?.cancel()
                            if (!readerPreferences.showReadingMode.get()) {
                                menuToggleToast = toast(stringRes)
                            }
                        },
                    )
                }

                is ReaderViewModel.Dialog.OrientationModeSelect -> {
                    OrientationSelectDialog(
                        onDismissRequest = onDismissRequest,
                        viewModel = settingsViewModel,
                        onChange = { stringRes ->
                            menuToggleToast?.cancel()
                            menuToggleToast = toast(stringRes)
                        },
                    )
                }

                is ReaderViewModel.Dialog.PageActions -> {
                    ReaderPageActionsDialog(
                        onDismissRequest = onDismissRequest,
                        onSetAsCover = viewModel::setAsCover,
                        onShare = viewModel::shareImage,
                        // SY -->
                        onSave = { extra ->
                            // KMK -->
                            if (externalStoragePermissionNotGranted) {
                                permissionRequester.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                // KMK <--
                                viewModel.saveImage(extra)
                            }
                        },
                        // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                        onShareCombined = { copyToClipboard ->
                            viewModel.shareImages(copyToClipboard, viewer as? PagerViewer)
                        },
                        // KUN <--
                        onSaveCombined = {
                            // KMK -->
                            if (externalStoragePermissionNotGranted) {
                                permissionRequester.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                // KMK <--
                                // KUN -->
                                viewModel.saveImages(viewer as? PagerViewer)
                                // KUN <--
                            }
                        },
                        hasExtraPage = (state.dialog as? ReaderViewModel.Dialog.PageActions)?.extraPage != null,
                    )
                }

                is ReaderViewModel.Dialog.ChapterList -> {
                    var chapters by remember {
                        mutableStateOf(viewModel.getChapters().toList())
                    }
                    ChapterListDialog(
                        onDismissRequest = onDismissRequest,
                        viewModel = settingsViewModel,
                        chapters = chapters,
                        onClickChapter = {
                            viewModel.loadNewChapterFromDialog(it)
                            onDismissRequest()
                        },
                        onBookmark = { chapter ->
                            viewModel.toggleBookmark(chapter.id, !chapter.bookmark)
                            chapters = chapters.map {
                                if (it.chapter.id == chapter.id) {
                                    it.copy(chapter = chapter.copy(bookmark = !chapter.bookmark))
                                } else {
                                    it
                                }
                            }.toList()
                        },
                        state.dateRelativeTime,
                        // KMK -->
                        onDownloadAction = { chapter, action ->
                            viewModel.handleDownloadAction(chapter, action)
                        },
                        // KMK <--
                    )
                }

                ReaderViewModel.Dialog.AutoScrollHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_autoscroll_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_autoscroll_help_message)) },
                )

                ReaderViewModel.Dialog.AutoScrollGesturesHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_autoscroll_gestures_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_autoscroll_gestures_help_message)) },
                )

                ReaderViewModel.Dialog.AutoScrollGestureToastsHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_autoscroll_gesture_toasts_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_autoscroll_gesture_toasts_help_message)) },
                )

                ReaderViewModel.Dialog.BoostPageHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_boost_page_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_boost_page_help_message)) },
                )

                ReaderViewModel.Dialog.RetryAllHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_retry_all_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_retry_all_help_message)) },
                )
                // SY <--

                // KUN -->
                is ReaderViewModel.Dialog.RedoOcr -> {
                    val page = dialog.page
                    AdaptiveSheet(onDismissRequest = onDismissRequest) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp, horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = stringResource(KUNMR.strings.action_redo_ocr),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ActionButton(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(KUNMR.strings.action_redo_ocr_page),
                                    icon = MaterialSymbols.Rounded.Refresh,
                                    onClick = {
                                        onDismissRequest()
                                        toast(stringResource(KUNMR.strings.ocr_redoing_page, page.index + 1))
                                        ocrSession?.redoOcr(page, chapter = false) { result ->
                                            runOnUiThread { toast(result.toastString(page.index, chapter = false)) }
                                        }
                                    },
                                )
                                ActionButton(
                                    modifier = Modifier.weight(1f),
                                    title = stringResource(KUNMR.strings.action_redo_ocr_chapter),
                                    icon = MaterialSymbols.Rounded.CollectionsBookmark,
                                    onClick = {
                                        onDismissRequest()
                                        toast(stringResource(KUNMR.strings.ocr_redoing_chapter))
                                        ocrSession?.redoOcr(page, chapter = true) { result ->
                                            runOnUiThread { toast(result.toastString(page.index, chapter = true)) }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                // KUN <--
                null -> {}
            }

            // KUN -->
            // Dictionary lookup popup (GPL impl behind the Apache seam). Renders nothing while
            // there is no active lookup; shows its own scrim when there is one. Not even composed
            // while the combined dictionary/OCR opt-in is off.
            if (dictionaryOcrEnabled) {
                readerDictionaryHost.Popup()
            }
            // KUN <--
        }
    }
    // KUN <--

    /**
     * Called when the activity is destroyed. Cleans up the viewer and any view.
     */
    override fun onDestroy() {
        super.onDestroy()
        viewer?.destroy()
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
        // KUN -->
        // Close this reader's OCR session — on every destroy, including a configuration change, where
        // the session hands the open lookup to the session the recreated reader creates. The session
        // owns the viewport, the active block and the popup anchor (including the page screenshot
        // provider behind an open lookup), so closing it is what keeps the last page from being
        // retained after the reader exits.
        ocrSession?.close(recreating = isChangingConfigurations)
        ocrSession = null
        // KUN <--
    }

    override fun onPause() {
        viewModel.saveHistory()

        // AM (DISCORD) -->
        updateDiscordRPC(exitingReader = true)
        // <-- AM (DISCORD)

        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()

        // AM (DISCORD) -->
        updateDiscordRPC(exitingReader = false)
        // <-- AM (DISCORD)

        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    // KUN -->
    /**
     * Dispatches a touch event. Feeds the passive OCR two-finger tap detector first, then always
     * delegates to the default implementation - the detector never consumes, so no existing gesture
     * is affected.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        ocrTwoFingerTapDetector.onTouchEvent(ev)
        // KUN -->
        // Dismiss the non-modal dictionary popup on an outside tap. Convert the screen coordinates
        // into the compose-overlay space the popup bounds are reported in; the detector never
        // consumes, so the viewer's own gestures are unaffected. The Compose content root replaced
        // the old `compose_overlay` ComposeView (upstream 00335b2a70).
        composeOverlayRoot.getLocationOnScreen(composeOverlayLocation)
        dictionaryOutsideTapDetector.onTouchEvent(
            ev,
            ev.rawX - composeOverlayLocation[0],
            ev.rawY - composeOverlayLocation[1],
        )
        // KUN <--
        return super.dispatchTouchEvent(ev)
    }
    // KUN <--

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    // KUN -->
    /**
     * Hosts the current viewer (upstream 00335b2a70). The reader's grayscale and inverted colours
     * reach the SSIV page images through [LocalPageColorFilter], and the WebGPU viewer - its pages
     * and its own transition, progress and error pages alike - through the layer
     * [WebGpuViewerHost] puts over its composition. The reader's own loading screen, bars and
     * overlays are outside both, and the SSIV transitions, spinners and error views keep theirs.
     */
    @Composable
    private fun ReaderViewer() {
        val viewer by viewerFlow.collectAsState()
        val general by settingsViewModel.general.collectAsState()
        val insets = when {
            !general.fullscreen -> WindowInsets.systemBars
            !general.drawUnderCutout -> WindowInsets.displayCutout
            else -> WindowInsets(0)
        }
        val colorFilter = remember(general.grayscale, general.invertedColors) {
            viewerColorFilter(general.grayscale, general.invertedColors)
        }

        CompositionLocalProvider(LocalPageColorFilter provides colorFilter) {
            key(viewer) {
                val modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(insets)
                // A WebGPU viewer's pages are the `webgpuviewer` surface, which reads no
                // [LocalPageColorFilter], so below API 34 the reader's colours are a hardware layer
                // over the viewer instead (`WebGpuViewerHost`), which takes that viewer's own
                // transition, progress and error pages with it. From API 34 on, the surface is a
                // `SurfaceView` in a layer of its own, which no ancestor can filter - the mechanism
                // this replaced could not filter it either.
                val currentViewer = viewer
                if (currentViewer is WebGpuViewer && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // The cover seed travels with the viewer: its own chrome is composed in the
                    // host's nested composition, which starts from an unseeded `TachiyomiTheme`.
                    WebGpuViewerHost(
                        viewer = currentViewer,
                        pageColorFilter = colorFilter,
                        modifier = modifier,
                        seedColor = seedColorState().takeIf { themeCoverBased },
                    )
                } else {
                    currentViewer?.Content(modifier)
                }
            }
        }
    }

    /**
     * Hosts [viewer] in a View of the reader's own, so the grayscale and inverted colours are a
     * hardware layer over it, as master's `viewerContainer.setLayerType` was.
     *
     * On API 26-33 the `webgpuviewer` surface is an `AndroidEmbeddedExternalSurface`, a TextureView
     * drawn into the hierarchy, so a layer paint on its host filters it.
     *
     * The layer is the WebGPU-only fallback, and what it covers is that viewer's whole composition:
     * its own chapter transition, progress and error pages are pages it draws, so they are filtered
     * along with the surface, exactly as master's container layer filtered them. The reader's own
     * loading screen, app bars and overlays are siblings of this view in the reader root, not inside
     * it, so they stay unfiltered. The SSIV viewers are not hosted here at all: their pages keep
     * [LocalPageColorFilter], so their transitions, spinners and error views keep their own colours.
     *
     * [seedColor] is the reader's cover seed (R19). The nested composition starts from
     * `setComposeContent`'s unseeded `TachiyomiTheme`, so without re-providing it here the pages this
     * viewer draws itself - its error page, which reads `MaterialTheme.colorScheme.onBackground`, and
     * its transition page - would be drawn in the default palette on API 26-33 only. It is wrapped the
     * way [ReaderTransitionView] wraps its own Compose content, and kept current through
     * [rememberUpdatedState] so a seed that arrives after the host is created still reaches it.
     */
    @Composable
    private fun WebGpuViewerHost(
        viewer: WebGpuViewer,
        pageColorFilter: ColorFilter?,
        modifier: Modifier,
        seedColor: ComposeColor?,
    ) {
        val paint = remember(pageColorFilter) {
            pageColorFilter?.asAndroidColorFilter()?.let { Paint().apply { colorFilter = it } }
        }
        val currentSeed by rememberUpdatedState(seedColor)
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ComposeView(context).apply {
                    setComposeContent {
                        TachiyomiTheme(seedColor = currentSeed) {
                            viewer.Content(Modifier.fillMaxSize())
                        }
                    }
                }
            },
            update = { view ->
                view.setLayerType(
                    if (paint == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE,
                    paint,
                )
            },
            onRelease = { view ->
                view.setLayerType(View.LAYER_TYPE_NONE, null)
                view.disposeComposition()
            },
        )
    }
    // KUN <--

    @Composable
    fun AppBars(state: ReaderViewModel.State) {
        val isHttpSource = state.source is HttpSource
        val viewer by viewerFlow.collectAsState()

        val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
        // SY -->
        val readingMode = viewModel.getMangaReadingMode()
        val isPagerType = ReadingMode.isPagerType(readingMode)
        val isWebtoon = ReadingMode.WEBTOON.flagValue == readingMode
        val cropBorderContinuousVertical by readerPreferences.cropBordersContinuousVertical.collectAsState()
        val cropEnabled = if (isPagerType) {
            cropBorderPaged
        } else if (isWebtoon) {
            cropBorderWebtoon
        } else {
            cropBorderContinuousVertical
        }
        val readerBottomButtons by remember {
            readerPreferences.readerBottomButtons.changes().map { it.toSet() }
        }.collectAsState(setOf())
        val dualPageSplitPaged by readerPreferences.dualPageSplitPaged.collectAsState()
        // KUN --> the viewer is owned by the activity (upstream ca028cb657)
        val autoScrollGesturesSupported = viewer is WebtoonViewer
        // KUN <--
        val autoScrollGesturesEnabled by readerPreferences.autoscrollGesturesEnabled.collectAsState()
        val autoScrollGestureToastsEnabled by readerPreferences.autoscrollGestureToastsEnabled.collectAsState()
        // SY <--

        // KUN -->
        val ocrOverlayEnabled by readerPreferences.ocrOverlayEnabled.collectAsState()
        val dictionaryOcrEnabled by dictionaryOcrPreferences.enabled.collectAsState()
        // KUN <--

        val verticalNavigatorModes by readerPreferences.verticalNavigator.collectAsState()
        val verticalNavigator = verticalNavigatorModes.contains(
            ReadingMode.fromPreference(viewModel.getMangaReadingMode()),
        )
        val verticalNavigatorOnLeft by readerPreferences.verticalNavigatorOnLeft.collectAsState()
        val verticalNavigatorHeight by readerPreferences.verticalNavigatorHeight.collectAsState()

        ReaderAppBars(
            visible = state.menuVisible,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            bookmarked = state.bookmarked,
            onToggleBookmarked = viewModel::toggleChapterBookmark,
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },

            chapterNavigatorType = if (!verticalNavigator) {
                if (viewer is R2LPagerViewer || (viewer as? WebGpuViewer)?.isReversed ?: false) {
                    ChapterNavigatorType.HORIZONTAL_RTL
                } else {
                    ChapterNavigatorType.HORIZONTAL_LTR
                }
            } else {
                if (verticalNavigatorOnLeft) {
                    ChapterNavigatorType.VERTICAL_LEFT
                } else {
                    ChapterNavigatorType.VERTICAL_RIGHT
                }
            },
            verticalNavigatorHeight = verticalNavigatorHeight / 100f,
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },
            onPageIndexChangeFinished = {
                isScrollingThroughPages = false
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = viewModel::openReadingModeSelectDialog,
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            onClickSettings = viewModel::openSettingsDialog,
            // SY -->
            isExhToolsVisible = state.ehUtilsVisible,
            onSetExhUtilsVisibility = viewModel::showEhUtils,
            isAutoScroll = state.autoScroll,
            isAutoScrollEnabled = state.isAutoScrollEnabled,
            onToggleAutoscroll = viewModel::toggleAutoScroll,
            autoScrollGesturesSupported = autoScrollGesturesSupported,
            autoScrollGesturesEnabled = autoScrollGesturesEnabled,
            onSetAutoScrollGesturesEnabled = { readerPreferences.autoscrollGesturesEnabled.set(it) },
            onClickAutoScrollGesturesHelp = viewModel::openAutoScrollGesturesHelpDialog,
            autoScrollGestureToastsEnabled = autoScrollGestureToastsEnabled,
            onSetAutoScrollGestureToastsEnabled = { readerPreferences.autoscrollGestureToastsEnabled.set(it) },
            onClickAutoScrollGestureToastsHelp = viewModel::openAutoScrollGestureToastsHelpDialog,
            autoScrollFrequency = state.ehAutoscrollFreq,
            onSetAutoScrollFrequency = viewModel::setAutoScrollFrequency,
            onClickAutoScrollHelp = viewModel::openAutoScrollHelpDialog,
            onClickRetryAll = ::exhRetryAll,
            onClickRetryAllHelp = viewModel::openRetryAllHelp,
            onClickBoostPage = ::exhBoostPage,
            onClickBoostPageHelp = viewModel::openBoostPageHelp,
            currentPageText = state.currentPageText,
            // KUN -->
            // Hidden while the combined dictionary/OCR opt-in is off: the button is a shortcut into
            // the feature, so it must not be the only visible trace of it.
            enabledButtons = if (dictionaryOcrEnabled) {
                readerBottomButtons
            } else {
                readerBottomButtons - ReaderBottomButton.Ocr.value
            },
            // KUN <--
            currentReadingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = true),
            ),
            dualPageSplitEnabled = dualPageSplitPaged,
            doublePages = state.doublePages,
            onClickChapterList = viewModel::openChapterListDialog,
            onClickPageLayout = {
                if (ReaderPreferences.PageLayout.fromValue(readerPreferences.pageLayout.get()) ==
                    ReaderPreferences.PageLayout.AUTOMATIC
                ) {
                    // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                    (viewer as? PagerViewer)?.let { pagerViewer ->
                        pagerViewer.doublePages = !pagerViewer.doublePages
                        reloadChapters(pagerViewer.doublePages, true)
                    }
                    // KUN <--
                } else {
                    readerPreferences.pageLayout.set(1 - readerPreferences.pageLayout.get())
                }
            },
            onClickShiftPage = ::shiftDoublePages,
            // SY <--
            // KUN -->
            ocrEnabled = dictionaryOcrEnabled && ocrOverlayEnabled,
            onClickOcr = {
                // Gated too: the icon is hidden while off, but the action must never be a backdoor.
                if (dictionaryOcrEnabled) {
                    val enabled = viewModel.toggleOcrOverlay()
                    menuToggleToast?.cancel()
                    menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
                }
            },
            onLongClickOcr = {
                if (dictionaryOcrEnabled) {
                    showRedoOcrDialog()
                }
            },
            // KUN <--
        )
    }

    // KMK -->
    @Composable
    private fun seedColorState(): ComposeColor? {
        val state by viewModel.state.collectAsState()
        return state.manga?.asMangaCover()?.vibrantCoverColor?.let { ComposeColor(it) }
            ?: seedColorStatic()
    }

    private fun seedColorStatic(): ComposeColor? {
        return viewModel.manga?.asMangaCover()?.vibrantCoverColor?.let { ComposeColor(it) }
            ?: intent.extras?.getLong("manga")?.takeIf { it > 0 }
                ?.let { MangaCover.vibrantCoverColorMap[it] }
                ?.let { ComposeColor(it) }
    }
    // KMK <--

    // EXH -->
    private fun enableExhAutoScroll() {
        readerPreferences.autoscrollGesturesEnabled.changes()
            .distinctUntilChanged()
            .onEach { enabled ->
                // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                (viewer as? WebtoonViewer)?.setAutoScrollGesturesEnabled(enabled)
                // KUN <--
            }
            .launchIn(lifecycleScope)

        readerPreferences.autoscrollGestureToastsEnabled.changes()
            .distinctUntilChanged()
            .onEach { enabled ->
                // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                (viewer as? WebtoonViewer)?.setAutoScrollGestureToastsEnabled(enabled)
                // KUN <--
            }
            .launchIn(lifecycleScope)

        readerPreferences.autoscrollInterval.changes()
            .combine(viewModel.state.map { it.autoScroll }.distinctUntilChanged()) { interval, enabled ->
                interval.toDouble() to enabled
            }
            .mapLatest { (intervalFloat, enabled) ->
                val interval = intervalFloat.seconds
                // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                (viewer as? WebtoonViewer)?.syncAutoScrollSettings(
                    enabled = enabled,
                    interval = interval,
                    forceInterval = true,
                )
                // KUN <--

                if (!enabled) return@mapLatest

                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        if (!viewModel.state.value.menuVisible) {
                            // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                            viewer.let { v ->
                                // KUN <--
                                when (v) {
                                    is PagerViewer -> {
                                        v.moveToNext()
                                        delay(interval)
                                    }
                                    is WebtoonViewer -> {
                                        v.syncAutoScrollSettings(enabled = true, interval = interval)
                                        v.performAutoScrollStep(readerPreferences.smoothAutoScroll.get())
                                        delayAutoScroll(v.autoScrollDelayMillis(), v)
                                    }
                                    else -> delay(100)
                                }
                            }
                        } else {
                            delay(100)
                        }
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private suspend fun delayAutoScroll(durationMillis: Long, viewer: WebtoonViewer) {
        var remaining = durationMillis
        while (remaining > 0) {
            val slice = remaining.coerceAtMost(100L)
            delay(slice)
            remaining -= slice
            if (viewer.consumeAutoScrollDelayInterrupt()) {
                break
            }
        }
    }

    private fun exhRetryAll() {
        var retried = 0

        viewModel.state.value.viewerChapters
            ?.currChapter
            ?.pages
            ?.forEachIndexed { _, page ->
                var shouldQueuePage = false
                if (page.status is Page.State.Error) {
                    shouldQueuePage = true
                } /*else if (page.status == Page.State.LoadPage ||
                                    page.status == Page.State.DownloadImage) {
                                // Do nothing
                            }*/

                if (shouldQueuePage) {
                    page.status = Page.State.Queue
                } else {
                    return@forEachIndexed
                }

                // If we are using EHentai/ExHentai, get a new image URL
                if (viewModel.source?.isEhBasedSource() == true) {
                    page.imageUrl = null
                }

                val loader = page.chapter.pageLoader
                if (page.index == exhCurrentpage()?.index && loader is HttpPageLoader) {
                    loader.boostPage(page)
                } else {
                    loader?.retryPage(page)
                }

                retried++
            }

        toast(pluralStringResource(SYMR.plurals.eh_retry_toast, retried, retried))
    }

    private fun exhBoostPage() {
        // KUN --> the viewer is owned by the activity (upstream ca028cb657)
        viewer ?: return
        // KUN <--
        val curPage = exhCurrentpage() ?: run {
            toast(SYMR.strings.eh_boost_page_invalid)
            return
        }

        if (curPage.status is Page.State.Error) {
            toast(SYMR.strings.eh_boost_page_errored)
        } else if (curPage.status == Page.State.LoadPage || curPage.status == Page.State.DownloadImage) {
            toast(SYMR.strings.eh_boost_page_downloading)
        } else if (curPage.status == Page.State.Ready) {
            toast(SYMR.strings.eh_boost_page_downloaded)
        } else {
            val loader = (viewModel.state.value.viewerChapters?.currChapter?.pageLoader as? HttpPageLoader)
            if (loader != null) {
                loader.boostPage(curPage)
                toast(SYMR.strings.eh_boost_boosted)
            } else {
                toast(SYMR.strings.eh_boost_invalid_loader)
            }
        }
    }

    private fun exhCurrentpage(): ReaderPage? {
        // KUN -->
        // The reader pages the viewer is actually drawing: WebGPU included (its current page is a
        // `ViewerReaderPage`), with the state's page index as the fallback when a viewer has none
        // yet. The viewer is owned by the activity (upstream ca028cb657).
        val currentViewer = viewer
        val pageFromViewer = when (currentViewer) {
            is PagerViewer -> currentViewer.currentPage as? ReaderPage
            is WebtoonViewer -> currentViewer.currentPage as? ReaderPage
            is WebGpuViewer -> (currentViewer.currentPage as? WebGpuViewer.ViewerReaderPage)?.page
            else -> null
        }
        if (pageFromViewer != null) return pageFromViewer

        val currPages = viewModel.state.value.viewerChapters?.currChapter?.pages.orEmpty()
        val index = (viewModel.state.value.currentPage - 1).coerceIn(0, (currPages.size - 1).coerceAtLeast(0))
        return currPages.getOrNull(index) ?: currPages.firstOrNull()
        // KUN <--
    }

    fun reloadChapters(doublePages: Boolean, force: Boolean = false) {
        // KUN --> the viewer is owned by the activity (upstream ca028cb657)
        val viewer = this.viewer as? PagerViewer ?: return
        // KUN <--
        viewer.updateShifting()
        if (!force && viewer.autoDoublePages) {
            setDoublePageMode(viewer)
        } else {
            viewer.doublePages = doublePages
            viewModel.setDoublePages(viewer.doublePages)
        }
        val currentChapter = viewModel.state.value.currentChapter
        if (doublePages) {
            // If we're moving from singe to double, we want the current page to be the first page
            val currentPage = viewModel.state.value.currentPage
            viewer.shiftDoublePage = (
                currentPage + (currentChapter?.pages?.take(currentPage)?.count { it.fullPage || it.isolatedPage } ?: 0)
                ) % 2 != 0
        }
        viewModel.state.value.viewerChapters?.let {
            viewer.setChaptersInternal(it)
        }
    }

    private fun setDoublePageMode(viewer: PagerViewer) {
        val currentOrientation = resources.configuration.orientation
        viewer.doublePages = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        viewModel.setDoublePages(viewer.doublePages)
    }

    private fun shiftDoublePages() {
        // KUN --> the viewer is owned by the activity (upstream ca028cb657)
        val viewer = this.viewer as? PagerViewer ?: return
        // KUN <--
        viewer.shiftDoublePage = !viewer.shiftDoublePage
        viewModel.state.value.viewerChapters?.let {
            viewer.updateShifting()
            viewer.setChaptersInternal(it)
            invalidateOptionsMenu()
        }
    }
    // EXH <--

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        // KUN --> the viewer is owned by the activity (upstream ca028cb657)
        (viewer as? WebtoonViewer)?.setAutoScrollUiBlocked(visible)
        // KUN <--
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewer
        // KUN --> the reading mode the viewer is made from is also the one the tap zones resolve
        // (upstream 00335b2a70), so `settingsViewModel.navigation` matches this viewer.
        val readingMode = viewModel.getMangaReadingMode()
        val newViewer = ReadingMode.toViewer(
            readingMode,
            this,
            // KMK -->
            seedColor = seedColorStatic()?.toArgb(),
            // KMK <--
        )
        // KUN -->
        // Passed rather than read from the preference: `toViewer`'s capability gate may have fallen
        // back from the WebGPU renderer, so only the viewer it actually made says which tap zones
        // apply and which settings page the sheet shows.
        settingsViewModel.setViewerReadingMode(
            ReadingMode.fromPreference(readingMode),
            isWebtoonViewer = newViewer is WebtoonViewer,
            isWebGpuViewer = newViewer is WebGpuViewer,
        )
        // KUN <--

        setOrientation(viewModel.getMangaOrientation())
        // KUN <--

        // Destroy previous viewer if there was one
        prevViewer?.destroy()
        viewerFlow.value = newViewer
        (newViewer as? WebtoonViewer)?.setAutoScrollUiBlocked(viewModel.state.value.menuVisible)
        (newViewer as? WebtoonViewer)?.setAutoScrollGesturesEnabled(readerPreferences.autoscrollGesturesEnabled.get())
        (newViewer as? WebtoonViewer)?.setAutoScrollGestureToastsEnabled(readerPreferences.autoscrollGestureToastsEnabled.get())

        // SY -->
        if (newViewer is PagerViewer) {
            if (ReaderPreferences.PageLayout.fromValue(readerPreferences.pageLayout.get()) ==
                ReaderPreferences.PageLayout.AUTOMATIC
            ) {
                setDoublePageMode(newViewer)
            }
            viewModel.state.value.lastShiftDoubleState?.let { newViewer.shiftDoublePage = it }
        }

        val manga = viewModel.state.value.manga
        val defaultReaderType = manga?.defaultReaderType(
            manga.mangaType(sourceName = viewModel.source?.name),
        )
        if (
            readerPreferences.useAutoWebtoon.get() &&
            (manga?.readingMode?.toInt() ?: ReadingMode.DEFAULT.flagValue) == ReadingMode.DEFAULT.flagValue &&
            defaultReaderType != null &&
            defaultReaderType == ReadingMode.WEBTOON.flagValue
        ) {
            readingModeToast?.cancel()
            readingModeToast = toast(SYMR.strings.eh_auto_webtoon_snack)
        } else if (readerPreferences.showReadingMode.get()) {
            // SY <--
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        // KUN -->
        // R20: the loading chrome shows until the new viewer has chapters. `setChapters` is the only
        // place the flag is cleared and it is driven by the chapters stream, so the chapters the
        // state already holds are handed to the new viewer right here: a manga emission that rebuilds
        // the viewer without re-emitting chapters would otherwise leave the reader spinning behind
        // the loading screen for ever, with no bars and no page indicator.
        isLoadingViewer = true
        viewModel.state.value.viewerChapters?.let(::setChapters)
        // KUN <--
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    private fun setChapters(viewerChapters: ViewerChapters) {
        isLoadingViewer = false
        // SY -->
        val state = viewModel.state.value
        if (state.indexChapterToShift != null && state.indexPageToShift != null) {
            viewerChapters.currChapter.pages?.find {
                it.index == state.indexPageToShift && it.chapter.chapter.id == state.indexChapterToShift
            }?.let {
                // KUN --> the viewer is owned by the activity (upstream ca028cb657)
                (viewer as? PagerViewer)?.updateShifting(it)
                // KUN <--
            }
            viewModel.setIndexChapterToShift(null)
            viewModel.setIndexPageToShift(null)
        } else if (state.lastShiftDoubleState != null) {
            val currentChapter = viewerChapters.currChapter
            // KUN --> the viewer is owned by the activity (upstream ca028cb657)
            (viewer as? PagerViewer)?.shiftDoublePage = (
                currentChapter.requestedPage +
                    (
                        currentChapter.pages?.take(currentChapter.requestedPage)
                            ?.count { it.fullPage || it.isolatedPage } ?: 0
                        )
                ) % 2 != 0
            // KUN <--
        }
        // SY <--

        viewer?.setChapters(viewerChapters)

        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }

        // AM (DISCORD) -->
        updateDiscordRPC(exitingReader = false)
        // <-- AM (DISCORD)
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = this.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage, hasExtraPage: Boolean = false) {
        // SY -->
        val currentPageText = if (hasExtraPage) {
            // KUN --> the viewer is owned by the activity (upstream ca028cb657)
            val invertDoublePage = (viewer as? PagerViewer)?.invertDoublePages ?: false
            // KUN <--
            if ((resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) xor
                invertDoublePage
            ) {
                "${page.number}-${page.number + 1}"
            } else {
                "${page.number + 1}-${page.number}"
            }
        } else {
            "${page.number}"
        }
        // SY <--
        viewModel.onPageSelected(page, /* SY --> */ currentPageText, hasExtraPage /* SY <-- */)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(
        page: ReaderPage,
        /* SY --> */
        extraPage: ReaderPage? = null, /* SY <-- */
        // KUN -->
        point: PointF? = null,
        // KUN <--
    ) {
        // KUN -->
        // Single OCR entry point (contract §2.4): when the feature is enabled and the overlay is on
        // and the press landed on an OCR block, the host starts a dictionary lookup and consumes the
        // gesture; otherwise fall through to the upstream page-actions dialog.
        if (point != null &&
            ReaderOcrOverlayPolicy.acceptsLookup(
                masterEnabled = dictionaryOcrPreferences.isEnabled(),
                overlayEnabled = readerPreferences.ocrOverlayEnabled.get(),
                menuVisible = viewModel.state.value.menuVisible,
            ) &&
            ocrSession?.onPageLongPress(page, extraPage, Offset(point.x, point.y)) == true
        ) {
            return
        }
        // KUN <--
        viewModel.openPageDialog(page, /* SY --> */ extraPage /* SY <-- */)
    }

    // KUN -->
    /**
     * Single-tap OCR entry point, called by every viewer *before* its normal single-tap navigation
     * (`PagerViewer`/`WebtoonViewer` `tapListener`, `WebGpuViewer` `state.onTap`).
     *
     * While the OCR overlay is **painted** (feature on, overlay on, reader chrome hidden) and the
     * tap lands on an OCR block, the host starts a dictionary lookup (the same hit-test + lookup path
     * as [onPageLongTap]) and returns `true`, so the viewer consumes the tap and does not also
     * page-turn or toggle the menu. A miss, the overlay being off, or the chrome being up (the
     * overlay paints nothing then) returns `false` and the normal navigation runs unchanged.
     *
     * Tying this to the existing overlay flag (no extra preference) keeps it to "when the overlay is
     * painted, one tap looks a word up"; the two-finger toggle therefore turns it on and off with the
     * boxes.
     */
    fun onPageSingleTap(page: ReaderPage, extraPage: ReaderPage?, point: PointF): Boolean {
        // KUN -->
        // The hit test runs only while the overlay is visible: a hidden overlay must never consume a
        // tap, or the page turn / menu toggle it swallowed is simply lost. Same predicate as the
        // long press and the outside-tap detector (`ReaderOcrOverlayPolicy.acceptsLookup`).
        if (!ReaderOcrOverlayPolicy.acceptsLookup(
                masterEnabled = dictionaryOcrPreferences.isEnabled(),
                overlayEnabled = readerPreferences.ocrOverlayEnabled.get(),
                menuVisible = viewModel.state.value.menuVisible,
            )
        ) {
            return false
        }
        // KUN <--
        return ocrSession?.onPageSingleTap(page, extraPage, Offset(point.x, point.y)) == true
    }
    // KUN <--

    // KUN -->
    /**
     * Converts a point in [source]'s local coordinates to the reader overlay's coordinate space
     * (both are window-relative, so only the views' window positions differ).
     */
    fun toOverlayPoint(source: View, x: Float, y: Float): PointF {
        val location = IntArray(2)
        source.getLocationInWindow(location)
        return toOverlayPoint(location[0].toFloat(), location[1].toFloat(), x, y)
    }

    /**
     * [toOverlayPoint] for a surface that is not a View: [viewWindowX]/[viewWindowY] are its origin
     * in window space, published by its own owner (the WebGPU viewer's `ImageViewer` modifier). The
     * rest of the conversion is the same - the overlay's origin is the Compose content root.
     */
    fun toOverlayPoint(viewWindowX: Float, viewWindowY: Float, x: Float, y: Float): PointF {
        val overlayLocation = IntArray(2)
        composeOverlayRoot.getLocationInWindow(overlayLocation)
        return PointF(
            (viewWindowX - overlayLocation[0]) + x,
            (viewWindowY - overlayLocation[1]) + y,
        )
    }

    /**
     * Two-finger tap: toggles the reader OCR overlay (Settings "OCR two-finger gesture"). Mirrors the
     * reader bottom-bar OCR button, including its on/off toast. Reached only through
     * [ocrTwoFingerTapDetector], which is gated on the setting.
     */
    private fun onOcrTwoFingerTap() {
        // Gated by the combined opt-in too: the detector is already disabled while off, and the
        // toggle must not flip the overlay preference behind the master switch.
        if (!dictionaryOcrPreferences.isEnabled()) return
        val enabled = viewModel.toggleOcrOverlay()
        menuToggleToast?.cancel()
        menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
    }

    /**
     * Shows a dialog to force-redo OCR for the current page or the entire chapter,
     * updating the cache and overriding any .mokuro sidecars in the archive.
     */
    private fun showRedoOcrDialog() {
        if (!dictionaryOcrPreferences.isEnabled()) return
        val currentPage = exhCurrentpage()
        if (currentPage != null) {
            viewModel.openRedoOcrDialog(currentPage)
        } else {
            toast(KUNMR.strings.action_redo_ocr)
        }
    }

    /**
     * The toast for a finished OCR re-run. Only a run that re-OCRed at least one page reports the
     * success message; a run in which every page failed (models missing, engine load failure, no
     * page bytes) and a run with nothing to do report themselves, so "OCR updated" is never shown
     * for a re-run that changed nothing.
     */
    private fun RedoOcrResult.toastString(pageIndex: Int, chapter: Boolean): String = when (this) {
        RedoOcrResult.UPDATED ->
            if (chapter) {
                stringResource(KUNMR.strings.ocr_redo_chapter_complete)
            } else {
                stringResource(KUNMR.strings.ocr_redo_page_complete, pageIndex + 1)
            }
        RedoOcrResult.FAILED -> stringResource(KUNMR.strings.ocr_redo_failed)
        RedoOcrResult.NOTHING_TO_REDO -> stringResource(KUNMR.strings.ocr_no_page_to_redo)
    }
    // KUN <--

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launch(Dispatchers.IO) { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    fun onShareImageResult(uri: Uri, page: ReaderPage /* SY --> */, secondPage: ReaderPage? = null /* SY <-- */) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = // SY -->
            if (secondPage != null) {
                stringResource(
                    SYMR.strings.share_pages_info,
                    manga.title,
                    chapter.name,
                    if (resources.configuration.layoutDirection ==
                        View.LAYOUT_DIRECTION_LTR
                    ) {
                        "${page.number}-${page.number + 1}"
                    } else {
                        "${page.number + 1}-${page.number}"
                    },
                )
            } else {
                // SY <--
                stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number)
            },
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    // KMK -->
    /**
     * Sets the display profile to [path].
     */
    private fun setDisplayProfile(path: String) {
        val file = UniFile.fromUri(baseContext, path.toUri())
        if (file != null && file.exists()) {
            val inputStream = file.openInputStream()
            val outputStream = ByteArrayOutputStream()
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            val data = outputStream.toByteArray()
            SubsamplingScaleImageView.setDisplayProfile(data)
        }
    }
    // KMK <--

    /**
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    private fun setCustomBrightnessValue(value: Int) {
        // Calculate and set reader brightness.
        val readerBrightness = when {
            value > 0 -> {
                value / 100f
            }
            value < 0 -> {
                0.01f
            }
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

        viewModel.setBrightnessOverlayValue(value)
    }

    // KMK -->
    /**
     * Updates the Discord Rich Presence (RPC) status based on the current reader activity.
     *
     * @param exitingReader A boolean flag indicating whether the user is exiting the reader.
     * If true, the Discord RPC status is set to the last used screen.
     * If false, the Discord RPC status is set to the current reader activity, displaying details such as the manga title, chapter number, and chapter title.
     */
    private fun updateDiscordRPC(exitingReader: Boolean) {
        if (!connectionsPreferences.enableDiscordRPC.get()) return

        DiscordRPCService.discordScope.launchIO {
            try {
                if (!exitingReader) {
                    val manga = viewModel.manga ?: return@launchIO
                    val chapter = viewModel.currentChapter ?: return@launchIO

                    DiscordRPCService.setReaderActivity(
                        context = this@ReaderActivity,
                        ReaderData(
                            incognitoMode = viewModel.incognitoMode,
                            mangaId = manga.id,
                            mangaTitle = manga.ogTitle,
                            thumbnailUrl = manga.thumbnailUrl.takeIf { UrlUtils.isOnlineUrl(it) } ?: manga.ogThumbnailUrl,
                            chapterNumber = if (connectionsPreferences.useChapterTitles.get()) {
                                chapter.name
                            } else {
                                chapter.chapterNumber.toString()
                            },
                            startTimestamp = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    with(DiscordRPCService) {
                        setScreen(this@ReaderActivity)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error updating Discord RPC: ${e.message}" }
            }
        }
    }
    // KMK <--
}

// KUN -->
/**
 * The reader's grayscale and inverted colours, for page images only: the SSIV transitions, spinners
 * and error views keep theirs. Null when both are off (upstream 00335b2a70).
 */
private fun viewerColorFilter(grayscale: Boolean, invertedColors: Boolean): ColorFilter? {
    if (!grayscale && !invertedColors) return null
    val matrix = ColorMatrix()
    if (invertedColors) {
        matrix *= ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
    if (grayscale) {
        matrix *= ColorMatrix().apply { setToSaturation(0f) }
    }
    return ColorFilter.colorMatrix(matrix)
}
// KUN <--
