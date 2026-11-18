package mihon.core.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen
import eu.kanade.presentation.more.settings.screen.SettingsAnkiScreen
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsBrowseScreen
import eu.kanade.presentation.more.settings.screen.SettingsConnectionScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsDictionaryPopupScreen
import eu.kanade.presentation.more.settings.screen.SettingsDictionaryScreen
import eu.kanade.presentation.more.settings.screen.SettingsDiscordScreen
import eu.kanade.presentation.more.settings.screen.SettingsEhScreen
import eu.kanade.presentation.more.settings.screen.SettingsMangadexScreen
import eu.kanade.presentation.more.settings.screen.SettingsOcrScreen
import eu.kanade.presentation.more.settings.screen.SettingsDownloadScreen
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.more.settings.screen.SettingsSearchScreen
import eu.kanade.presentation.more.settings.screen.SettingsSecurityScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLicensesScreen
import eu.kanade.presentation.more.settings.screen.advanced.ClearDatabaseScreen
import eu.kanade.presentation.more.settings.screen.advanced.OrphanedDownloadsScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppLanguageScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.debug.BackupSchemaScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.presentation.more.settings.screen.debug.WorkerInfoScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppCustomThemeColorPickerScreen
import eu.kanade.presentation.more.settings.screen.data.SyncSettingsSelector
import eu.kanade.presentation.more.settings.screen.data.SyncTriggerOptionsScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.migration.manga.MigrateMangaScreen
import eu.kanade.tachiyomi.ui.browse.migration.search.MigrateSearchScreen
import eu.kanade.tachiyomi.ui.browse.migration.search.MigrateSourceSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesFilterScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.feed.SourceFeedScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.category.biometric.BiometricTimesScreen
import eu.kanade.tachiyomi.ui.category.genre.SortTagScreen
import eu.kanade.tachiyomi.ui.category.sources.SourceCategoryScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryAudioSettingsScreen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryBackupImportScreen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryManagementScreen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import eu.kanade.tachiyomi.ui.libraryUpdateError.LibraryUpdateErrorScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialog
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.ComingUpdatesScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.more.WhatsNewScreen
import eu.kanade.tachiyomi.ui.more.DictionaryOcrSetupScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.list.MigrationListScreen
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import eu.kanade.tachiyomi.ui.manga.PaletteScreen
import exh.debug.SettingsDebugScreen
import exh.md.follows.MangaDexFollowsScreen
import exh.pagepreview.PagePreviewScreen
import exh.ui.batchadd.BatchAddScreen
import exh.ui.metadata.MetadataViewScreen
import exh.ui.smartsearch.SmartSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.SmartSearchConfig
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen as SourcesScreenRoot
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreen
import eu.kanade.tachiyomi.ui.setting.connections.DiscordLoginScreen
import eu.kanade.presentation.more.settings.screen.DiscordAccountsScreen
import mihon.feature.upcoming.UpcomingScreen

