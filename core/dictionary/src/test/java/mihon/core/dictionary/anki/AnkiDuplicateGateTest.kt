// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.anki

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * T1 duplicate-gate gate (plan 05 §A3/B4): the stripe set must serialise the preflight→commit
 * window for a given key so the same card cannot be inserted twice concurrently.
 */
class AnkiDuplicateGateTest {

    @Test
    fun `preflight returns the decision`() = runBlocking {
        val gate = AnkiDuplicateGate()
        gate.preflight("k") { AnkiDuplicateDecision.Overwrite(42L) } shouldBe AnkiDuplicateDecision.Overwrite(42L)
    }

    @Test
    fun `commit passes the decision to the block`() = runBlocking {
        val gate = AnkiDuplicateGate()
        val seen = gate.commit("k", { AnkiDuplicateDecision.Insert }) { it }
        seen shouldBe AnkiDuplicateDecision.Insert
    }

    @Test
    fun `same-key critical sections never overlap`() = runBlocking {
        val gate = AnkiDuplicateGate()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val jobs = (1..32).map {
            launch(Dispatchers.Default) {
                gate.commit("same|key", { AnkiDuplicateDecision.Insert }) {
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { max -> maxOf(max, now) }
                    delay(1)
                    active.decrementAndGet()
                }
            }
        }
        jobs.joinAll()
        maxActive.get() shouldBe 1
        active.get() shouldBe 0
    }
}
// KUN <--
