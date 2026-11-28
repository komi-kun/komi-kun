package eu.kanade.tachiyomi.ui.manga

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.materialkolor.ktx.blend
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.manga.ChapterSettingsDialog
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.presentation.manga.MangaScreen
import eu.kanade.presentation.manga.components.ClearMangaDialog
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.MangaCoverDialog
import eu.kanade.presentation.manga.components.ScanlatorFilterDialog
import eu.kanade.presentation.manga.components.SetIntervalDialog
import eu.kanade.presentation.more.settings.screen.SettingsEhScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.openSettings
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.getOrStubOrNull
import eu.kanade.tachiyomi.source.isLocalOrStub
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSearchEvent
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.home.TabEvent
import eu.kanade.tachiyomi.ui.manga.merged.EditMergedSettingsDialog
import eu.kanade.tachiyomi.ui.manga.merged.EditMergedSettingsViewModel
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import exh.pagepreview.PagePreviewScreen
import exh.recs.RecommendsArgs
import exh.recs.RecommendsRoute
import exh.recs.RecommendsScreen
import exh.source.MERGED_SOURCE_ID
import exh.source.anyIs
import exh.source.getMainSource
import exh.source.isEhBasedSource
import exh.ui.metadata.MetadataViewScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.core.navigation.BrowseSourceRoute
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.ExtensionsRoute
import mihon.core.navigation.GlobalSearchRoute
import mihon.core.navigation.HomeRoute
import mihon.core.navigation.LocalAssistUrl
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaNotesRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.MetadataViewRoute
import mihon.core.navigation.MigrationConfigRoute
import mihon.core.navigation.Navigator
import mihon.core.navigation.PagePreviewRoute
import mihon.core.navigation.PaletteRoute
import mihon.core.navigation.SmartSearchRoute
import mihon.core.navigation.SmartSearchSourceRoute
import mihon.core.navigation.SourceFeedRoute
import mihon.core.navigation.SourcePreferencesRoute
import mihon.core.navigation.TrackInfoDialogRoute
import mihon.core.navigation.WebViewRoute
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.isTabletUi
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun MangaScreen(
    mangaId: Long,
    fromSource: Boolean,
    // KUN -->
    // Smart-search state, flattened onto the route: the entry a merge is being searched for.
    smartSearchTitle: String? = null,
    smartSearchMangaId: Long? = null,
    // KUN <--
) {
    val smartSearchConfig = smartSearchTitle?.let { SmartSearchConfig(it, smartSearchMangaId) }
    val navigator = LocalNavigator.current
    val resultEventBus = LocalResultEventBus.current
    val setAssistUrl = LocalAssistUrl.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val isTabletUi = isTabletUi()
    val viewModel =
        assistedMetroViewModel<MangaViewModel, MangaViewModel.Factory> {
            create(
                mangaId = mangaId,
                isFromSource = fromSource,
                // SY -->
                smartSearched = smartSearchConfig != null,
                // SY <--
            )
        }

    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state is MangaViewModel.State.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as MangaViewModel.State.Success

    // KMK -->
    val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()

    val showingRelatedMangasScreen = rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = bulkFavoriteState.selectionMode || showingRelatedMangasScreen.value) {
        when {
            bulkFavoriteState.selectionMode -> bulkFavoriteViewModel.backHandler()
            showingRelatedMangasScreen.value -> showingRelatedMangasScreen.value = false
        }
    }

    val content = @Composable {
        Crossfade(
            targetState = showingRelatedMangasScreen.value,
            label = "manga_related_crossfade",
        ) { showRelatedMangasScreen ->
            when (showRelatedMangasScreen) {
                true -> RelatedMangasScreen(
                    viewModel = viewModel,
                    successState = successState,
                    bulkFavoriteViewModel = bulkFavoriteViewModel,
                    navigateUp = { showingRelatedMangasScreen.value = false },
                    navigator = navigator,
                    scope = scope,
                )
                false -> MangaDetailContent(
                    context = context,
                    viewModel = viewModel,
                    successState = successState,
                    bulkFavoriteViewModel = bulkFavoriteViewModel,
                    showRelatedMangasScreen = { showingRelatedMangasScreen.value = true },
                    navigator = navigator,
                    scope = scope,
                    setAssistUrl = setAssistUrl,
                    resultEventBus = resultEventBus,
                    smartSearchConfig = smartSearchConfig,
                )
            }
        }
    }

    val seedColor = successState.seedColor
    TachiyomiTheme(
        seedColor = seedColor.takeIf { viewModel.themeCoverBased },
    ) {
        content()
    }

    BulkFavoriteDialogs(
        bulkFavoriteViewModel = bulkFavoriteViewModel,
        dialog = bulkFavoriteState.dialog,
    )
}

