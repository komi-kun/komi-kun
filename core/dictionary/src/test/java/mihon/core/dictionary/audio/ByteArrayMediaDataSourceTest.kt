// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ByteArrayMediaDataSourceTest {

    @Test
    fun `readAt reads requested slice into buffer`() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val source = ByteArrayMediaDataSource(data)
        source.size shouldBe 5L

        val buffer = ByteArray(10)
        val read = source.readAt(position = 1L, buffer = buffer, offset = 2, size = 3)

        read shouldBe 3
        buffer[2] shouldBe 20
        buffer[3] shouldBe 30
        buffer[4] shouldBe 40
    }

    @Test
    fun `readAt returns -1 when position is at or beyond data size`() {
        val data = byteArrayOf(1, 2, 3)
        val source = ByteArrayMediaDataSource(data)

        val buffer = ByteArray(5)
        source.readAt(position = 3L, buffer = buffer, offset = 0, size = 2) shouldBe -1
        source.readAt(position = 10L, buffer = buffer, offset = 0, size = 2) shouldBe -1
        source.readAt(position = -1L, buffer = buffer, offset = 0, size = 2) shouldBe -1
    }

    @Test
    fun `readAt returns 0 when size is 0 even at end of data`() {
        val data = byteArrayOf(1, 2, 3)
        val source = ByteArrayMediaDataSource(data)
        val buffer = ByteArray(5)

        source.readAt(position = 0L, buffer = buffer, offset = 0, size = 0) shouldBe 0
        source.readAt(position = 3L, buffer = buffer, offset = 0, size = 0) shouldBe 0
        source.readAt(position = 10L, buffer = buffer, offset = 0, size = 0) shouldBe 0
    }

    @Test
    fun `readAt caps read length at remaining bytes`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val source = ByteArrayMediaDataSource(data)

        val buffer = ByteArray(5)
        val read = source.readAt(position = 3L, buffer = buffer, offset = 0, size = 10)

        read shouldBe 2
        buffer[0] shouldBe 4
        buffer[1] shouldBe 5
    }
}
// KUN <--
