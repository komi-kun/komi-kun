package eu.kanade.tachiyomi.util.lang

import java.io.File
import java.security.MessageDigest

object Hash {

    private val chars = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'a', 'b', 'c', 'd', 'e', 'f',
    )

    private val MD5 get() = MessageDigest.getInstance("MD5")

    private val SHA256 get() = MessageDigest.getInstance("SHA-256")

    fun sha256(bytes: ByteArray): String {
        return encodeHex(SHA256.digest(bytes))
    }

    fun sha256(string: String): String {
        return sha256(string.toByteArray())
    }

    // KUN -->
    fun sha256(file: File): String {
        val digest = SHA256
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return encodeHex(digest.digest())
    }

    /**
     * Extracts a 64-character lowercase SHA-256 hex string from an input digest string,
     * which may be prefixed with "sha256:" (as returned by the GitHub Releases API).
     */
    fun extractSha256(digest: String?): String? {
        if (digest.isNullOrBlank()) return null
        val normalized = digest.trim().lowercase()
        val hex = if (normalized.startsWith("sha256:")) {
            normalized.removePrefix("sha256:").trim()
        } else {
            normalized
        }
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
    // KUN <--

    fun md5(bytes: ByteArray): String {
        return encodeHex(MD5.digest(bytes))
    }

    fun md5(string: String): String {
        return md5(string.toByteArray())
    }

    private fun encodeHex(data: ByteArray): String {
        val l = data.size
        val out = CharArray(l shl 1)
        var i = 0
        var j = 0
        while (i < l) {
            out[j++] = chars[(240 and data[i].toInt()).ushr(4)]
            out[j++] = chars[15 and data[i].toInt()]
            i++
        }
        return String(out)
    }
}
