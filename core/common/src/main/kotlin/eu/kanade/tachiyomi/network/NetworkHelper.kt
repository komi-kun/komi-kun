package eu.kanade.tachiyomi.network

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import eu.kanade.tachiyomi.util.lang.Hash
import exh.log.EHLogLevel
import kotlinx.coroutines.CoroutineScope
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import tachiyomi.core.common.mokuro.MokuroOcrInterceptor
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.DictionaryOcrPreferences
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@Inject
@SingleIn(AppScope::class)
/* SY --> */
open /* SY <-- */ class NetworkHelper(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: NetworkPreferences,
    // KUN -->
    // The immersion opt-in the `.mokuro` cache is gated on. Defaulted so the extension-facing
    // `HttpSource` subclass (three arguments) keeps compiling; the app graph injects the AppScope
    // singleton, and both read the same stored key.
    private val dictionaryOcrPreferences: DictionaryOcrPreferences =
        DictionaryOcrPreferences(AndroidPreferenceStore(context)),
    // KUN <--
) {

    /* SY --> */ open /* SY <-- */val cookieJar = AndroidCookieJar()

    /**
     * Timeout in unit of seconds.
     */
    private /* KMK --> */ fun /* KMK <-- */ clientBuilder(
        // KMK -->
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
        // KMK <--
    ): OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // KMK -->
            .connectTimeout(connectTimeout.seconds)
            .readTimeout(readTimeout.seconds)
            .callTimeout(callTimeout.seconds)
            // KMK <--
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
            // KUN -->
            // Cache any `.mokuro` OCR sidecar fetched through this client so the reader can use a
            // source-provided sidecar without downloading it again (03 §4). Generic + Apache: it
            // only recognises the extension and stores a copy (see MokuroOcrInterceptor).
            // Gated on the immersion switch *inside* the interceptor (read per request), not here:
            // the client outlives a switch flip, so a construction-time check would need a restart.
            .addInterceptor(MokuroOcrInterceptor(context, dictionaryOcrPreferences::isEnabled))
        // KUN <--

        // KMK -->
        if (EHLogLevel.isExtraLogging()) {
            // KMK <--
            val httpLoggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                if (preferences.redactSensitiveHeaders.get()) {
                    redactHeader("Authorization")
                    redactHeader("Auth")
                }
            }
            builder.addNetworkInterceptor(httpLoggingInterceptor)
        }

        when (preferences.dohProvider.get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            else -> builder
        }
    }

    /* SY --> */ open /* SY <-- */ val client /* KMK --> */ by lazy /* KMK <-- */ {
        clientBuilder()
            .addInterceptor(
                CloudflareInterceptor(context, cookieJar, scope, ::defaultUserAgentProvider),
            )
            .build()
    }

    // KMK -->
    /**
     * Timeout in unit of seconds.
     */
    private fun clientWithTimeOut(
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        callTimeout: Long = 120,
    ) = clientBuilder(connectTimeout, readTimeout, callTimeout)
        .addInterceptor(
            CloudflareInterceptor(context, cookieJar, scope, ::defaultUserAgentProvider),
        )
        .build()

    /**
     * Allow to download a big file with retry & resume capability because
     * normally it would get a Timeout exception.
     */
    fun downloadFileWithResume(
        url: String,
        outputFile: File,
        progressListener: ProgressListener,
        // KUN -->
        expectedDigest: String? = null,
        // KUN <--
    ) {
        val client = clientWithTimeOut(
            callTimeout = 120,
        )

        // KUN -->
        val expectedSha256 = Hash.extractSha256(expectedDigest)
        // KUN <--
        var downloadedBytes: Long

        var attempt = 0

        while (attempt < MAX_RETRY) {
            try {
                // Check how much has already been downloaded
                downloadedBytes = outputFile.length()
                // Set up request with Range header to resume from the last byte
                val request = GET(
                    url = url,
                    headers = Headers.Builder()
                        .add("Range", "bytes=$downloadedBytes-")
                        .build(),
                )

                var failed = false
                client.newCachelessCallWithProgress(request, progressListener).execute().use { response ->
                    val isPartial = response.code == 206
                    if (response.isSuccessful || isPartial) { // 206 indicates partial content
                        // KUN -->
                        val contentType = response.header("Content-Type")?.lowercase()
                        if (contentType?.contains("text/html") == true) {
                            logcat(LogPriority.ERROR) { "Unexpected HTML response for APK download. Deleting and retrying..." }
                            outputFile.delete()
                            attempt++
                            failed = true
                            return@use
                        }

                        val startPosition = if (isPartial) downloadedBytes else 0L
                        saveResponseToFile(response, outputFile, startPosition)

                        // Verify integrity if expected hash is known
                        if (expectedSha256 != null) {
                            val actualSha256 = Hash.sha256(outputFile)
                            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                                logcat(LogPriority.ERROR) {
                                    "Downloaded file checksum mismatch: expected $expectedSha256, got $actualSha256. Deleting and retrying..."
                                }
                                outputFile.delete()
                                attempt++
                                failed = true
                                return@use
                            }
                        }

                        if (response.isSuccessful || isPartial) {
                            return
                        }
                        // KUN <--
                    } else {
                        attempt++
                        logcat(LogPriority.ERROR) { "Unexpected response code: ${response.code}. Retrying..." }
                        if (response.code == 416) {
                            // 416: Range Not Satisfiable
                            outputFile.delete()
                        }
                        failed = true
                    }
                }
                if (failed) exponentialBackoff(attempt - 1)
            } catch (e: IOException) {
                logcat(LogPriority.ERROR) { "Download interrupted: ${e.message}. Retrying..." }
                // Wait or handle as needed before retrying
                attempt++
                exponentialBackoff(attempt - 1)
            }
        }
        // KUN -->
        outputFile.delete()
        val errorMsg = if (expectedSha256 != null) {
            "Max retry attempts reached. Downloaded APK failed SHA-256 verification ($expectedSha256)."
        } else {
            "Max retry attempts reached."
        }
        throw IOException(errorMsg)
        // KUN <--
    }

    // Helper function to save data incrementally
    private fun saveResponseToFile(response: Response, outputFile: File, startPosition: Long) {
        val body = response.body

        // Use RandomAccessFile to write from specific position
        RandomAccessFile(outputFile, "rw").use { file ->
            // KUN -->
            if (startPosition == 0L) {
                file.setLength(0)
            }
            // KUN <--
            file.seek(startPosition)
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    file.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    // Increment attempt and apply exponential backoff
    private fun exponentialBackoff(attempt: Int) {
        val backoffDelay = calculateExponentialBackoff(attempt)
        Thread.sleep(backoffDelay)
    }

    // Helper function to calculate exponential backoff with jitter
    private fun calculateExponentialBackoff(attempt: Int, baseDelay: Long = 1000L, maxDelay: Long = 32000L): Long {
        // Calculate the exponential delay
        val delay = baseDelay * 2.0.pow(attempt).toLong()
        logcat(LogPriority.ERROR) { "Exponential backoff delay: $delay ms" }
        // Apply jitter by adding a random value to avoid synchronized retries in distributed systems
        return (delay + Random.nextLong(0, 1000)).coerceAtMost(maxDelay)
    }
    // KMK <--

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default", ReplaceWith("client"))
    @Suppress("UNUSED")
    /* SY --> */
    open /* SY <-- */val cloudflareClient: OkHttpClient
        get() = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent.get().trim()

    companion object {
        // KMK -->
        private const val MAX_RETRY = 5
        // KMK <--
    }
}
