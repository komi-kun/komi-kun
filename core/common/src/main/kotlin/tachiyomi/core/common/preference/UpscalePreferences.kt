// SPDX-License-Identifier: Apache-2.0
// KUN -->
// komi-kun's reader-upscaling preferences (off by default).
//
// It lives in the Apache-2.0 `:core:common` module — not in the GPL `:core:upscale` module — so the
// reader (Apache, GPL-free) and the GPL engine can share exactly one set of keys without the reader
// importing GPL code. That also makes it a **registered** GPL-free seam file (contract §1.3 and
// `scripts/verify-gpl-free.sh`): keep the Apache header, never import a GPL package, and keep the
// mode enum here — the GPL router imports it from here, not the other way round.
//
// Nothing about upscaling runs while [UpscalePreferences.enabled] is false: the engine, the model
// download and the reader overlay are all gated on [UpscalePreferences.isEnabled].
// KUN <--
package tachiyomi.core.common.preference

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * How pages are routed to a model.
 *
 * Declared here (Apache) rather than in `:core:upscale` so the GPL-free reader can mention it without
 * touching GPL code.
 */
enum class UpscaleMode {
    /** Grayscale (line-art) pages use a MangaJaNai bucket, colour pages an IllustrationJaNai model. */
    AUTO,

    /** Every page uses a MangaJaNai bucket, colour or not. */
    MANGA_ONLY,

    /** Every page uses the selected IllustrationJaNai model. */
    ILLUSTRATION_ONLY,
}

/**
 * The reader-upscaling preferences (komi-kun's own keys).
 *
 * Off by default and inert until the user turns it on; every value here is a plain preference so the
 * settings screen and the engine read the same state.
 */
@Inject
@SingleIn(AppScope::class)
class UpscalePreferences(
    preferenceStore: PreferenceStore,
) {
    /** Master switch for reader upscaling; `false` until the user opts in. */
    val enabled: Preference<Boolean> = preferenceStore.getBoolean(KEY_ENABLED, false)

    /** Page routing policy. */
    val mode: Preference<UpscaleMode> = preferenceStore.getEnum(KEY_MODE, UpscaleMode.AUTO)

    /** Requested upscale factor (2 or 4). */
    val scale: Preference<Int> = preferenceStore.getInt(KEY_SCALE, DEFAULT_SCALE)

    /** Target page height for the MangaJaNai bucket, in pixels. */
    val mangaBucket: Preference<Int> = preferenceStore.getInt(KEY_MANGA_BUCKET, DEFAULT_MANGA_BUCKET)

    /**
     * The selected IllustrationJaNai model id. The default is the catalog's **2x V3 detail SPAN_S**
     * model (the fastest one measured, see [DEFAULT_ILLUSTRATION_MODEL_ID]); `UpscaleModelCatalogTest`
     * in `:core:upscale` pins the two defaults together.
     */
    val illustrationModel: Preference<String> =
        preferenceStore.getString(KEY_ILLUSTRATION_MODEL, DEFAULT_ILLUSTRATION_MODEL_ID)

    /** Result-cache budget, in megabytes. */
    val cacheMb: Preference<Int> = preferenceStore.getInt(KEY_CACHE_MB, DEFAULT_CACHE_MB)

    /** Only download models and run inference on unmetered networks. */
    val unmeteredOnly: Preference<Boolean> = preferenceStore.getBoolean(KEY_UNMETERED_ONLY, true)

    /**
     * Cap on the output image, in megapixels. Larger results are assembled from vertical bands
     * (`planBands` in `:core:upscale`) instead of being refused.
     *
     * 8 MP is the largest band the on-device benchmark measured: the 800x5685 webtoon page (18 MP at
     * 2x) ran as 3 bands of ~6 MP in 131 s with 132 MB of native heap and 77 MB of Java heap, while
     * every page in the matrix (7.7 MP at 2x) stayed a single band. 32 MP — four times what was
     * exercised — buys nothing a reader can show on a 3000 px-wide screen and multiplies the one
     * page-sized allocation the cap exists to bound (an ARGB output bitmap is 4 B/px: 32 MP is
     * 128 MB, 8 MP is 32 MB).
     */
    val maxOutputMegapixels: Preference<Int> =
        preferenceStore.getInt(KEY_MAX_OUTPUT_MP, DEFAULT_MAX_OUTPUT_MP)

    /** Whether upscaling may run right now. */
    fun isEnabled(): Boolean = enabled.get()

    /** Stores an explicit accept (`true`) or decline (`false`). */
    fun choose(value: Boolean) {
        enabled.set(value)
    }

    /**
     * Identifies the *selection* a cached result belongs to: two results are interchangeable only
     * when they were produced by the same mode, scale, manga bucket and illustration model. The
     * engine's cache keys on this, so changing any of them makes old results unreachable instead of
     * being served for a different model.
     */
    fun cacheSignature(): String = buildString {
        append("v").append(CACHE_SIGNATURE_VERSION)
        append(";mode=").append(mode.get().name)
        append(";scale=").append(scale.get())
        append(";bucket=").append(mangaBucket.get())
        append(";model=").append(illustrationModel.get())
    }

    companion object {
        const val KEY_ENABLED = "pref_reader_upscale_enabled"
        const val KEY_MODE = "pref_reader_upscale_mode"
        const val KEY_SCALE = "pref_reader_upscale_scale"
        const val KEY_MANGA_BUCKET = "pref_reader_upscale_manga_bucket"
        const val KEY_ILLUSTRATION_MODEL = "pref_reader_upscale_illustration_model"
        const val KEY_CACHE_MB = "pref_reader_upscale_cache_mb"
        const val KEY_UNMETERED_ONLY = "pref_reader_upscale_unmetered_only"
        const val KEY_MAX_OUTPUT_MP = "pref_reader_upscale_max_output_mp"

        /** Bumped when [cacheSignature] gains a component, so old entries stop matching. */
        const val CACHE_SIGNATURE_VERSION = 1

        const val DEFAULT_SCALE = 2
        const val DEFAULT_MANGA_BUCKET = 1600

        /**
         * The fast V3 detail model. Measured on a OnePlus Pad 2 (2x, 1125x1600 colour page, tile
         * 512): 14 s here against 103 s for the V3 detail FDAT_M, which is why the faster one is the
         * default; FDAT_M stays selectable for quality.
         */
        const val DEFAULT_ILLUSTRATION_MODEL_ID = "illustrationjanai_2x_v3detail_span_s"
        const val DEFAULT_CACHE_MB = 512

        /**
         * 16 MP holds a 2x upscale of an ordinary 1600x2400 page (15.4 MP). The cap exists to bound
         * the one page-sized output allocation; a page over it is **not** dropped — the engine splits
         * it into vertical bands (`planBands` in `:core:upscale`) and assembles them. The page keeps
         * its original resolution only when no band plan is possible at all (a cap too small to hold
         * one row of output), never merely for being larger than the cap.
         */
        const val DEFAULT_MAX_OUTPUT_MP = 16
    }
}
