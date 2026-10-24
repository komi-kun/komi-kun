// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The owner of the dictionary repository's native session lifetime.
//
// The repository opens its session lazily on the first lookup and reuses it for the whole process,
// because re-parsing the dictionary set on every use costs seconds. Two things therefore have to
// happen *outside* the repository:
//
//  * the user opting out must release the session **at once** — not at the next background, and not
//    by leaving the parsed dictionaries pinned in the native heap until the process dies;
//  * a background/foreground cycle must **not** release it: nothing about a ProcessLifecycle stop
//    makes the parsed tree useless, and re-parsing it on every resume is a pure regression (see the
//    `App.onTrimMemory` hook for the case where the system really needs the memory back).
//
// This class is that owner, and it is deliberately tiny and Android-free so the transition rule is
// unit-tested (`DictionarySessionOwnerTest`) instead of living in an untested `Application` callback.
// Closing is serialised by `DictionaryRepository` itself (its `close` is `@Synchronized`), so the
// collector can run on any dispatcher.
// KUN <--
package mihon.dictionary.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Closes the dictionary session when the combined dictionary/OCR opt-in goes from on to off.
 *
 * Only an **on → off** transition releases the session: the initial value of [enabled] is not a
 * transition (a process that starts with the feature off never opened a session), and repeated
 * `false` emissions must not keep calling [closeSession].
 */
class DictionarySessionOwner(
    private val enabled: Flow<Boolean>,
    private val closeSession: () -> Unit,
) {
    /** Starts observing [enabled]; the returned job ends when the scope is cancelled. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        var previous: Boolean? = null
        enabled.distinctUntilChanged().collect { now ->
            if (previous == true && !now) closeSession()
            previous = now
        }
    }
}
