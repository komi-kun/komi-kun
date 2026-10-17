// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Word-audio playback, the Android stand-in for Yomitan's `AudioSystem`/`HTMLAudioElement`. The
// popup WebView runs with `blockNetworkLoads`, so audio is fetched and played natively (MediaPlayer)
// instead of through an `<audio>` element. One player per bridge instance; starting a new clip stops
// the previous one, matching Yomitan's `stopAudio()` before each `_playAudio`.
package mihon.core.dictionary.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

class WordAudioPlayer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** Play [url] at [volume] (0f..1f). Safe to call from any thread; work is posted to the main. */
    fun play(url: String, volume: Float) {
        if (url.isBlank()) return
        mainHandler.post { playInternal(url, volume.coerceIn(0f, 1f)) }
    }

    /** Stop and release the current clip. */
    fun stop() {
        mainHandler.post { releaseInternal() }
    }

    private fun playInternal(url: String, volume: Float) {
        releaseInternal()
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
            true
        }
        try {
            mediaPlayer.setDataSource(url)
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (_: Exception) {
            runCatching { mediaPlayer.release() }
        }
    }

    private fun releaseInternal() {
        val current = player ?: return
        player = null
        runCatching { current.stop() }
        runCatching { current.reset() }
        runCatching { current.release() }
    }
}
// KUN <--
