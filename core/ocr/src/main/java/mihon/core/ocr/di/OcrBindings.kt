// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.ocr.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import mihon.core.metro.AppCoroutineScope
import mihon.core.ocr.OcrEngine
import mihon.core.ocr.OcrPreferences
import mihon.core.ocr.cache.OcrCacheManager
import mihon.core.ocr.cache.OcrJob
import mihon.core.ocr.cache.OcrManager
import mihon.core.ocr.cache.OcrStore
import mihon.core.ocr.merge.OcrLineMerger
import mihon.core.ocr.merge.OwOCRMerger
import mihon.core.ocr.paddle.ModelDownloader
import mihon.core.ocr.paddle.PaddleOcrEngine
import mihon.core.ocr.paddle.PaddleOcrVlEngine
import mihon.core.ocr.paddle.PaddleOcrVlOnDeviceEngine
import mihon.core.ocr.paddle.RoutingOcrEngine
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Metro bindings for :core:ocr (contract §3.6).
 *
 * Created empty by W1 and registered in `AppGraph.bindingContainers`; W3 fills it with the OCR
 * services. The concrete services are plain classes wired here (rather than `@Inject` constructors)
 * so the module keeps a single, reviewable list of what it contributes — mirroring
 * `DictionaryBindings`. Interface seams ([OcrEngine], [OcrLineMerger]) are bound to their Paddle /
 * OwOCR implementations.
 */
@BindingContainer
object OcrBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesPaddleOcrEngine(context: Context, preferences: OcrPreferences): PaddleOcrEngine =
        PaddleOcrEngine(context, preferences)

    @Provides
    @SingleIn(AppScope::class)
    fun providesPaddleOcrVlEngine(preferences: OcrPreferences, networkHelper: NetworkHelper): PaddleOcrVlEngine =
        PaddleOcrVlEngine(preferences, networkHelper.client)

    @Provides
    @SingleIn(AppScope::class)
    fun providesPaddleOcrVlOnDeviceEngine(context: Context, preferences: OcrPreferences): PaddleOcrVlOnDeviceEngine =
        PaddleOcrVlOnDeviceEngine(context, preferences)

    @Provides
    @SingleIn(AppScope::class)
    fun providesOcrEngine(
        context: Context,
        preferences: OcrPreferences,
        localEngine: PaddleOcrEngine,
        vlServerEngine: PaddleOcrVlEngine,
        vlOnDeviceEngine: PaddleOcrVlOnDeviceEngine,
    ): OcrEngine = RoutingOcrEngine(context, preferences, localEngine, vlServerEngine, vlOnDeviceEngine)

    @Provides
    @SingleIn(AppScope::class)
    fun providesOcrLineMerger(): OcrLineMerger = OwOCRMerger()

    @Provides
    @SingleIn(AppScope::class)
    fun providesOcrPreferences(preferenceStore: PreferenceStore): OcrPreferences = OcrPreferences(preferenceStore)

    @Provides
    @SingleIn(AppScope::class)
    fun providesOcrCacheManager(context: Context, json: Json): OcrCacheManager = OcrCacheManager(context, json)

    @Provides
    @SingleIn(AppScope::class)
    fun providesOcrStore(context: Context, json: Json): OcrStore = OcrStore(context, json)

    @Provides
    @SingleIn(AppScope::class)
    fun providesOcrManager(
        engine: OcrEngine,
        merger: OcrLineMerger,
        cacheManager: OcrCacheManager,
        store: OcrStore,
        preferences: OcrPreferences,
        @AppCoroutineScope scope: CoroutineScope,
    ): OcrManager = OcrManager(
        engine = engine,
        merger = merger,
        cacheManager = cacheManager,
        store = store,
        preferences = preferences,
        scope = scope,
    )

    @Provides
    @SingleIn(AppScope::class)
    fun providesModelDownloader(
        context: Context,
        networkHelper: NetworkHelper,
        json: Json,
    ): ModelDownloader = ModelDownloader(context, networkHelper.client, json)

    @Provides
    @SingleIn(AppScope::class)
    fun providesOcrJob(
        manager: OcrManager,
        @AppCoroutineScope scope: CoroutineScope,
    ): OcrJob = OcrJob(manager, scope)
}
