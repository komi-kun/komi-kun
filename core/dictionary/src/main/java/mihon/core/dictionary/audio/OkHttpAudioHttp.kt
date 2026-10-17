// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Default [AudioHttp] implementation over the app's OkHttp stack (core:common exposes OkHttp as
// `api`). GET-only; the audio handlers never POST.
package mihon.core.dictionary.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class OkHttpAudioHttp(
    private val client: OkHttpClient = defaultClient(),
) : AudioHttp {

    override suspend fun get(url: String): AudioHttpResponse = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                AudioHttpResponse(
                    ok = response.isSuccessful,
                    status = response.code,
                    bodyText = response.body.string(),
                )
            }
        } catch (_: Exception) {
            AudioHttpResponse(ok = false, status = 0, bodyText = "")
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
// KUN <--
