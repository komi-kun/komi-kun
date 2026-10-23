// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Audio model mirroring Yomitan's `options.audio` group (ext/data/schemas/options-schema.json):
// `enabled`, `autoPlay`, `volume` (0-100), `fallbackSoundType` and the `type`-discriminated
// `sources[]` entries `{type, url, voice}`. Kept Android-free so the resolvers and the settings
// mapper can be unit-tested on the JVM.
//
// The exact Yomitan source types are reproduced verbatim so a settings backup round-trips.
package mihon.core.dictionary.audio

/** Yomitan's `audio.sources[].type` values (display-audio.js / audio-downloader.js). */
object AudioSourceTypes {
    const val JPOD_101 = "jpod101"
    const val LANGUAGE_POD_101 = "language-pod-101"
    const val JISHO = "jisho"
    const val LINGUA_LIBRE = "lingua-libre"
    const val WIKTIONARY = "wiktionary"
    const val TEXT_TO_SPEECH = "text-to-speech"
    const val TEXT_TO_SPEECH_READING = "text-to-speech-reading"
    const val CUSTOM = "custom"
    const val CUSTOM_JSON = "custom-json"

    val ALL = listOf(
        JPOD_101,
        LANGUAGE_POD_101,
        JISHO,
        LINGUA_LIBRE,
        WIKTIONARY,
        TEXT_TO_SPEECH,
        TEXT_TO_SPEECH_READING,
        CUSTOM,
        CUSTOM_JSON,
    )

    fun isKnown(type: String): Boolean = type in ALL

    /**
     * Yomitan's implicit default sources (`getRequiredAudioSourceList`, audio-downloader.js:608).
     * They are only used when the backup/profile leaves `enableDefaultAudioSources` on.
     */
    fun defaultsFor(languageIso: String): List<String> = if (languageIso.startsWith("ja")) {
        listOf(JPOD_101, LANGUAGE_POD_101, JISHO)
    } else {
        listOf(LINGUA_LIBRE, LANGUAGE_POD_101, WIKTIONARY)
    }

    /** Human display names, matching Yomitan's `display-audio.js` `_audioSourceTypeNames`. */
    fun displayName(type: String): String = when (type) {
        JPOD_101 -> "JapanesePod101"
        LANGUAGE_POD_101 -> "LanguagePod101"
        JISHO -> "Jisho.org"
        LINGUA_LIBRE -> "Lingua Libre"
        WIKTIONARY -> "Wiktionary"
        TEXT_TO_SPEECH -> "Text-to-speech"
        TEXT_TO_SPEECH_READING -> "Text-to-speech (Kana reading)"
        CUSTOM -> "Custom URL"
        CUSTOM_JSON -> "Custom URL (JSON)"
        else -> type
    }
}

/** Yomitan's `audio.fallbackSoundType` values. */
object AudioFallbackSound {
    const val NONE = "none"
    const val CLICK = "click"
    const val BLOOP = "bloop"
    val ALL = listOf(NONE, CLICK, BLOOP)
}

/** One `audio.sources[]` entry: `{type, url, voice}`. */
data class AudioSourceConfig(
    val type: String,
    val url: String = "",
    val voice: String = "",
)

/**
 * The resolved audio configuration the playback path runs with (`options.audio`).
 * `volumePercent` is Yomitan's 0-100 `volume`; [volumeFraction] is the value MediaPlayer wants.
 */
data class AudioPreferences(
    val enabled: Boolean = true,
    val autoPlay: Boolean = false,
    val volumePercent: Int = 100,
    val sources: List<AudioSourceConfig> = emptyList(),
    val enableDefaultAudioSources: Boolean = true,
    val fallbackSoundType: String = AudioFallbackSound.CLICK,
) {
    val volumeFraction: Float get() = volumePercent.coerceIn(0, 100) / 100f

    companion object {
        val DEFAULT = AudioPreferences()
    }
}

/**
 * A single playable candidate: a resolved URL plus provenance for the source menu.
 *
 * [sourceIndex] positions the candidate within the *effective* source list (configured sources
 * first, appended language defaults after) and is what lets the picker group samples back under
 * the source they came from, in order. [sourceLabel] is that source's display name.
 */
data class AudioCandidate(
    val url: String,
    val name: String? = null,
    val sourceType: String,
    val sourceIndex: Int = -1,
    val sourceLabel: String = "",
)

/**
 * The full resolution for one lookup: the ordered effective source list (so the picker can also
 * show sources that yielded nothing) plus the playable candidates in that same order.
 */
data class AudioResolution(
    val sources: List<AudioSourceConfig>,
    val candidates: List<AudioCandidate>,
)
// KUN <--
