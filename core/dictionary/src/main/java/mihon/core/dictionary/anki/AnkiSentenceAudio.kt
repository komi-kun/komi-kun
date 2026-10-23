// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Adapted from Chimahon's `chimahon/anki/AnkiSentenceAudio.kt` (GPL-3.0). Manga has no audio
// source, so only the marker plumbing + a `null` provider survive (plan 05 §B4): `{sentence-audio}`
// renders `""` and its `Unavailable` types never crash the marker engine.
package mihon.core.dictionary.anki

import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.Locale

/** A byte-backed audio file with the filename frozen before it reaches Anki. */
data class AnkiSentenceAudioSource(
    val data: ByteArray,
    val preferredBaseName: String,
    val extension: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnkiSentenceAudioSource) return false
        return data.contentEquals(other.data) &&
            preferredBaseName == other.preferredBaseName &&
            extension == other.extension
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + preferredBaseName.hashCode()
        result = 31 * result + extension.hashCode()
        return result
    }

    companion object {
        fun fromBytes(data: ByteArray, extension: String): AnkiSentenceAudioSource {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(data)
                .joinToString("") { "%02x".format(it) }
            return AnkiSentenceAudioSource(
                data = data,
                preferredBaseName = "komikun_sentence_$hash",
                extension = safeExtension(extension, "m4a"),
            )
        }

        fun safeExtension(value: String, fallback: String): String {
            val candidate = value.substringBefore('?')
            return candidate
                .substringAfterLast('.', candidate)
                .replace(Regex("[^A-Za-z0-9]"), "")
                .ifBlank { fallback }
                .lowercase(Locale.ROOT)
        }
    }
}

enum class AnkiSentenceAudioFailure {
    SOURCE_UNAVAILABLE,
    EXTRACTION_FAILED,
    UNKNOWN,
}

sealed interface AnkiSentenceAudioPreparation {
    data class Ready(val source: AnkiSentenceAudioSource) : AnkiSentenceAudioPreparation

    data class Unavailable(val failure: AnkiSentenceAudioFailure) : AnkiSentenceAudioPreparation
}

fun interface LazyAnkiSentenceAudioProvider {
    suspend fun prepare(): AnkiSentenceAudioPreparation
}

data class AnkiMediaRequest(
    val sentenceAudioProvider: LazyAnkiSentenceAudioProvider? = null,
)

sealed interface AnkiMediaWarning {
    data class SentenceAudioGenerationFailed(val failure: AnkiSentenceAudioFailure) : AnkiMediaWarning

    data object SentenceAudioStorageFailed : AnkiMediaWarning
}

internal data class StoredSentenceAudio(
    val filename: String?,
    val warnings: List<AnkiMediaWarning> = emptyList(),
)

internal class AnkiSentenceAudioCommitter(
    private val store: suspend (AnkiSentenceAudioSource) -> String,
) {
    suspend fun store(preparation: AnkiSentenceAudioPreparation?): StoredSentenceAudio =
        when (preparation) {
            null -> StoredSentenceAudio(null)
            is AnkiSentenceAudioPreparation.Unavailable -> StoredSentenceAudio(
                filename = null,
                warnings = listOf(AnkiMediaWarning.SentenceAudioGenerationFailed(preparation.failure)),
            )

            is AnkiSentenceAudioPreparation.Ready -> try {
                StoredSentenceAudio(store(preparation.source))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                StoredSentenceAudio(null, listOf(AnkiMediaWarning.SentenceAudioStorageFailed))
            }
        }
}

internal suspend fun prepareSentenceAudioForMarker(
    hasSentenceAudioMarker: Boolean,
    provider: LazyAnkiSentenceAudioProvider?,
): AnkiSentenceAudioPreparation? {
    if (!hasSentenceAudioMarker || provider == null) return null
    return try {
        provider.prepare()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AnkiSentenceAudioPreparation.Unavailable(AnkiSentenceAudioFailure.UNKNOWN)
    }
}
// KUN <--