@Composable
fun MangaDetailContent(
    context: Context,
    viewModel: MangaViewModel,
    successState: MangaViewModel.State.Success,
    bulkFavoriteViewModel: BulkFavoriteViewModel,
    showRelatedMangasScreen: () -> Unit,
    navigator: Navigator,
    scope: CoroutineScope,
    setAssistUrl: (String?) -> Unit,
    resultEventBus: ResultEventBus,
    smartSearchConfig: SmartSearchConfig?,
) {
    // KMK <--
    val haptic = LocalHapticFeedback.current
    val isHttpSource = remember { successState.source is HttpSource }
    // KMK -->
    val tabletUi = isTabletUi()
    // KMK <--

    LaunchedEffect(successState.manga, viewModel.source) {
        if (isHttpSource) {
            try {
                withContext(Dispatchers.IO) {
                    setAssistUrl(getMangaUrl(viewModel.manga, viewModel.source))
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to get manga URL" }
            }
        }
    }

    // KUN -->
    // The track dialog is a route now: the view model only records the request.
    LaunchedEffect(viewModel.openTrackDialogRequested) {
        if (viewModel.openTrackDialogRequested) {
            viewModel.consumeTrackDialogRequest()
            navigator.push(
                TrackInfoDialogRoute(
                    mangaId = successState.manga.id,
                    mangaTitle = successState.manga.title,
                    sourceId = successState.source.id,
                ),
            )
        }
    }
    // KUN <--

    // SY -->
    LaunchedEffect(Unit) {
        viewModel.redirectFlow
            .take(1)
            .onEach {
                navigator.replace(
                    MangaRoute(it.mangaId),
                )
            }
            .launchIn(this)
    }
    // SY <--

    // KMK -->
    val coverRatio = remember { mutableFloatStateOf(1f) }
    val hazeState = remember { HazeState() }
    val fullCoverBackground = MaterialTheme.colorScheme.surfaceTint.blend(MaterialTheme.colorScheme.surface)

    val isHentaiEnabled: Boolean = LocalContext.current.appGraph.exhPreferences.isHentaiEnabled.get()
    val isConfigurableSource = successState.source.anyIs<ConfigurableSource>() ||
        (successState.source.isEhBasedSource() && isHentaiEnabled)
    // KMK <--

    MangaScreen(
        state = successState,
        snackbarHostState = viewModel.snackbarHostState,
        nextUpdate = successState.manga.expectedNextUpdate,
        isTabletUi = isTabletUi(),
        chapterSwipeStartAction = viewModel.chapterSwipeStartAction,
        chapterSwipeEndAction = viewModel.chapterSwipeEndAction,
        navigateUp = navigator::pop,
        onChapterClicked = { openChapter(context, it) },
        onDownloadChapter = viewModel::runChapterDownloadActions.takeIf { !successState.source.isLocalOrStub() },
        downloadProgressProvider = viewModel::getDownloadProgress,
        onAddToLibraryClicked = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (successState.manga.favorite) {
                viewModel.showRemoveFromLibraryDialog()
            } else {
                viewModel.toggleFavorite()
            }
        },
        // SY -->
        onWebViewClicked = {
            if (successState.mergedData == null) {
                openMangaInWebView(
                    navigator,
                    viewModel.manga,
                    viewModel.source,
                )
            } else {
                mergedMangaAction(
                    context,
                    navigator,
                    successState.mergedData,
                    // KMK -->
                    action = { _, nav, manga, source -> openMangaInWebView(nav, manga, source) },
                    titleRes = MR.strings.action_open_in_web_view,
                    // KMK <--
                )
            }
        }.takeIf { isHttpSource },
        // SY <--
        onWebViewLongClicked = {
            // KMK -->
            if (successState.mergedData == null) {
                // KMK <--
                copyMangaUrl(
                    context,
                    viewModel.manga,
                    viewModel.source,
                )
                // KMK -->
            } else {
                mergedMangaAction(
                    context,
                    navigator,
                    successState.mergedData,
                    action = { ctx, _, manga, source -> copyMangaUrl(ctx, manga, source) },
                    titleRes = MR.strings.action_copy_link,
                )
                // KMK <--
            }
        }.takeIf { isHttpSource },
        onTrackingClicked = {
            if (!successState.hasLoggedInTrackers) {
                navigator.openSettings(SettingsTrackingScreen, tabletUi)
            } else {
                navigator.push(
                    TrackInfoDialogRoute(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                )
            }
        },
        onTagSearch = { performGenreSearch(navigator, resultEventBus, it, viewModel.source!!) },
        onFilterButtonClicked = viewModel::showSettingsDialog,
        onRefresh = viewModel::fetchAllFromSource,
        onContinueReading = { continueReading(context, viewModel.getNextUnreadChapter()) },
        onSearch = { query, global -> performSearch(navigator, resultEventBus, query, global) },
        // KMK -->
        // Search the library instead of the source: the same request `performSearch` sends when the
        // previous route is Home.
        librarySearch = { query -> resultEventBus.sendResult<TabEvent>(TabEvent.Library(query = query)) },
        // KMK <--
        onCoverClicked = viewModel::showCoverDialog,
        onShareClicked = {
            // KMK -->
            if (successState.mergedData == null) {
                // KMK <--
                shareManga(context, viewModel.manga, viewModel.source)
                // KMK -->
            } else {
                mergedMangaAction(
                    context,
                    navigator,
                    successState.mergedData,
                    action = { ctx, _, manga, source -> shareManga(ctx, manga, source) },
                    titleRes = MR.strings.action_share,
                )
                // KMK <--
            }
        }.takeIf { isHttpSource },
        onDownloadActionClicked = viewModel::runDownloadAction.takeIf { !successState.source.isLocalOrStub() },
        onEditCategoryClicked = viewModel::showChangeCategoryDialog.takeIf { successState.manga.favorite },
        onEditFetchIntervalClicked = viewModel::showSetFetchIntervalDialog.takeIf {
            successState.manga.favorite
        },
        onMigrateClicked = {
            navigator.push(MigrationConfigRoute(successState.manga.id))
        }.takeIf {
            // SY -->
            successState.manga.favorite && successState.manga.source != MERGED_SOURCE_ID
            // SY <--
        },
        // SY -->
        previewsRowCount = successState.previewsRowCount,
        onMetadataViewerClicked = {
            openMetadataViewer(
                navigator,
                successState.manga,
                // KMK -->
                successState.seedColor,
                // KMK <--
            )
        },
        onEditInfoClicked = viewModel::showEditMangaInfoDialog,
        onRecommendClicked = {
            openRecommends(navigator, viewModel.source?.getMainSource(), successState.manga)
        },
        onMergedSettingsClicked = viewModel::showEditMergedSettingsDialog,
        onMergeClicked = { openSmartSearch(navigator, successState.manga) },
        onMergeWithAnotherClicked = {
            mergeWithAnother(
                navigator,
                context,
                successState.manga,
                viewModel::smartSearchMerge,
                smartSearchConfig,
            )
        },
        onOpenPagePreview = { page ->
            openPagePreview(context, successState.chapters.minByOrNull { it.chapter.sourceOrder }?.chapter, page)
        },
        onMorePreviewsClicked = { openMorePagePreviews(navigator, successState.manga) },
        // SY <--
        onEditNotesClicked = { navigator.push(MangaNotesRoute(mangaId = successState.manga.id)) },
        onMultiBookmarkClicked = viewModel::bookmarkChapters,
        onMultiMarkAsReadClicked = viewModel::markChaptersRead,
        onMarkPreviousAsReadClicked = viewModel::markPreviousChapterRead,
        onMultiDeleteClicked = viewModel::showDeleteChapterDialog,
        onChapterSwipe = viewModel::chapterSwipe,
        onChapterSelected = viewModel::toggleSelection,
        onAllChapterSelected = viewModel::toggleAllSelection,
        onInvertSelection = viewModel::invertSelection,
        // KMK -->
        getMangaState = { viewModel.getManga(initialManga = it) },
        onClickSourceSettingsClicked = {
            when {
                successState.source.isEhBasedSource() && isHentaiEnabled ->
                    navigator.push(SettingsEhScreen)
                successState.source.anyIs<ConfigurableSource>() ->
                    navigator.push(SourcePreferencesRoute(successState.source.id))
                else -> {}
            }
        }.takeIf { isConfigurableSource },
        onClearManga = { viewModel.showClearMangaDialog() },
        onOpenMangaFolder = {
            if (successState.mergedData == null) {
                viewModel.openMangaFolder(viewModel.source, viewModel.manga)
            } else {
                mergedMangaAction(
                    context,
                    navigator,
                    successState.mergedData,
                    action = { _, _, manga, source -> viewModel.openMangaFolder(source, manga) },
                    titleRes = KMR.strings.action_open_folder,
                )
            }
        }
            .takeIf { successState.source !is StubSource },
        onRelatedMangasScreenClick = {
            if (successState.isRelatedMangasFetched == null) {
                scope.launchIO { viewModel.fetchRelatedMangasFromSource(onDemand = true) }
            }
            showRelatedMangasScreen()
        },
        onRelatedMangaClick = { navigator.push(MangaRoute(it.id, true)) },
        onRelatedMangaLongClick = { bulkFavoriteViewModel.addRemoveManga(it, haptic) },
        onSourceClick = {
            if (successState.source !is StubSource) {
                // KMK -->
                if (successState.mergedData == null) {
                    viewModel.source?.let {
                        browseSource(navigator, it, viewModel.useNewSourceNavigation, smartSearchConfig)
                    }
                } else {
                    mergedMangaAction(
                        context,
                        navigator,
                        successState.mergedData,
                        action = { _, nav, _, source ->
                            source?.let {
                                browseSource(nav, it, viewModel.useNewSourceNavigation, smartSearchConfig)
                            }
                        },
                        titleRes = MR.strings.browse,
                    )
                }
                // KMK <--
            } else {
                navigator.push(ExtensionsRoute(searchSource = successState.source.name))
            }
        },
        onCoverLoaded = {
            if (viewModel.themeCoverBased || successState.manga.favorite) viewModel.setPaletteColor(it)
        },
        coverRatio = coverRatio,
        onPaletteScreenClick = { navigator.push(PaletteRoute(successState.seedColor?.toArgb())) },
        hazeState = hazeState,
        // KMK <--
    )

    var showScanlatorsDialog by remember { mutableStateOf(false) }

    val onDismissRequest = {
        viewModel.dismissDialog()
        // KMK -->
        if (viewModel.autoOpenTrack && viewModel.showTrackDialogAfterCategorySelection) {
            viewModel.showTrackDialogAfterCategorySelection = false
            if (successState.manga.favorite) {
                navigator.push(
                    TrackInfoDialogRoute(
                        mangaId = successState.manga.id,
                        mangaTitle = successState.manga.title,
                        sourceId = successState.source.id,
                    ),
                )
            }
        }
        // KMK <--
    }
    when (val dialog = successState.dialog) {
        null -> {}
        MangaViewModel.Dialog.RemoveFromLibrary -> {
            RemoveMangaDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = {
                    if (successState.manga.favorite) viewModel.toggleFavorite()
                },
                mangaToRemove = successState.manga,
            )
        }
        is MangaViewModel.Dialog.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = { navigator.push(CategoryRoute) },
                onConfirm = { include, _ ->
                    viewModel.moveMangaToCategoriesAndAddToLibrary(dialog.manga, include)
                },
            )
        }
        is MangaViewModel.Dialog.DeleteChapters -> {
            DeleteChaptersDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = {
                    viewModel.toggleAllSelection(false)
                    viewModel.deleteChapters(dialog.chapters)
                },
            )
        }

        is MangaViewModel.Dialog.DuplicateManga -> {
            DuplicateMangaDialog(
                duplicates = dialog.duplicates,
                onDismissRequest = onDismissRequest,
                onConfirm = { viewModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                onOpenManga = { navigator.push(MangaRoute(it.id)) },
                onMigrate = { viewModel.showMigrateDialog(it) },
                // KMK -->
                targetManga = dialog.manga,
                // KMK <--
            )
        }

        is MangaViewModel.Dialog.Migrate -> {
            MigrateMangaDialog(
                current = dialog.current,
                target = dialog.target,
                // Initiated from the context of [dialog.target] so we show [dialog.current].
                onClickTitle = { navigator.push(MangaRoute(dialog.current.id)) },
                onDismissRequest = onDismissRequest,
            )
        }
        MangaViewModel.Dialog.SettingsSheet -> ChapterSettingsDialog(
            onDismissRequest = onDismissRequest,
            manga = successState.manga,
            onDownloadFilterChanged = viewModel::setDownloadedFilter,
            onUnreadFilterChanged = viewModel::setUnreadFilter,
            onBookmarkedFilterChanged = viewModel::setBookmarkedFilter,
            onSortModeChanged = viewModel::setSorting,
            onDisplayModeChanged = viewModel::setDisplayMode,
            onSetAsDefault = viewModel::setCurrentSettingsAsDefault,
            onResetToDefault = viewModel::resetToDefaultSettings,
            scanlatorFilterActive = successState.scanlatorFilterActive,
            onScanlatorFilterClicked = { showScanlatorsDialog = true },
        )
        MangaViewModel.Dialog.FullCover -> {
            val vm =
                assistedMetroViewModel<MangaCoverViewModel, MangaCoverViewModel.Factory> {
                    create(mangaId = successState.manga.id)
                }
            val manga by vm.state.collectAsStateWithLifecycle()
            if (manga != null) {
                val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                    if (it == null) return@rememberLauncherForActivityResult
                    vm.editCover(context, it)
                }
                // KMK -->
                val externalStoragePermissionNotGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_DENIED
                val saveCoverPermissionRequester = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = {
                        vm.saveCover(context)
                    },
                )
                // KMK <--
                MangaCoverDialog(
                    manga = manga!!,
                    snackbarHostState = vm.snackbarHostState,
                    isCustomCover = remember(manga) { manga!!.hasCustomCover() },
                    onShareClick = { vm.shareCover(context) },
                    onSaveClick = {
                        // KMK -->
                        if (externalStoragePermissionNotGranted) {
                            saveCoverPermissionRequester.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            // KMK <--
                            vm.saveCover(context)
                        }
                    },
                    onEditClick = {
                        when (it) {
                            EditCoverAction.EDIT -> getContent.launch("image/*")
                            EditCoverAction.DELETE -> vm.deleteCustomCover(context)
                            EditCoverAction.ADD_TO_LIBRARY -> vm.addToLibraryWarning(context)
                        }
                    },
                    onDismissRequest = onDismissRequest,
                    // KMK -->
                    modifier = Modifier
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = Color.Transparent,
                                tint = HazeDefaults.tint(fullCoverBackground),
                                blurRadius = 10.dp,
                            ),
                        ),
                    // KMK <--
                )
            } else {
                LoadingScreen(Modifier.systemBarsPadding())
            }
        }
        is MangaViewModel.Dialog.SetFetchInterval -> {
            SetIntervalDialog(
                interval = dialog.manga.fetchInterval,
                nextUpdate = dialog.manga.expectedNextUpdate,
                onDismissRequest = onDismissRequest,
                onValueChanged = { interval: Int -> viewModel.setFetchInterval(dialog.manga, interval) }
                    .takeIf { viewModel.isUpdateIntervalEnabled },
            )
        }
        // SY -->
        is MangaViewModel.Dialog.EditMangaInfo -> {
            val coverVm =
                assistedMetroViewModel<MangaCoverViewModel, MangaCoverViewModel.Factory> {
                    create(mangaId = successState.manga.id)
                }
            val coverManga by coverVm.state.collectAsStateWithLifecycle()
            val getContent = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
                if (it == null) return@rememberLauncherForActivityResult
                coverVm.editCover(context, it)
            }
            EditMangaDialog(
                manga = dialog.manga,
                coverManga = coverManga,
                // KMK -->
                coverRatio = coverRatio,
                // KMK <--
                sourceName = successState.source.getNameForMangaInfo(),
                mergedManga = successState.mergedData?.manga?.values?.toList()?.ifEmpty { null },
                onDismissRequest = viewModel::dismissDialog,
                onPositiveClick = viewModel::updateMangaInfo,
                onCoverClick = { getContent.launch("image/*") },
                onResetCoverClick = { coverVm.deleteCustomCover(context) },
            )
        }
        MangaViewModel.Dialog.EditMergedSettings -> {
            val mergedVM =
                assistedMetroViewModel<EditMergedSettingsViewModel, EditMergedSettingsViewModel.Factory> {
                    create(mangaId = successState.manga.id)
                }
            EditMergedSettingsDialog(
                viewModel = mergedVM,
                onMangaClick = { navigator.push(MangaRoute(it.mangaId ?: return@EditMergedSettingsDialog)) },
                onDismissRequest = viewModel::dismissDialog,
                // KUN -->
                // Opening an entry from the settings sheet is upstream's `onMangaClick` now;
                // Komikku had the same action through the removed `onOpenEntryClick`.
                // KUN <--
            )
        }
        // SY <--
        // KMK -->
        is MangaViewModel.Dialog.ClearManga -> {
            ClearMangaDialog(
                onDismissRequest = onDismissRequest,
                onConfirm = viewModel::clearManga,
            )
        }
        // KMK <--
    }

    if (showScanlatorsDialog) {
        ScanlatorFilterDialog(
            availableScanlators = successState.availableScanlators,
            excludedScanlators = successState.excludedScanlators,
            onDismissRequest = { showScanlatorsDialog = false },
            onConfirm = viewModel::setExcludedScanlators,
        )
    }
}

