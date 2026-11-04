// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Reader upscaling settings (phase 4b).
//
// Owned by W4, registered by `SettingsMainScreen`/`SettingsSearchScreen` like `SettingsOcrScreen`. All
// user-visible text comes from the `i18n-kun` module (`KUNMR`); the services are the graph-scoped
// singletons (`UpscaleBindings`), reached through `context.appGraph`, so the progress this screen
// shows, the models it installs and the pages it clears are exactly the ones the reader uses. A
// second `ModelDownloader` or `UpscaleCache` built here would report a different state and free
// nothing.
//
// **Default off, and inert while off.** The master switch (and the licence/attribution block, which
// must never be hidden behind a switch) stay visible; every actionable row — routing, scale, bucket,
// the colour model, downloads, the output cap, the cache — is hidden with `visible = featureEnabled`,
// and the I/O behind it (the installed-model listing, the cache size) is not performed at all. This
// screen is reachable while the feature is off because it owns the master switch: hiding it would
// strand re-enable.
//
// **Downloads are refused, not queued.** `ModelDownloader` is itself inert while the feature is off,
// and this screen additionally refuses to start a download while "Wi-Fi only" is on and the active
// network is metered — a 530 MB download a user did not expect does not get to spend their data.
// KUN <--
package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.connectivityManager
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.core.upscale.model.ModelFamily
import mihon.core.upscale.model.UpscaleDownloadState
import mihon.core.upscale.model.UpscaleModelCatalog
import tachiyomi.core.common.preference.UpscaleMode
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Reader-upscaling settings.
 *
 * The performance copy on this screen is measured, not estimated: one OnePlus Pad 2, 2x, tile 512,
 * page 1125x1600 — MangaJaNai 1600p ~56 s/page, IllustrationJaNai V3 detail SPAN ~14 s/page, V3 detail
 * FDAT ~103 s/page (CPU; WebGPU returns NaN for that arch at the default tile). Upscaling runs in the
 * background as pages are read, so a page shows its original resolution until its upscaled copy is
 * ready on a later open — the switch's summary says so.
 */
object SettingsUpscaleScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = KUNMR.strings.pref_category_upscaling

    // KUN -->
    /**
     * Always reachable. Unlike the dictionary popup/Anki screens (hidden while the combined switch is
     * off), this screen **owns** the upscaling master switch, so hiding it would leave no way to turn
     * the feature on. Its actionable rows are gated inside instead.
     */
    override fun isEnabled(): Boolean = true
    // KUN <--

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val preferences = remember(context) { context.appGraph.upscalePreferences }
        val downloader = remember(context) { context.appGraph.upscaleModelDownloader }
        val cache = remember(context) { context.appGraph.upscaleCache }
        val scope = rememberCoroutineScope()

        val featureEnabled by preferences.enabled.collectAsState()
        val mode by preferences.mode.collectAsState()
        val scale by preferences.scale.collectAsState()
        val mangaBucket by preferences.mangaBucket.collectAsState()
        val illustrationId by preferences.illustrationModel.collectAsState()
        val unmeteredOnly by preferences.unmeteredOnly.collectAsState()
        val downloadState by downloader.state.collectAsState()

        // A device fact, read once: whether *this* network is metered does not change while the
        // screen is open in any way this screen can observe, and it only decides whether a download
        // may start right now.
        val metered = remember(context) { context.connectivityManager.isActiveNetworkMetered }
        val downloadBlocked = unmeteredOnly && metered

        // ---------------------------------------------------------------- installed set + cache

        var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        LaunchedEffect(featureEnabled, downloadState) {
            // A directory listing plus the cached manifest — cheap, but still I/O, and nothing is
            // touched at all while the feature is off.
            installedIds = if (featureEnabled) withIOContext { downloader.installedIds() } else emptySet()
        }

        var cacheBytes by remember { mutableStateOf<Long?>(null) }
        var clearedText by remember { mutableStateOf<String?>(null) }
        var showClearDialog by remember { mutableStateOf(false) }
        var isClearing by remember { mutableStateOf(false) }

        LaunchedEffect(featureEnabled) {
            cacheBytes = if (featureEnabled) withIOContext { cache.sizeBytes() } else null
        }

        val clearConfirmText = stringResource(KUNMR.strings.confirm_clear_upscale_cache)
        val clearTitleText = stringResource(KUNMR.strings.pref_upscale_clear_cache)
        val clearActionText = stringResource(KUNMR.strings.action_clear_upscale_cache)
        val cancelledText = stringResource(MR.strings.action_cancel)
        val clearedTemplate = stringResource(KUNMR.strings.pref_upscale_cache_cleared)

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(text = clearTitleText) },
                text = { Text(text = clearConfirmText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            if (!isClearing) {
                                isClearing = true
                                scope.launch {
                                    val (removed, freed) = withIOContext {
                                        val bytes = cache.sizeBytes()
                                        cache.clear() to bytes
                                    }
                                    cacheBytes = withIOContext { cache.sizeBytes() }
                                    // The count *and* the freed size: "Empty" alone would not say
                                    // whether the button did anything on a cache the reader refills.
                                    clearedText = clearedTemplate.format(
                                        removed,
                                        Formatter.formatFileSize(context, freed),
                                    )
                                    isClearing = false
                                }
                            }
                        },
                    ) {
                        Text(text = clearActionText)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(text = cancelledText)
                    }
                },
            )
        }

        // ---------------------------------------------------------------- models for this scale

        val mangaModels = remember(scale) {
            UpscaleModelCatalog.forFamilyAndScale(ModelFamily.MANGA_JANAI, scale)
        }
        val colourModels = remember(scale) {
            UpscaleModelCatalog.forFamilyAndScale(ModelFamily.ILLUSTRATION_JANAI, scale)
        }
        val illustrationChoices = remember(scale) { UpscaleModelOptions.choices(scale) }
        val selectedMangaModel = remember(scale, mangaBucket) {
            mangaModels.firstOrNull { it.bucketHeight == mangaBucket } ?: mangaModels.first()
        }
        val selectedColourModel = remember(scale, illustrationId) {
            UpscaleModelOptions.resolve(scale, illustrationId)
        }

        // Switching to 4x keeps the stored colour model at 2x unless it is replaced, and a colour
        // model of the wrong scale routes nothing: colour pages would silently stay at their original
        // resolution. Repair the stored id (including a stale one from another build) here, so what
        // the picker shows is what the router will run.
        LaunchedEffect(scale, illustrationId, featureEnabled) {
            if (!featureEnabled) return@LaunchedEffect
            val resolved = UpscaleModelOptions.resolve(scale, illustrationId)
            if (resolved.id != illustrationId) preferences.illustrationModel.set(resolved.id)
        }

        // ---------------------------------------------------------------- labels

        val entryTemplate = stringResource(KUNMR.strings.upscale_model_entry)
        val measuredTemplate = stringResource(KUNMR.strings.upscale_model_entry_measured)
        val tierFast = stringResource(KUNMR.strings.upscale_tier_fast)
        val tierBalanced = stringResource(KUNMR.strings.upscale_tier_balanced)
        val tierBest = stringResource(KUNMR.strings.upscale_tier_best)
        val speedFast = stringResource(KUNMR.strings.upscale_speed_fast)
        val speedBalanced = stringResource(KUNMR.strings.upscale_speed_balanced)
        val speedBest = stringResource(KUNMR.strings.upscale_speed_best)
        val modeSummary = stringResource(KUNMR.strings.pref_upscale_mode_summary)
        val bucketSummary = stringResource(KUNMR.strings.pref_upscale_manga_bucket_summary)
        val scale4xSummary = stringResource(KUNMR.strings.pref_upscale_scale_4x_summary)

        fun sizeText(bytes: Long): String = Formatter.formatFileSize(context, bytes)

        // The bucket picker, sized from the catalog: `approxBytes` is the published file size the
        // release manifest carries (`UpscaleModelManifestTest` pins them together).
        val bucketEntries = remember(scale, entryTemplate) {
            mangaModels.mapNotNull { model -> model.bucketHeight }
                .associateWith { bucket ->
                    val model = mangaModels.first { it.bucketHeight == bucket }
                    entryTemplate.format("${bucket}px", sizeText(model.approxBytes))
                }
        }

        val colourEntries = remember(scale, entryTemplate, measuredTemplate) {
            illustrationChoices.associate { choice ->
                val size = sizeText(choice.model.approxBytes)
                val label = when (choice.tier) {
                    UpscaleIllustrationTier.FAST ->
                        measuredTemplate.format(tierFast, size, speedFast)
                    UpscaleIllustrationTier.BALANCED ->
                        measuredTemplate.format(tierBalanced, size, speedBalanced)
                    UpscaleIllustrationTier.BEST ->
                        measuredTemplate.format(tierBest, size, speedBest)
                    null -> entryTemplate.format(choice.model.displayName, size)
                }
                choice.model.id to label
            }
        }

        // ---------------------------------------------------------------- download state

        val downloadStatus = when (val state = downloadState) {
            is UpscaleDownloadState.Failed ->
                stringResource(KUNMR.strings.upscale_download_state_failed, state.message)
            is UpscaleDownloadState.Downloading ->
                stringResource(KUNMR.strings.upscale_download_state_progress, state.done, state.total)
            UpscaleDownloadState.Completed -> stringResource(KUNMR.strings.upscale_download_state_completed)
            UpscaleDownloadState.Idle -> stringResource(KUNMR.strings.upscale_download_state_idle)
        }
        val meteredWarning = stringResource(KUNMR.strings.upscale_download_metered)
        val installedCountTemplate = stringResource(KUNMR.strings.upscale_models_installed_count)

        // What the status row retries. A failed single-model download retried as the whole set would
        // spend ~530 MB the user never asked for.
        var lastRequestedIds by remember { mutableStateOf<List<String>>(emptyList()) }

        fun download(ids: List<String>) {
            // Refused while the feature is off (the rows are hidden then anyway) and while the
            // network is metered with "Wi-Fi only" on — the warning row says why.
            if (!featureEnabled || downloadBlocked) return
            lastRequestedIds = ids
            downloader.triggerDownload(ids)
        }

        // ---------------------------------------------------------------- preferences

        return listOf(
            // Master switch first: visible whether the feature is on or off, so this screen can
            // always turn it back on.
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.enabled,
                title = stringResource(KUNMR.strings.pref_upscale_enabled),
                subtitle = stringResource(KUNMR.strings.pref_upscale_enabled_summary),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_upscale_disabled_hint),
                visible = !featureEnabled,
            ),

            // The measured numbers, and the licence: both stay visible while the feature is off, so
            // a user can decide whether to turn it on without enabling a download first.
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_upscale_performance),
                subtitle = stringResource(KUNMR.strings.pref_upscale_performance_summary),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KUNMR.strings.pref_upscale_licence),
                subtitle = stringResource(KUNMR.strings.pref_upscale_licence_summary),
            ),

            // Routing.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.mode,
                entries = mapOf(
                    UpscaleMode.AUTO to stringResource(KUNMR.strings.pref_upscale_mode_auto),
                    UpscaleMode.MANGA_ONLY to stringResource(KUNMR.strings.pref_upscale_mode_manga),
                    UpscaleMode.ILLUSTRATION_ONLY to
                        stringResource(KUNMR.strings.pref_upscale_mode_illustration),
                ),
                title = stringResource(KUNMR.strings.pref_upscale_mode),
                subtitleProvider = { value, entries ->
                    entries[value]?.let { "$it · $modeSummary" } ?: modeSummary
                },
                visible = featureEnabled,
            ),

            // Scale. The warning is the 4x one: it is far slower and most pages end up over the
            // output cap, so 2x is the default and 4x is a deliberate choice.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.scale,
                entries = mapOf(
                    2 to stringResource(KUNMR.strings.pref_upscale_scale_2x),
                    4 to stringResource(KUNMR.strings.pref_upscale_scale_4x),
                ),
                title = stringResource(KUNMR.strings.pref_upscale_scale),
                subtitleProvider = { value, entries ->
                    if (value == 4) "${entries[value]} · $scale4xSummary" else entries[value]
                },
                visible = featureEnabled,
            ),

            // MangaJaNai is trained per page height ("bucket"), so the picker is a bucket picker and
            // the router matches a page to the closest one. Hidden in IllustrationJaNai-only mode,
            // where no manga model is ever consulted.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.mangaBucket,
                entries = bucketEntries,
                title = stringResource(KUNMR.strings.pref_upscale_manga_bucket),
                subtitleProvider = { value, entries ->
                    entries[value]?.let { "$it · $bucketSummary" } ?: bucketSummary
                },
                visible = featureEnabled && mode != UpscaleMode.ILLUSTRATION_ONLY,
            ),

            // The colour model: the three curated picks plus the rest of the family at this scale.
            // The subtitle is the entry label, which already carries size and measured speed.
            Preference.PreferenceItem.ListPreference(
                preference = preferences.illustrationModel,
                entries = colourEntries,
                title = stringResource(KUNMR.strings.pref_upscale_illustration_model),
                subtitleProvider = { value, entries -> entries[value] ?: selectedColourModel.displayName },
                visible = featureEnabled && mode != UpscaleMode.MANGA_ONLY,
            ),

            // The one page-sized allocation the feature makes. A bigger result is not refused: the
            // engine splits it into vertical bands (`planBands`), so the cap bounds the memory one
            // page holds and only a page the cap cannot hold at all is left as it is.
            Preference.PreferenceItem.SliderPreference(
                preference = preferences.maxOutputMegapixels,
                title = stringResource(KUNMR.strings.pref_upscale_max_output),
                subtitle = stringResource(KUNMR.strings.pref_upscale_max_output_summary),
                valueRange = 4..32 step 4,
                valueText = { "$it MP" },
                visible = featureEnabled,
            ),

            // Models.
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_category_upscale_models),
                visible = featureEnabled,
                preferenceItems = listOf(
                    // A 530 MB download on a metered network is refused, and says so here rather
                    // than silently doing nothing when a download row is tapped.
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_metered_warning),
                        subtitle = meteredWarning,
                        visible = downloadBlocked,
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.unmeteredOnly,
                        title = stringResource(KUNMR.strings.pref_upscale_unmetered_only),
                        subtitle = stringResource(KUNMR.strings.pref_upscale_unmetered_only_summary),
                    ),
                    // Installed status, per family at the selected scale.
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_manga_models),
                        subtitle = installedCountTemplate.format(
                            mangaModels.count { it.id in installedIds },
                            mangaModels.size,
                        ),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_illustration_models),
                        subtitle = installedCountTemplate.format(
                            colourModels.count { it.id in installedIds },
                            colourModels.size,
                        ),
                    ),
                    // The two downloads a working setup needs: the bucket in use and the colour
                    // model in use. The whole set is one tap away below.
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_download_manga),
                        subtitle = sizeText(selectedMangaModel.approxBytes),
                        onClick = { download(listOf(selectedMangaModel.id)) },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_download_illustration),
                        subtitle = sizeText(selectedColourModel.approxBytes),
                        onClick = { download(listOf(selectedColourModel.id)) },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_download_all),
                        subtitle = stringResource(KUNMR.strings.pref_upscale_download_all_summary),
                        onClick = { download(emptyList()) },
                    ),
                    // Live progress, the failure reason, and the retry: tapping re-runs the last
                    // request. A completed download needs no row of its own (the counts above move).
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_download_status),
                        subtitle = if (downloadBlocked) meteredWarning else downloadStatus,
                        onClick = { download(lastRequestedIds) },
                    ),
                ),
            ),

            // Cache.
            Preference.PreferenceGroup(
                title = stringResource(KUNMR.strings.pref_category_upscale_cache),
                visible = featureEnabled,
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        preference = preferences.cacheMb,
                        title = stringResource(KUNMR.strings.pref_upscale_cache_mb),
                        subtitle = stringResource(KUNMR.strings.pref_upscale_cache_mb_summary),
                        valueRange = 32..4096 step 32,
                        valueText = { "$it MB" },
                    ),
                    // Current usage, so a raised/lowered cache size is visible immediately and an
                    // emptied cache reads as empty rather than as "still there".
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(KUNMR.strings.pref_upscale_cache_usage),
                        subtitle = cacheBytes?.let { Formatter.formatFileSize(context, it) } ?: "...",
                    ),
                    // After a clear this row reports what it removed (count *and* the size it freed),
                    // which is the only evidence that the button did anything on a cache that refills
                    // as soon as the reader touches a page again.
                    Preference.PreferenceItem.TextPreference(
                        title = clearTitleText,
                        subtitle = clearedText
                            ?: stringResource(KUNMR.strings.pref_upscale_clear_cache_summary),
                        onClick = { showClearDialog = true },
                    ),
                ),
            ),
        )
    }
}
