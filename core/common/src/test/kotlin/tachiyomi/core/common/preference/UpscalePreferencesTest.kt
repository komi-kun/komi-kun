// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Contract tests for `UpscalePreferences.cacheSignature()`.
//
// The signature is the cache key's "which selection produced this?" half. Both directions are
// defects on a device and neither fails loudly:
//
//  * a missing component serves an upscale made by a *different* model for the page — the exact
//    "colour page quietly replaced by a line-art upscale" case the router exists to prevent;
//  * an extra component (the cache budget, the master switch, the metered preference) makes every
//    existing entry unreachable whenever an unrelated setting changes, silently discarding hundreds
//    of megabytes of upscaled pages.
// KUN <--
package tachiyomi.core.common.preference

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class UpscalePreferencesTest {

    /** A fresh store per call, so every case starts from the shipped defaults. */
    private fun signature(configure: UpscalePreferences.() -> Unit = {}): String =
        UpscalePreferences(InMemoryPreferenceStore()).apply(configure).cacheSignature()

    @Test
    fun `every input that decides what runs changes the signature`() {
        val base = signature()

        // Each of these changes the model the engine runs, so a cached result from before it is not
        // interchangeable with one from after it.
        signature { mode.set(UpscaleMode.MANGA_ONLY) } shouldNotBe base
        signature { scale.set(4) } shouldNotBe base
        signature { mangaBucket.set(1200) } shouldNotBe base
        signature { illustrationModel.set("illustrationjanai_4x_v3detail_fdat_m") } shouldNotBe base

        // ...and they are distinct from each other, not merely different from the default.
        val all = listOf(
            base,
            signature { mode.set(UpscaleMode.MANGA_ONLY) },
            signature { scale.set(4) },
            signature { mangaBucket.set(1200) },
            signature { illustrationModel.set("illustrationjanai_4x_v3detail_fdat_m") },
        )
        all.toSet().size shouldBe all.size
    }

    @Test
    fun `keys that do not decide what runs leave the signature alone`() {
        val base = signature()

        // Turning the feature on, resizing the cache, allowing metered downloads or raising the
        // output cap does not change which model produces a page, so it must not orphan the cache.
        signature { enabled.set(true) } shouldBe base
        signature { cacheMb.set(1024) } shouldBe base
        signature { unmeteredOnly.set(false) } shouldBe base
        signature { maxOutputMegapixels.set(32) } shouldBe base
    }

    @Test
    fun `the same selection always produces the same signature`() {
        // The key is written by one process and read by the next; anything non-deterministic here
        // (an identity hash, a timestamp) is a cache that never hits again.
        signature { mode.set(UpscaleMode.ILLUSTRATION_ONLY) } shouldBe
            signature { mode.set(UpscaleMode.ILLUSTRATION_ONLY) }
    }
}