private fun continueReading(context: Context, unreadChapter: Chapter?) {
    if (unreadChapter != null) openChapter(context, unreadChapter)
}

private fun openChapter(context: Context, chapter: Chapter) {
    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id))
}

@Suppress("LocalVariableName")
private fun getMangaUrl(manga_: Manga?, source_: Source?): String? {
    val manga = manga_ ?: return null
    val source = source_ as? HttpSource ?: return null

    return try {
        source.getMangaUrl(manga.toSManga())
    } catch (_: Exception) {
        null
    }
}

@Suppress("LocalVariableName")
private fun openMangaInWebView(navigator: Navigator, manga_: Manga?, source_: Source?) {
    getMangaUrl(manga_, source_)?.let { url ->
        navigator.push(
            WebViewRoute(
                url = url,
                initialTitle = manga_?.title,
                sourceId = source_?.id,
            ),
        )
    }
}

@Suppress("LocalVariableName")
private fun shareManga(context: Context, manga_: Manga?, source_: Source?) {
    try {
        getMangaUrl(manga_, source_)?.let { url ->
            val intent = url.toUri().toShareIntent(context, type = "text/plain")
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        context.toast(e.message)
    }
}

/**
 * Perform a search using the provided query.
 *
 * @param query the search query to the parent controller
 */
private fun performSearch(
    navigator: Navigator,
    resultEventBus: ResultEventBus,
    query: String,
    global: Boolean,
) {
    if (global) {
        navigator.push(GlobalSearchRoute(query))
        return
    }

    when (val previous = navigator.previous) {
        is HomeRoute -> {
            navigator.pop()
            resultEventBus.sendResult<TabEvent>(TabEvent.Library(query = query))
        }
        is BrowseSourceRoute -> {
            navigator.pop()
            resultEventBus.sendResult(BrowseSearchEvent(query))
        }
        // SY -->
        is SourceFeedRoute -> {
            navigator.push(BrowseSourceRoute(previous.sourceId, query))
        }
        // SY <--
    }
}

/**
 * Performs a genre search using the provided genre name.
 *
 * @param genreName the search genre to the parent controller
 */
private fun performGenreSearch(
    navigator: Navigator,
    resultEventBus: ResultEventBus,
    genreName: String,
    source: Source,
) {
    val previous = navigator.previous
    if (previous is BrowseSourceRoute && source is HttpSource) {
        navigator.pop()
        resultEventBus.sendResult(BrowseSearchEvent(genreName, genre = true))
        // SY -->
    } else if (previous is SourceFeedRoute && source is HttpSource) {
        navigator.pop()
        navigator.push(BrowseSourceRoute(previous.sourceId, genreName))
        // SY <--
    } else {
        performSearch(navigator, resultEventBus, genreName, global = false)
    }
}

/**
 * Copy Manga URL to Clipboard
 */
@Suppress("LocalVariableName")
private fun copyMangaUrl(context: Context, manga_: Manga?, source_: Source?) {
    val manga = manga_ ?: return
    val source = source_ as? HttpSource ?: return
    val url = source.getMangaUrl(manga.toSManga())
    context.copyToClipboard(url, url)
}

// SY -->
private fun openMetadataViewer(
    navigator: Navigator,
    manga: Manga,
    // KMK -->
    seedColor: Color?,
    // KMK <--
) {
    navigator.push(MetadataViewRoute(manga.id, manga.source, seedColor?.toArgb()))
}

private fun mergedMangaAction(
    context: Context,
    navigator: Navigator,
    mergedMangaData: MergedMangaData,
    // KMK -->
    action: (Context, Navigator, Manga, HttpSource?) -> Unit,
    titleRes: StringResource,
    // KMK <--
) {
    val sourceManager: SourceManager = context.appGraph.sourceManager
    // KMK -->
    // Compose callback with no coroutine scope: read the already-loaded source snapshot instead
    // of blocking the main thread. While the sources are still loading, fall back to a blank
    // placeholder stub so the option list keeps its shape (its action then no-ops, as it would
    // for a genuinely missing source).
    val mergedMangaAndSources = mergedMangaData.manga.values
        .filterNot { it.source == MERGED_SOURCE_ID }
        .map { manga ->
            manga to (sourceManager.getOrStubOrNull(manga.source) ?: StubSource(manga.source, "", ""))
        }
    // KMK <--
    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes.getString(context))
        .setSingleChoiceItems(
            Array(mergedMangaAndSources.size) { index ->
                // KMK -->
                mergedMangaAndSources[index].second.toString()
                // KMK <--
            },
            -1,
        ) { dialog, index ->
            dialog.dismiss()
            // KMK -->
            val (manga, source) = mergedMangaAndSources[index]
            action(context, navigator, manga, source as? HttpSource)
            // KMK <--
        }
        .setNegativeButton(MR.strings.action_cancel.getString(context), null)
        .show()
}

