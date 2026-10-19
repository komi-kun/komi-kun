// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Minimal HTTP seam for the audio resolvers. The only networked handlers are `custom-json` (fetch a
// JSON list) and `jisho` (scrape the audio element). Keeping it an interface lets the resolvers be
// unit-tested with a fake and lets the Android bridge supply the app's OkHttp client.
package mihon.core.dictionary.audio

/** One HTTP response, decoded as UTF-8 text. `ok` mirrors `Response.isSuccessful`. */
data class AudioHttpResponse(
    val ok: Boolean,
    val status: Int,
    val bodyText: String,
)

/** Fetches HTTP requests. Implementations must be safe to call from a background dispatcher. */
fun interface AudioHttp {
    suspend fun get(url: String): AudioHttpResponse

    suspend fun postForm(url: String, formFields: Map<String, String>): AudioHttpResponse =
        AudioHttpResponse(ok = false, status = 0, bodyText = "")
}
// KUN <--