fun EntryProviderScope<NavKey>.appEntries() {
    entry<HomeRoute> {
        HomeScreen()
    }
    entry<DeepLinkRoute> { route ->
        DeepLinkScreen(route.query)
    }
    entry<WebViewRoute> { route ->
        val navigator = LocalNavigator.current
        LocalAssistContentManager.current.Provide(route) {
            WebViewScreen(route, onNavigateUp = navigator::pop)
        }
    }

    entry<MangaNotesRoute> { route ->
        MangaNotesScreen(route.mangaId)
    }
    entry<MangaRoute> { route ->
        LocalAssistContentManager.current.Provide(route) {
            MangaScreen(route.mangaId, route.fromSource)
        }
    }
    entry<TrackInfoDialogRoute>(metadata = OverlaySheetScene.overlaySheet()) { route ->
        val navigator = LocalNavigator.current
        TrackInfoDialog(
            mangaId = route.mangaId,
            mangaTitle = route.mangaTitle,
            sourceId = route.sourceId,
            onDismissRequest = { navigator.pop() },
        )
    }

    entry<GlobalSearchRoute> { route ->
        GlobalSearchScreen(route.searchQuery, route.extensionFilter)
    }

    entry<UpcomingRoute> {
        UpcomingScreen()
    }

    entry<BrowseSourceRoute> { route ->
        LocalAssistContentManager.current.Provide(route) {
            BrowseSourceScreen(route.sourceId, route.listingQuery)
        }
    }
    entry<ExtensionDetailsRoute> { route ->
        ExtensionDetailsScreen(route.pkgName)
    }
    entry<ExtensionFilterRoute> {
        ExtensionFilterScreen()
    }
    entry<MigrateMangaRoute> { route ->
        MigrateMangaScreen(route.sourceId)
    }
    entry<MigrateSearchRoute> { route ->
        MigrateSearchScreen(route.mangaId)
    }
    entry<MigrateSourceSearchRoute> { route ->
        MigrateSourceSearchScreen(route.currentMangaId, route.sourceId, route.query)
    }
    entry<MigrationConfigRoute> { route ->
        MigrationConfigScreen(route.mangaIds)
    }
    entry<MigrationListRoute> { route ->
        MigrationListScreen(route.mangaIds, route.extraSearchQuery, route.isSmartSearchSingleEntry)
    }
    entry<SourcesFilterRoute> {
        SourcesFilterScreen()
    }
    entry<SourcePreferencesRoute> { route ->
        SourcePreferencesScreen(route.sourceId)
    }

    entry<CategoryRoute> {
        CategoryScreen()
    }
    entry<DownloadQueueRoute> {
        DownloadQueueScreen()
    }
    entry<NewUpdateRoute>(metadata = TwoPaneSettingsScene.fullScreen()) { route ->
        NewUpdateScreen(
            versionName = route.versionName,
            changelogInfo = route.changelogInfo,
            releaseLink = route.releaseLink,
            downloadLink = route.downloadLink,
            // KUN -->
            digest = route.digest,
            // KUN <--
        )
    }
    entry<OnboardingRoute> {
        OnboardingScreen()
    }
    entry<SettingsMainScreen>(metadata = TwoPaneSettingsScene.listPane()) {
        SettingsMainScreen.Content()
    }
    entry<StatsRoute> {
        StatsScreen()
    }

    entry<AboutRoute> {
        AboutScreen()
    }
    entry<AppLanguageRoute> {
        AppLanguageScreen()
    }
    entry<BackupSchemaRoute> {
        BackupSchemaScreen()
    }
    entry<ClearDatabaseRoute> {
        ClearDatabaseScreen()
    }
    entry<CreateBackupRoute> {
        CreateBackupScreen()
    }
    entry<DebugInfoRoute> {
        DebugInfoScreen()
    }
    entry<ExtensionStoresRoute> {
        ExtensionStoresScreen()
    }
    entry<OpenSourceLicensesRoute> {
        OpenSourceLicensesScreen()
    }
    entry<RestoreBackupRoute> { route ->
        RestoreBackupScreen(route.uri)
    }
    entry<SettingsSearchRoute> {
        SettingsSearchScreen()
    }
    entry<WorkerInfoRoute> {
        WorkerInfoScreen()
    }

    entry<SettingsAdvancedScreen> {
        SettingsAdvancedScreen.Content()
    }
    entry<SettingsAppearanceScreen> {
        SettingsAppearanceScreen.Content()
    }
    entry<SettingsBrowseScreen> {
        SettingsBrowseScreen.Content()
    }
    entry<SettingsDataScreen> {
        SettingsDataScreen.Content()
    }
    entry<SettingsDownloadScreen> {
        SettingsDownloadScreen.Content()
    }
    entry<SettingsLibraryScreen> {
        SettingsLibraryScreen.Content()
    }
    entry<SettingsReaderScreen> {
        SettingsReaderScreen.Content()
    }
    entry<SettingsSecurityScreen> {
        SettingsSecurityScreen.Content()
    }
    entry<SettingsTrackingScreen> {
        SettingsTrackingScreen.Content()
    }

    // KUN -->
    // The screens this fork adds on top of upstream's registry. Every fork route must be listed
    // here: a `Navigator.push` of an unregistered route renders nothing.
    entry<TopLevelRoute.Dictionary> {
        DictionaryTab()
    }
    entry<SmartSearchRoute> { route ->
        SourcesScreenRoot(SmartSearchConfig(route.origTitle, route.origMangaId))
    }
    entry<SmartSearchSourceRoute> { route ->
        SmartSearchScreen(route.sourceId, SmartSearchConfig(route.origTitle, route.origMangaId))
    }
    entry<ExtensionsRoute> { route ->
        ExtensionsScreen(route.searchSource)
    }
    entry<SourceFeedRoute> { route ->
        SourceFeedScreen(route.sourceId)
    }
    entry<MangaDexFollowsRoute> { route ->
        MangaDexFollowsScreen(route.sourceId)
    }
    entry<PagePreviewRoute> { route ->
        PagePreviewScreen(route.mangaId)
    }
    entry<PaletteRoute> { route ->
        PaletteScreen(route.seedColor)
    }
    entry<MetadataViewRoute> { route ->
        MetadataViewScreen(route.mangaId, route.sourceId, route.seedColor)
    }
    entry<BatchAddRoute> {
        BatchAddScreen()
    }
    entry<SettingsDebugRoute> {
        SettingsDebugScreen()
    }
    entry<UpdatesTabRoute> {
        UpdatesTab()
    }
    entry<HistoryTabRoute> {
        HistoryTab()
    }
    entry<LibraryUpdateErrorRoute> {
        LibraryUpdateErrorScreen()
    }
    entry<WhatsNewRoute> { route ->
        WhatsNewScreen(
            currentVersion = route.currentVersion,
            versionName = route.versionName,
            changelogInfo = route.changelogInfo,
            releaseLink = route.releaseLink,
        )
    }
    entry<ComingUpdatesRoute> { route ->
        ComingUpdatesScreen(
            versionName = route.versionName,
            changelogInfo = route.changelogInfo,
            releaseLink = route.releaseLink,
            downloadLink = route.downloadLink,
            digest = route.digest,
        )
    }
    entry<DictionaryOcrSetupRoute> {
        DictionaryOcrSetupScreen()
    }
    entry<DictionaryManagementRoute> {
        DictionaryManagementScreen()
    }
    entry<DictionaryAudioSettingsRoute> {
        DictionaryAudioSettingsScreen()
    }
    entry<DictionaryBackupImportRoute> {
        DictionaryBackupImportScreen()
    }
    entry<SortTagRoute> {
        SortTagScreen()
    }
    entry<SourceCategoryRoute> {
        SourceCategoryScreen()
    }
    entry<BiometricTimesRoute> {
        BiometricTimesScreen()
    }
    entry<AppCustomThemeColorPickerRoute> {
        AppCustomThemeColorPickerScreen()
    }
    entry<OrphanedDownloadsRoute> {
        OrphanedDownloadsScreen()
    }
    entry<SyncSettingsSelectorRoute> {
        SyncSettingsSelector()
    }
    entry<SyncTriggerOptionsRoute> {
        SyncTriggerOptionsScreen()
    }
    entry<DiscordLoginRoute> {
        DiscordLoginScreen()
    }
    entry<DiscordAccountsRoute> {
        DiscordAccountsScreen()
    }

    entry<SettingsConnectionScreen> {
        SettingsConnectionScreen.Content()
    }
    entry<SettingsDictionaryScreen> {
        SettingsDictionaryScreen.Content()
    }
    entry<SettingsDictionaryPopupScreen> {
        SettingsDictionaryPopupScreen.Content()
    }
    entry<SettingsAnkiScreen> {
        SettingsAnkiScreen.Content()
    }
    entry<SettingsOcrScreen> {
        SettingsOcrScreen.Content()
    }
    entry<SettingsDiscordScreen> {
        SettingsDiscordScreen.Content()
    }
    entry<SettingsEhScreen> {
        SettingsEhScreen.Content()
    }
    entry<SettingsMangadexScreen> {
        SettingsMangadexScreen.Content()
    }
    // KUN <--
}