// KMK -->
private fun browseSource(
    navigator: Navigator,
    source: Source,
    useNewSourceNavigation: Boolean,
    smartSearchConfig: SmartSearchConfig?,
) {
    val route = when {
        // Clicked on source of an entry being merged with previous entry or
        // source of an recommending entry (to search again)
        smartSearchConfig != null ->
            SmartSearchSourceRoute(source.id, smartSearchConfig.origTitle, smartSearchConfig.origMangaId)
        useNewSourceNavigation -> SourceFeedRoute(source.id)
        else -> BrowseSourceRoute(source.id, GetRemoteManga.QUERY_POPULAR)
    }
    when (route) {
        // When doing a migrate/recommend => replace previous screen to perform search again.
        is SmartSearchSourceRoute -> {
            navigator.popTo { it is SmartSearchSourceRoute }
            if (navigator.canPop) navigator.replace(route) else navigator.push(route)
        }

        is SourceFeedRoute -> {
            navigator.popTo { it is SourceFeedRoute }
            if (navigator.canPop) navigator.replace(route) else navigator.push(route)
        }

        else -> {
            navigator.popTo { it is BrowseSourceRoute }
            if (navigator.canPop) navigator.replace(route) else navigator.push(route)
        }
    }
}
// KMK <--

private fun openMorePagePreviews(navigator: Navigator, manga: Manga) {
    navigator.push(PagePreviewRoute(manga.id))
}

