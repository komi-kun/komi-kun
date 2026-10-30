// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// GPL implementation of the Apache `eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrHost` seam
// (contract §1.2/§2.3). It lives in the distinct `.impl` package so `verifyGplFree` can separate the
// Apache seam files from the GPL implementation. The reader only ever sees the Apache interfaces.
//
// This is the **app-scoped half only**: it hands out one `ReaderOcrSession` per reader activity and
// keeps the one piece of state that genuinely outlives a reader *activity* — the state a session
// closed for a configuration change leaves for the session the recreated reader creates. No viewport,
// active block or popup anchor is held here, so nothing a reader owns can outlive it.
// KUN <--
package eu.kanade.tachiyomi.ui.reader.ocr.impl

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrHost
import eu.kanade.tachiyomi.ui.reader.ocr.ReaderOcrSession
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import mihon.dictionary.host.ReaderDictionaryHost

/**
 * GPL implementation of [ReaderOcrHost] (contract §3.6b: `@Inject` + `@ContributesBinding`, never
 * wired through `AppBindings.kt` or `OcrHostBindings`).
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReaderOcrHostImpl(
    private val blockLoader: ReaderOcrBlockLoader,
    private val readerPreferences: ReaderPreferences,
    private val dictionaryPreferences: DictionaryPreferences,
    private val dictionaryHost: ReaderDictionaryHost,
    private val popupAnchor: ReaderOcrPopupAnchor,
    private val context: Context,
) : ReaderOcrHost {

    /**
     * The state of the session that was just closed for a configuration change, waiting for the
     * session the recreated reader creates. A handover slot, not live reader state: it holds nothing
     * between readers, and the next [newSession] takes it and clears it.
     *
     * Android destroys the old activity (and so closes its session) *before* it creates the new one,
     * which is why the handover cannot be a reference from the old session to its successor.
     */
    private var handover: ReaderOcrSessionState? = null

    override fun newSession(): ReaderOcrSession = synchronized(this) {
        val state = handover ?: ReaderOcrSessionState()
        handover = null
        ReaderOcrSessionImpl(
            state = state,
            blockLoader = blockLoader,
            readerPreferences = readerPreferences,
            dictionaryPreferences = dictionaryPreferences,
            dictionaryHost = dictionaryHost,
            popupAnchor = popupAnchor,
            context = context,
            keepForRecreatedReader = ::keepForRecreatedReader,
        )
    }

    /** Where a session closed for a configuration change leaves its state (see [handover]). */
    private fun keepForRecreatedReader(state: ReaderOcrSessionState) {
        synchronized(this) { handover = state }
    }
}
