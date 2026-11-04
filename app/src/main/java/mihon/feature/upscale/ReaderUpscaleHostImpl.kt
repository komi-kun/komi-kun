// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// GPL implementation of the Apache `eu.kanade.tachiyomi.ui.reader.upscale.ReaderUpscaleHost` seam
// (contract §1.3). It lives in `mihon.feature.upscale` — the app-level GPL feature package, distinct
// from the Apache seam package — so `verifyGplFree` can tell the two apart and the reader only ever
// sees the Apache interfaces.
//
// This is the app-scoped half: it holds nothing that belongs to a reader. The engine, the result
// cache, the model set and the request queue are all graph-scoped singletons (`UpscaleBindings`), so
// a reader that closes only drops the handle — it never tears the engine down.
//
// The host is the upscaling sibling of `ReaderOcrHostImpl`: `@Inject` + `@ContributesBinding`, and
// deliberately not wired through `AppBindings.kt` or a host-specific binding container.
// KUN <--
package mihon.feature.upscale

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.upscale.ReaderUpscaleHost
import eu.kanade.tachiyomi.ui.reader.upscale.ReaderUpscaleSession
import eu.kanade.tachiyomi.ui.reader.upscale.UpscaleRuntimePolicy
import mihon.core.upscale.UpscaleService
import mihon.core.upscale.cache.UpscaleCache
import mihon.core.upscale.model.ModelDownloader
import tachiyomi.core.common.preference.UpscalePreferences

/**
 * GPL implementation of [ReaderUpscaleHost]: one reader session per reader activity, over the
 * app-scoped [UpscaleService].
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReaderUpscaleHostImpl(
    private val service: UpscaleService,
    private val cache: UpscaleCache,
    private val downloader: ModelDownloader,
    private val preferences: UpscalePreferences,
) : ReaderUpscaleHost {

    override fun isEnabled(): Boolean = UpscaleRuntimePolicy.isActive(
        enabled = preferences.isEnabled(),
        installedIds = downloader.installedIds(),
    )

    override fun newSession(): ReaderUpscaleSession = ReaderUpscaleSessionImpl(
        service = service,
        cache = cache,
        models = downloader,
        preferences = preferences,
    )
}