private fun openPagePreview(context: Context, chapter: Chapter?, page: Int) {
    chapter ?: return
    context.startActivity(ReaderActivity.newIntent(context, chapter.mangaId, chapter.id, page))
}
// SY <--

// EXH -->
/**
 * Called when click Merge on an entry to search for entries to merge.
 */
private fun openSmartSearch(navigator: Navigator, manga: Manga) {
    navigator.push(SmartSearchRoute(origTitle = manga.title, origMangaId = manga.id))
}

@OptIn(DelicateCoroutinesApi::class)
private fun mergeWithAnother(
    navigator: Navigator,
    context: Context,
    manga: Manga,
    smartSearchMerge: suspend (Manga, Long) -> Manga,
    smartSearchConfig: SmartSearchConfig?,
) {
    launchUI {
        try {
            val mergedManga = withNonCancellableContext {
                smartSearchMerge(manga, smartSearchConfig?.origMangaId!!)
            }

            navigator.popTo { it is SmartSearchRoute }
            navigator.pop()
            // KMK -->
            if (navigator.current !is MangaRoute) {
                navigator.push(MangaRoute(mergedManga.id))
            } else {
                // KMK <--
                navigator.replace(MangaRoute(mergedManga.id))
            }
            context.toast(SYMR.strings.entry_merged)
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            context.toast(context.stringResource(SYMR.strings.failed_merge, e.message.orEmpty()))
        }
    }
}
// EXH <--

// AZ -->
private fun openRecommends(navigator: Navigator, source: Source?, manga: Manga) {
    source ?: return
    navigator.push(RecommendsRoute(RecommendsArgs.SingleSourceManga(manga.id, source.id)))
}
// AZ <--
