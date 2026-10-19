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
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class WordAudioPlayer(
    private var context: Context? = null,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var fallbackTrack: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false

    var onPlaybackError: (() -> Unit)? = null

    fun attachContext(ctx: Context) {
        if (context == null) {
            context = ctx.applicationContext
        }
    }

    /** Play [url] at [volume] (0f..1f). Safe to call from any thread; work is posted to the main. */
    fun play(url: String, volume: Float) {
        if (url.isBlank()) return
        mainHandler.post { playInternal(url, volume.coerceIn(0f, 1f)) }
    }

    /** Play fallback synthesized sound ([AudioFallbackSound]) at [volume]. */
    fun playFallback(soundType: String, volume: Float) {
        mainHandler.post { playFallbackInternal(soundType, volume.coerceIn(0f, 1f)) }
    }

    /** Stop and release the current clip. */
    fun stop() {
        mainHandler.post { releaseInternal() }
    }

    /** Release player and shutdown TTS resources. */
    fun destroy() {
        mainHandler.post {
            releaseInternal()
            tts?.shutdown()
            tts = null
            ttsInitialized = false
        }
    }

    private fun playInternal(url: String, volume: Float) {
        releaseInternal()
        if (url.startsWith("tts://speak?") || url.startsWith("tts:")) {
            speakTts(url, volume)
            return
        }
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        mediaPlayer.setVolume(volume, volume)
        mediaPlayer.setOnPreparedListener { it.start() }
        mediaPlayer.setOnCompletionListener { releaseInternal() }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            releaseInternal()
            onPlaybackError?.invoke()
            true
        }
        try {
            mediaPlayer.setDataSource(url)
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (_: Exception) {
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
        tts?.stop()
        releaseFallbackTrack()
    }
}
// KUN <--
