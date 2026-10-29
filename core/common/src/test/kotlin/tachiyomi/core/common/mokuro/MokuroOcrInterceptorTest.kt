// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// The `.mokuro` network cache is opt-in: with the immersion switch off it must not create
// `{filesDir}/mokuro_ocr`, buffer a sidecar body or write a cache file, and it must start caching
// the moment the switch is turned on (the client is built once per process, so the check cannot be
// a construction-time one).
// KUN <--
package tachiyomi.core.common.mokuro

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

class MokuroOcrInterceptorTest {

    private val sidecar = """{"pages":["img/p001.jpg"],"title":"ch1"}"""

    @Test
    fun `the switch off returns the sidecar untouched and creates no cache directory`(
        @TempDir filesDir: File,
    ) {
        val interceptor = MokuroOcrInterceptor(filesDir.asContext(), isEnabled = { false })

        val (code, body) = fetch(interceptor, MOKURO_URL)

        code shouldBe 200
        body shouldBe sidecar
        // Neither construction nor an intercepted `.mokuro` request may touch the disk: no
        // `mokuro_ocr/` directory, and therefore no cache file in one.
        File(filesDir, "mokuro_ocr").exists() shouldBe false
        filesDir.listFiles().orEmpty().map { it.name } shouldBe emptyList()
    }

    @Test
    fun `the switch on caches the sidecar under the url hash and still returns it`(
        @TempDir filesDir: File,
    ) {
        val interceptor = MokuroOcrInterceptor(filesDir.asContext(), isEnabled = { true })

        val (code, body) = fetch(interceptor, MOKURO_URL)

        code shouldBe 200
        body shouldBe sidecar
        val cached = File(File(filesDir, "mokuro_ocr"), "${sha256(MOKURO_URL)}.json")
        cached.isFile shouldBe true
        cached.readText(Charsets.UTF_8) shouldBe sidecar
    }

    /**
     * The switch is read per request, never captured in the constructor: `NetworkHelper` builds its
     * OkHttp clients once, so a construction-time gate would only start working after a restart.
     */
    @Test
    fun `turning the switch on after construction starts caching without a restart`(
        @TempDir filesDir: File,
    ) {
        var enabled = false
        val interceptor = MokuroOcrInterceptor(filesDir.asContext(), isEnabled = { enabled })

        fetch(interceptor, MOKURO_URL)
        File(filesDir, "mokuro_ocr").exists() shouldBe false

        enabled = true
        fetch(interceptor, MOKURO_URL)

        val cached = File(File(filesDir, "mokuro_ocr"), "${sha256(MOKURO_URL)}.json")
        cached.isFile shouldBe true
        cached.readText(Charsets.UTF_8) shouldBe sidecar
    }

    /** Only `.mokuro` paths are ever buffered, switch or no switch. */
    @Test
    fun `a non-mokuro response is passed through without being cached`(@TempDir filesDir: File) {
        val interceptor = MokuroOcrInterceptor(filesDir.asContext(), isEnabled = { true })

        val (code, body) = fetch(interceptor, "https://example.org/pages/1.jpg")

        code shouldBe 200
        body shouldBe sidecar
        File(filesDir, "mokuro_ocr").exists() shouldBe false
    }

    /**
     * Runs [interceptor] against a fabricated `200 OK` (the terminal interceptor below never touches
     * the network), so both the interception and the on-disk bytes are real.
     */
    private fun fetch(interceptor: Interceptor, url: String): Pair<Int, String> {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sidecar.toResponseBody(null))
                    .build()
            }
            .build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.code to response.body.string()
        }
    }

    private fun File.asContext(): Context = mockk<Context> {
        every { filesDir } returns this@asContext
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val MOKURO_URL = "https://example.org/chapters/1/ch1.mokuro"
    }
}
