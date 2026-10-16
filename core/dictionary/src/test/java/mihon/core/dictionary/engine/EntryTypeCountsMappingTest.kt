// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.engine

import io.kotest.matchers.shouldBe
import mihon.core.dictionary.model.EntryTypeCounts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * `HoshiDictsNative.probeEntryTypes` returns `[term, freq, pitch, kanji]` as 4 longs
 * (contract §2.1). The mapping is pure so it can be tested without `libhoshidicts_jni`.
 */
@Execution(ExecutionMode.CONCURRENT)
class EntryTypeCountsMappingTest {

    @Test
    fun `maps a full four-long reply in order`() {
        longArrayOf(10, 20, 30, 40).toEntryTypeCounts() shouldBe
            EntryTypeCounts(term = 10, freq = 20, pitch = 30, kanji = 40)
    }

    @Test
    fun `missing trailing counts default to zero`() {
        longArrayOf(7).toEntryTypeCounts() shouldBe
            EntryTypeCounts(term = 7, freq = 0, pitch = 0, kanji = 0)
    }

    @Test
    fun `an empty reply degrades to all zeros`() {
        LongArray(0).toEntryTypeCounts() shouldBe
            EntryTypeCounts(term = 0, freq = 0, pitch = 0, kanji = 0)
    }

    @Test
    fun `extra values are ignored`() {
        longArrayOf(1, 2, 3, 4, 5, 6).toEntryTypeCounts() shouldBe
            EntryTypeCounts(term = 1, freq = 2, pitch = 3, kanji = 4)
    }
}
