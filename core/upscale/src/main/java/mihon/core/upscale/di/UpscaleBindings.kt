// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Metro bindings for :core:upscale.
//
// Registered in `AppGraph.bindingContainers` (phase 4), which is what lets the app resolve
// `UpscaleService` — and the GPL `ReaderUpscaleHostImpl` behind the reader's Apache seam. Keeping the
// wiring in one object (rather than `@Inject` constructors) mirrors `OcrBindings`/`DictionaryBindings`
// and keeps the module's contributions reviewable in one place — and keeps a GPL feature's services
// out of the shared `AppBindings`.
//
// Everything here is `@SingleIn(AppScope::class)`: one cache, one downloader, one engine (and
// therefore one ORT session and one inference thread) for the whole process.
// KUN <--
package mihon.core.upscale.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import mihon.core.upscale.UpscaleService
import mihon.core.upscale.cache.UpscaleCache
import mihon.core.upscale.engine.UpscaleEngine
import mihon.core.upscale.model.ModelDownloader
import tachiyomi.core.common.preference.UpscalePreferences
import java.io.File

private const val CACHE_DIR = "upscale/results"
private const val BYTES_PER_MB = 1_000_000L

/**
 * The upscaling module's DI graph: the synchronous result cache, the model downloader, the engine
 * (one inference thread, one session) and the reader-facing service.
 */
@BindingContainer
object UpscaleBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesUpscaleCache(
        context: Context,
        preferences: UpscalePreferences,
    ): UpscaleCache = UpscaleCache(
        // The system may delete a cache directory at any time; a missing entry is just a miss.
        directory = File(context.cacheDir, CACHE_DIR),
        maxBytes = { preferences.cacheMb.get().toLong() * BYTES_PER_MB },
    )

    @Provides
    @SingleIn(AppScope::class)
    fun providesUpscaleModelDownloader(
        context: Context,
        networkHelper: NetworkHelper,
        json: Json,
        preferences: UpscalePreferences,
    ): ModelDownloader = ModelDownloader(
        context = context,
        httpClient = networkHelper.client,
        json = json,
        preferences = preferences,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun providesUpscaleEngine(downloader: ModelDownloader): UpscaleEngine =
        UpscaleEngine(modelsDir = downloader.modelsDir)

    @Provides
    @SingleIn(AppScope::class)
    fun providesUpscaleService(
        cache: UpscaleCache,
        downloader: ModelDownloader,
        engine: UpscaleEngine,
        preferences: UpscalePreferences,
    ): UpscaleService = UpscaleService(
        cache = cache,
        models = downloader,
        engine = engine,
        preferences = preferences,
    )
}
