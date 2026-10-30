// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Word-audio playback, the Android stand-in for Yomitan's `AudioSystem`/`HTMLAudioElement`. The
// popup WebView runs with `blockNetworkLoads`, so audio is fetched and played natively (MediaPlayer)
// instead of through an `<audio>` element. One player per bridge instance; starting a new clip stops
// the previous one, matching Yomitan's `stopAudio()` before each `_playAudio`.
package mihon.core.dictionary.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val TAG = "WordAudioPlayer"

class WordAudioPlayer(
    private var context: Context? = null,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentPlayJob: Job? = null
    private var player: MediaPlayer? = null
    private var currentDataSource: MediaDataSource? = null
    private var fallbackTrack: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false

    var onPlaybackError: (() -> Unit)? = null

    fun attachContext(ctx: Context) {
        if (context == null) {
            context = ctx.applicationContext
            ctx.cacheDir?.let { AudioCache.purgeLegacyDiskCache(it) }
        }
    }

    /** Play [url] at [volume] (0f..1f). Safe to call from any thread; work is posted to the main. */
    fun play(url: String, volume: Float) {
        if (url.isBlank()) return
        playCandidateList(listOf(url), volume)
    }

    /**
     * Play first playable candidate from [urls] at [volume] (0f..1f).
     * If candidate 0 is cached in memory, playback starts instantly (< 2ms) via [ByteArrayMediaDataSource].
     * Otherwise, downloads directly into RAM via OkHttp in the background with zero storage I/O.
     */
    fun playCandidateList(urls: List<String>, volume: Float) {
        val validUrls = urls.filter { it.isNotBlank() }
        if (validUrls.isEmpty()) return
        mainHandler.post {
            playCandidateListInternal(validUrls, volume.coerceIn(0f, 1f))
        }
    }

    /** Prefetch [urls] to the in-memory cache in the background. */
    fun prefetch(urls: List<String>) {
        for (url in urls.take(3)) {
            if (url.isNotBlank() && !url.startsWith("tts:")) {
                AudioCache.prefetch(url, OkHttpAudioHttp.sharedClient, ioScope)
            }
        }
    }

    fun prefetch(url: String) {
        if (url.isBlank() || url.startsWith("tts:")) return
        AudioCache.prefetch(url, OkHttpAudioHttp.sharedClient, ioScope)
    }

    /** Play fallback synthesized sound ([AudioFallbackSound]) at [volume]. */
    fun playFallback(soundType: String, volume: Float) {
        mainHandler.post { playFallbackInternal(soundType, volume.coerceIn(0f, 1f)) }
    }

    /** Stop and release the current clip. */
    fun stop() {
        mainHandler.post {
            currentPlayJob?.cancel()
            releaseInternal()
        }
    }

    /** Release player and shutdown TTS resources. */
    fun destroy() {
        mainHandler.post {
            currentPlayJob?.cancel()
            ioScope.cancel()
            releaseInternal()
            tts?.shutdown()
            tts = null
            ttsInitialized = false
        }
    }

    private fun playCandidateListInternal(urls: List<String>, volume: Float) {
        currentPlayJob?.cancel()
        releaseInternal()

        // 1. Check if the first candidate is already cached in memory or is TTS
        val firstUrl = urls.firstOrNull() ?: return
        if (firstUrl.startsWith("tts://speak?") || firstUrl.startsWith("tts:")) {
            speakTts(firstUrl, volume)
            return
        }

        val cachedBytes = AudioCache.get(firstUrl)
        if (cachedBytes != null) {
            if (playData(cachedBytes, firstUrl, volume)) {
                if (urls.size > 1) prefetch(urls.drop(1))
                return
            }
        }

        // 2. Not cached in memory: download directly into memory via coroutine (zero storage I/O)
        currentPlayJob = ioScope.launch {
            val client = OkHttpAudioHttp.sharedClient
            for (url in urls) {
                if (!isActive) return@launch
                if (url.startsWith("tts://speak?") || url.startsWith("tts:")) {
                    withContext(Dispatchers.Main) {
                        if (isActive) speakTts(url, volume)
                    }
                    return@launch
                }

                val bytes = AudioCache.getOrDownload(url, client)
                if (bytes != null && isActive) {
                    val played = withContext(Dispatchers.Main) {
                        if (isActive) playData(bytes, url, volume) else false
                    }
                    if (played) return@launch
                }
            }

            withContext(Dispatchers.Main) {
                if (isActive) {
                    onPlaybackError?.invoke()
                }
            }
        }
    }

    private fun playData(bytes: ByteArray, url: String, volume: Float): Boolean {
        releaseInternal()
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        mediaPlayer.setVolume(volume, volume)
        mediaPlayer.setOnCompletionListener {
            Log.d(TAG, "MediaPlayer completed playback: $url")
            releaseInternal()
        }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error what=$what extra=$extra for url: $url")
            releaseInternal()
            onPlaybackError?.invoke()
            true
        }
        return try {
            val dataSource = ByteArrayMediaDataSource(bytes)
            currentDataSource = dataSource
            mediaPlayer.setDataSource(dataSource)
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play in-memory audio for url: $url", e)
            AudioCache.remove(url)
            val ds = currentDataSource
            currentDataSource = null
            runCatching { ds?.close() }
            runCatching { mediaPlayer.release() }
            false
        }
    }

    private fun playUrlDirect(url: String, volume: Float) {
        releaseInternal()
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        mediaPlayer.setVolume(volume, volume)
        mediaPlayer.setOnPreparedListener {
            Log.d(TAG, "MediaPlayer starting playback: $url")
            it.start()
        }
        mediaPlayer.setOnCompletionListener {
            Log.d(TAG, "MediaPlayer completed playback: $url")
            releaseInternal()
        }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error what=$what extra=$extra for url: $url")
            releaseInternal()
            onPlaybackError?.invoke()
            true
        }
        try {
            mediaPlayer.setDataSource(url)
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start direct playback for url: $url", e)
            runCatching { mediaPlayer.release() }
            onPlaybackError?.invoke()
        }
    }

    private fun speakTts(url: String, volume: Float) {
        val uri = Uri.parse(url)
        val text = uri.getQueryParameter("text") ?: return
        val lang = uri.getQueryParameter("lang")
        val voiceName = uri.getQueryParameter("voice")
        val ctx = context ?: return

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        val utteranceId = "word_audio_${System.currentTimeMillis()}"

        fun doSpeak(ttsEngine: TextToSpeech) {
            if (!lang.isNullOrBlank()) {
                val locale = Locale.forLanguageTag(lang)
                runCatching { ttsEngine.language = locale }
            }
            if (!voiceName.isNullOrBlank()) {
                val matchingVoice = runCatching {
                    ttsEngine.voices?.firstOrNull { it.name.equals(voiceName, ignoreCase = true) }
                }.getOrNull()
                if (matchingVoice != null) {
                    runCatching { ttsEngine.voice = matchingVoice }
                }
            }
            ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }

        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsInitialized = true
                    val ttsEngine = tts ?: return@TextToSpeech
                    mainHandler.post {
                        doSpeak(ttsEngine)
                    }
                }
            }
        } else if (ttsInitialized) {
            doSpeak(tts!!)
        }
    }

    private fun playFallbackInternal(soundType: String, volume: Float) {
        if (soundType == AudioFallbackSound.NONE || volume <= 0f) return
        releaseInternal()

        val sampleRate = 44100
        val buffer: ShortArray = when (soundType) {
            AudioFallbackSound.CLICK -> {
                // Short ~15 ms click impulse: decaying cosine burst at ~1800 Hz
                val nSamples = (0.015 * sampleRate).toInt()
                ShortArray(nSamples) { i ->
                    val t = i.toFloat() / sampleRate
                    val decay = exp(-i.toFloat() / (nSamples * 0.25f))
                    val wave = cos(2.0 * PI * 1800.0 * t).toFloat() * decay
                    (wave * 32767f * volume).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            AudioFallbackSound.BLOOP -> {
                // Short ~90 ms pleasant two-tone sine "bloop" (glide 520 Hz -> 360 Hz with smooth envelope)
                val nSamples = (0.090 * sampleRate).toInt()
                var phase = 0.0
                ShortArray(nSamples) { i ->
                    val freq = 520.0 - (520.0 - 360.0) * (i.toDouble() / nSamples)
                    phase += 2.0 * PI * freq / sampleRate
                    val env = sin(PI * i.toDouble() / nSamples)
                    val wave = (sin(phase) * env).toFloat()
                    (wave * 32767f * volume).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            else -> return
        }

        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buffer, 0, buffer.size)
            track.play()
            fallbackTrack = track
        } catch (_: Exception) {
            // AudioTrack unavailable or audio service error
        }
    }

    private fun releaseFallbackTrack() {
        val track = fallbackTrack ?: return
        fallbackTrack = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun releaseInternal() {
        val current = player
        player = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        val ds = currentDataSource
        currentDataSource = null
        runCatching { ds?.close() }
        tts?.stop()
        releaseFallbackTrack()
    }
}
// KUN <--
