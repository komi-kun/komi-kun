package mihon.core.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import mihon.core.archive.ArchiveEntry as MihonArchiveEntry

/**
 * The largest `mtime` (in seconds) that is still a usable identity: 9999-12-31, so `* 1000` cannot
 * overflow and a corrupt header cannot invent a time.
 */
private const val MAX_MTIME_SECONDS = 253_402_300_799L

class ArchiveInputStream(
    buffer: Long,
    size: Long,
    // SY -->
    encrypted: Boolean,
    // SY <--
) : InputStream() {
    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    init {
        try {
            // SY -->
            if (encrypted) {
                Archive.readAddPassphrase(archive, CbzCrypto.getDecryptedPasswordCbz())
            }
            // SY <--
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenMemoryUnsafe(archive, buffer, size)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    override fun read(): Int {
        read(oneByteBuffer)
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val buffer = ByteBuffer.wrap(b, off, len)
        read(buffer)
        return if (buffer.hasRemaining()) buffer.remaining() else -1
    }

    private fun read(buffer: ByteBuffer) {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip()
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    fun getNextEntry(): MihonArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            // SY -->
            val isEncrypted = ArchiveEntry.isEncrypted(entry)
            // SY <--
            // KUN --> the recorded size (see `ArchiveEntry.size`); -1 when the archive has none.
            val size = if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry) else -1L
            // The recorded modification time, in millis (`mtime` is in seconds). Also metadata of the
            // header just parsed, so it costs no buffer read. A value that cannot be a time — or whose
            // millis would overflow — is reported as absent rather than as a bogus identity.
            val seconds = if (ArchiveEntry.mtimeIsSet(entry)) ArchiveEntry.mtime(entry) else -1L
            val lastModified = if (seconds in 1..MAX_MTIME_SECONDS) seconds * 1000L else -1L
            // KUN <--
            MihonArchiveEntry(
                name,
                isFile,
                // SY -->
                isEncrypted,
                // SY <--
                // KUN -->
                size,
                lastModified,
                // KUN <--
            )
        }
    }
}
