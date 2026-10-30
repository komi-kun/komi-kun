// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.audio

import android.media.MediaDataSource
import androidx.annotation.RequiresApi

/**
 * In-memory [MediaDataSource] providing zero-disk, zero-I/O random access to audio byte arrays
 * for Android [android.media.MediaPlayer].
 */
@RequiresApi(23)
class ByteArrayMediaDataSource(
    private val data: ByteArray,
) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position >= data.size || position < 0) {
            return -1
        }
        val remaining = (data.size - position).toInt()
        val toCopy = minOf(size, remaining)
        System.arraycopy(data, position.toInt(), buffer, offset, toCopy)
        return toCopy
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {
        // In-memory data requires no resource cleanup
    }
}
// KUN <--
