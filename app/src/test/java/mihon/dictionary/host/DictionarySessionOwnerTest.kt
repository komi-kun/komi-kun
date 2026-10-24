// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The session-release rule of the dictionary opt-in: the native session must be released when the
// user turns the switch **off**, immediately, and nothing else may release it. The real preference
// store emits its current value first and then every write, which is what `MutableStateFlow` does
// here too.
//
// The failure this pins: the previous wiring closed the session from `ProcessLifecycle.onStop` only,
// so turning the switch off kept a parsed dictionary set alive until the app was backgrounded, and
// every background/foreground cycle paid a full re-parse.
// KUN <--
package mihon.dictionary.host

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionarySessionOwnerTest {

    private val enabled = MutableStateFlow(false)
    private val closes = mutableListOf<Unit>()

    private fun owner() = DictionarySessionOwner(
        enabled = enabled,
        closeSession = { closes += Unit },
    )

    @Test
    fun `turning the switch off closes the open session immediately`() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = true
        owner().start(backgroundScope)
        runCurrent()

        // On: nothing is released (the session is in use).
        closes.size shouldBe 0

        // Off: one release, without waiting for any lifecycle callback.
        enabled.value = false
        runCurrent()
        closes.size shouldBe 1
    }

    @Test
    fun `a repeated off does not keep closing the already closed session`() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = true
        owner().start(backgroundScope)
        runCurrent()

        enabled.value = false
        runCurrent()
        enabled.value = false
        runCurrent()

        closes.size shouldBe 1
    }

    @Test
    fun `every on to off cycle releases the session that cycle opened`() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = true
        owner().start(backgroundScope)
        runCurrent()

        repeat(3) {
            enabled.value = false
            runCurrent()
            enabled.value = true
            runCurrent()
        }
        enabled.value = false
        runCurrent()

        // Not one-shot: each opt-out released the session opened since the previous one.
        closes.size shouldBe 4
    }

    @Test
    fun `starting with the feature off never closes anything`() = runTest(UnconfinedTestDispatcher()) {
        // A process that starts opted out has no session to release; treating the initial value as a
        // transition would construct the repository (and its native engine) on every cold start.
        enabled.value = false
        owner().start(backgroundScope)
        runCurrent()

        closes.size shouldBe 0
    }

    @Test
    fun `a duplicate on is not a transition`() = runTest(UnconfinedTestDispatcher()) {
        // Guards the obvious over-eager implementation: "close whenever the value is false" would also
        // close on the first emissions of a never-enabled session.
        enabled.value = true
        owner().start(backgroundScope)
        runCurrent()
        enabled.value = true
        runCurrent()

        closes.size shouldBe 0
    }
}
