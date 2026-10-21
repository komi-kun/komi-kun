// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported/adapted from Chimahon's `chimahon/anki/AnkiCardCreator.kt` (GPL-3.0). The marker
// rendering lives in [AnkiMarkerEngine]; this file owns the card-building flow of
// `docs/design/yomitan-spec/06-anki.md` §(b): field-map parsing, media requirements, the
// duplicate gate and the AnkiDroid bridge call. Manga-only: word audio and real sentence audio
// are dropped, dictionary media and sentence furigana come in through [AnkiDictionaryAccess].
package mihon.core.dictionary.anki

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.LookupResult
import java.security.MessageDigest

/** Outcome of an Anki mining attempt (`AnkiResult`). */
sealed class AnkiResult {
    data class Success(
        val noteId: Long,
        val mediaWarnings: List<AnkiMediaWarning> = emptyList(),
    ) : AnkiResult()

    data class CardExists(val noteId: Long) : AnkiResult()

    data class OpenCard(val noteId: Long) : AnkiResult()

    data class Error(val message: String) : AnkiResult()

    data object PermissionDenied : AnkiResult()

    data object NotConfigured : AnkiResult()
}

/**
 * Read-only dictionary access the card builder needs but does not own: sentence furigana scanning
 * and dictionary-media bytes. Injected by the app layer (which owns the native session).
 */
interface AnkiDictionaryAccess {
    /** Look up the dictionary term starting at the given sentence suffix. */
    fun lookupSuffix(suffix: String): List<LookupResult>

    /** Content bytes for one `{dictionary, path}` media reference, or null if absent. */
    fun mediaBytes(dictionary: String, path: String): ByteArray?
}

object AnkiCardCreator {

    private const val TAG = "AnkiCardCreator"
    private const val SCREENSHOT_MEDIA_PLACEHOLDER = "komikun_screenshot_pending.webp"
    private const val WORD_AUDIO_MEDIA_PLACEHOLDER = "komikun_word_audio_pending.mp3"
    private const val SENTENCE_AUDIO_MEDIA_PLACEHOLDER = "komikun_sentence_audio_pending.m4a"

    private val duplicateGate = AnkiDuplicateGate()

    @Volatile
    internal var bridgeFactory: (Context) -> AnkiCardBridge = ::AnkiDroidBridge

    internal fun resetBridgeFactoryForTests() {
        bridgeFactory = ::AnkiDroidBridge
    }

    // =====================================================================================
    // Public API
    // =====================================================================================

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    suspend fun addToAnki(
        context: Context,
        result: LookupResult,
        profile: AnkiProfile,
        sentence: String = "",
        offset: Int = -1,
        media: AnkiMediaInfo? = null,
        screenshotBytes: ByteArray? = null,
        glossaryIndex: Int? = null,
        selection: String? = null,
        selectedDict: String? = null,
        popupSelection: String? = null,
        styles: List<DictionaryStyle> = emptyList(),
        clipboardText: String? = null,
        clipboardImageFilename: String? = null,
        documentTitle: String = "",
        url: String = "",
        searchQuery: String = "",
        forceOpen: Boolean = false,
        dictionaryAccess: AnkiDictionaryAccess? = null,
        bridge: AnkiCardBridge? = null,
    ): AnkiResult {
        val deck = profile.ankiDeck
        val model = profile.ankiModel
        val fieldMapJson = profile.ankiFieldMap
        val tags = profile.ankiTags
        val dupCheck = profile.ankiDupCheck
        val dupScope = profile.ankiDupScope
        val dupAction = profile.ankiDupAction

        val activeBridge = bridge ?: bridgeFactory(context)
        if (!activeBridge.hasPermission()) return AnkiResult.PermissionDenied

        return try {
            val effectiveDeck = deck.ifBlank { activeBridge.ensureDefaultDeckName() }
            val effectiveModel = if (model.isBlank()) activeBridge.ensureLapisModelName() else model
            val effectiveFieldMapJson = if (LapisPreset.isBundledModelName(effectiveModel) && LapisPreset.isBlankFieldMap(fieldMapJson)) {
                LapisPreset.defaultFieldMapJson
            } else {
                fieldMapJson
            }
            if (effectiveDeck.isBlank() || effectiveModel.isBlank()) return AnkiResult.NotConfigured

            val fieldMap = AnkiMarkerEngine.parseFieldMap(effectiveFieldMapJson)
            val cloze = if (sentence.isNotEmpty() && offset >= 0) {
                val boldTarget = selection?.takeIf { it.isNotEmpty() } ?: result.matched
                AnkiMarkerEngine.buildCloze(sentence, offset, result.term.expression, result.term.reading, boldTarget)
            } else {
                null
            }

            val hasWordAudioMarker = fieldMap.values.any { it.contains("{${Marker.WORD_AUDIO}}") || it.contains("{${Marker.AUDIO}}") }
            val hasScreenshotMarker = fieldMap.values.any { it.contains("{${Marker.SCREENSHOT}}") }
            val hasSentenceAudioMarker = fieldMap.values.any { it.contains("{${Marker.SENTENCE_AUDIO}}") }
            val hasSentenceFurigana = fieldMap.values.any {
                it.contains("{${Marker.SENTENCE_FURIGANA}}") || it.contains("{${Marker.SENTENCE_FURIGANA_PLAIN}}")
            }

            val lockKey = "${dupScope.lowercase()}|${if (dupScope == "deck") effectiveDeck else ""}|${result.term.expression}"
            suspend fun duplicateDecision(): AnkiDuplicateDecision {
                if (!dupCheck && !forceOpen) return AnkiDuplicateDecision.Insert
                val targetDeckId = if (dupScope == "deck" && effectiveDeck.isNotBlank()) {
                    runCatching { activeBridge.getDeckId(effectiveDeck) }.getOrNull()
                } else {
                    null
                }
                val existing = activeBridge.findNotes(result.term.expression, null, targetDeckId)
                if (existing.isEmpty()) return AnkiDuplicateDecision.Insert
                val noteId = existing.first()
                return when {
                    forceOpen -> AnkiDuplicateDecision.Return(AnkiResult.OpenCard(noteId))
                    dupAction == "prevent" -> AnkiDuplicateDecision.Return(AnkiResult.CardExists(noteId))
                    dupAction == "open" -> AnkiDuplicateDecision.Return(AnkiResult.OpenCard(noteId))
                    dupAction == "overwrite" -> AnkiDuplicateDecision.Overwrite(noteId)
                    else -> AnkiDuplicateDecision.Insert
                }
            }

            val firstDecision = duplicateGate.preflight(lockKey, ::duplicateDecision)
            if (firstDecision is AnkiDuplicateDecision.Return) return firstDecision.result

            val sentenceAudioPreparation = prepareSentenceAudioForMarker(hasSentenceAudioMarker, null)
            val exportMedia = AnkiDictionaryMediaContext()
            val baseContext = AnkiMarkerContext(
                term = result.term,
                process = result.process,
                cloze = cloze,
                media = media,
                glossaryIndex = glossaryIndex,
                selectedDict = selectedDict,
                popupSelection = popupSelection,
                clipboardText = clipboardText,
                clipboardImageFilename = clipboardImageFilename,
                screenshotFilename = SCREENSHOT_MEDIA_PLACEHOLDER.takeIf { hasScreenshotMarker },
                wordAudioFilename = null,
                sentenceAudioFilename = SENTENCE_AUDIO_MEDIA_PLACEHOLDER.takeIf { hasSentenceAudioMarker },
                documentTitle = documentTitle,
                url = blankExtensionUrl(url),
                searchQuery = searchQuery,
                selectionText = selection,
                styles = styles,
                exportMedia = exportMedia,
            ).let { ctx ->
                if (hasSentenceFurigana && cloze != null && dictionaryAccess != null) {
                    ctx.copy(
                        sentenceFuriganaHtml = buildSentenceFuriganaHtml(cloze.sentence, dictionaryAccess),
                        sentenceFuriganaPlain = buildSentenceFuriganaPlain(cloze.sentence, dictionaryAccess),
                    )
                } else {
                    ctx
                }
            }

            val fieldsWithPlaceholders = withContext(Dispatchers.Default) {
                fieldMap.mapValues { (_, template) -> AnkiMarkerEngine.render(template, baseContext) }
            }
            val tagsWithPlaceholders = withContext(Dispatchers.Default) {
                tags.split(",").map { template -> AnkiMarkerEngine.render(template, baseContext) }
            }

            val preparedMedia = PreparedAnkiCardMedia(
                screenshot = screenshotBytes?.let { PreparedAnkiMediaPayload(generateScreenshotFilename(it), it) },
                wordAudio = null,
                dictionaryMedia = prepareDictionaryMedia(exportMedia, dictionaryAccess),
                sentenceAudio = sentenceAudioPreparation,
            )

            duplicateGate.commit(lockKey, ::duplicateDecision) { finalDecision ->
                if (finalDecision is AnkiDuplicateDecision.Return) {
                    return@commit finalDecision.result
                }
                val committedMedia = commitPreparedAnkiCardMedia(
                    prepared = preparedMedia,
                    storeBytes = activeBridge::storeMedia,
                    storeSentenceAudio = activeBridge::storeMedia,
                )
                val fields = resolveMediaPlaceholders(
                    resolveDictionaryMediaPlaceholders(fieldsWithPlaceholders, committedMedia.dictionaryReplacementByPlaceholder),
                    committedMedia,
                )
                val tagList = tagsWithPlaceholders
                    .map { value -> resolveMediaPlaceholders(value, committedMedia) }
                    .mapNotNull(::normalizeAnkiTag)

                val noteId = when (finalDecision) {
                    is AnkiDuplicateDecision.Overwrite -> {
                        // KUN --> Granular per-field merging support
                        val existingFields = activeBridge.getNoteFields(finalDecision.noteId)
                        val mergedFields = if (existingFields.isNotEmpty() && profile.ankiFieldOverwriteModes.isNotEmpty()) {
                            fields.mapValues { (fieldName, newValue) ->
                                val existingValue = existingFields[fieldName] ?: ""
                                val mode = profile.ankiFieldOverwriteModes[fieldName] ?: "overwrite"
                                mergeFieldValue(existingValue, newValue, mode)
                            }
                        } else {
                            fields
                        }
                        activeBridge.updateNoteFields(finalDecision.noteId, mergedFields)
                        // KUN <--
                        finalDecision.noteId
                    }

                    AnkiDuplicateDecision.Insert -> activeBridge.addNote(
                        deckName = effectiveDeck,
                        modelName = effectiveModel,
                        fields = fields,
                        tags = tagList,
                    )

                    is AnkiDuplicateDecision.Return -> error("handled above")
                }
                if (profile.ankiSyncOnCreate) activeBridge.triggerSync()
                AnkiResult.Success(noteId, committedMedia.sentenceAudio.warnings)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AnkiResult.PermissionDenied
        } catch (e: Exception) {
            Log.w(TAG, "addToAnki failed", e)
            AnkiResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun checkExistingCards(
        context: Context,
        expressions: List<String>,
        deckName: String = "",
        dupScope: String = "collection",
        bridge: AnkiCardBridge? = null,
    ): Set<String> = checkExistingCardIds(context, expressions, deckName, dupScope, bridge).keys

    suspend fun checkExistingCardIds(
        context: Context,
        expressions: List<String>,
        deckName: String = "",
        dupScope: String = "collection",
        bridge: AnkiCardBridge? = null,
    ): Map<String, Long> {
        val existing = linkedMapOf<String, Long>()
        val activeBridge = bridge ?: bridgeFactory(context)
        if (!activeBridge.hasPermission()) return existing
        val targetDeckId = if (dupScope == "deck" && deckName.isNotBlank()) {
            runCatching { activeBridge.getDeckId(deckName) }.getOrNull()
        } else {
            null
        }
        for (expr in expressions.distinct()) {
            runCatching { activeBridge.findNotes(expr, null, targetDeckId) }
                .getOrNull()
                ?.firstOrNull()
                ?.let { existing[expr] = it }
        }
        return existing
    }

    suspend fun findExistingCardId(
        context: Context,
        expression: String,
        deckName: String = "",
        dupScope: String = "collection",
        bridge: AnkiCardBridge? = null,
    ): Long? {
        val activeBridge = bridge ?: bridgeFactory(context)
        if (!activeBridge.hasPermission()) return null
        val targetDeckId = if (dupScope == "deck" && deckName.isNotBlank()) {
            runCatching { activeBridge.getDeckId(deckName) }.getOrNull()
        } else {
            null
        }
        return runCatching { activeBridge.findNotes(expression, null, targetDeckId).firstOrNull() }.getOrNull()
    }

    /** Parse a stored field-map JSON string into field → template (`parseFieldMap` in Chimahon). */
    fun parseFieldMap(json: String): Map<String, String> = AnkiMarkerEngine.parseFieldMap(json)

    // =====================================================================================
    // Sentence furigana (`builder:515-532`)
    // =====================================================================================

    fun buildSentenceFuriganaHtml(sentence: String, access: AnkiDictionaryAccess): String {
        if (sentence.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < sentence.length) {
            val suffix = sentence.substring(i)
            val best = bestMatch(suffix, access)
            if (best != null) {
                sb.append(AnkiMarkerEngine.buildFuriganaHtml(best.matched, best.term.reading))
                i += best.matched.length
            } else {
                sb.append(AnkiMarkerEngine.escapeHtml(sentence[i].toString()))
                i++
            }
        }
        return sb.toString()
    }

    fun buildSentenceFuriganaPlain(sentence: String, access: AnkiDictionaryAccess): String {
        if (sentence.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < sentence.length) {
            val suffix = sentence.substring(i)
            val best = bestMatch(suffix, access)
            if (best != null) {
                sb.append(AnkiMarkerEngine.buildFuriganaPlain(best.matched, best.term.reading))
                i += best.matched.length
            } else {
                sb.append(sentence[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun bestMatch(suffix: String, access: AnkiDictionaryAccess): LookupResult? = runCatching {
        access.lookupSuffix(suffix)
            .filter { suffix.startsWith(it.matched) }
            .maxByOrNull { it.matched.length }
    }.getOrNull()

    // =====================================================================================
    // Media
    // =====================================================================================

    private fun prepareDictionaryMedia(
        exportMedia: AnkiDictionaryMediaContext,
        access: AnkiDictionaryAccess?,
    ): List<PreparedDictionaryMedia> {
        val references = exportMedia.references
        if (references.isEmpty()) return emptyList()
        return references.map { reference ->
            val bytes = access?.mediaBytes(reference.dictionary, reference.path)
            val payload = bytes?.let { PreparedAnkiMediaPayload(dictionaryMediaFilename(reference.path, it), it) }
            PreparedDictionaryMedia(reference.placeholder, payload)
        }
    }

    private fun resolveDictionaryMediaPlaceholders(
        fields: Map<String, String>,
        replacements: Map<String, String>,
    ): Map<String, String> = fields.mapValues { (_, value) ->
        replacements.entries.fold(value) { current, (placeholder, replacement) -> current.replace(placeholder, replacement) }
    }

    private fun resolveMediaPlaceholders(value: String, media: CommittedAnkiCardMedia): String =
        value
            .resolveImagePlaceholder(SCREENSHOT_MEDIA_PLACEHOLDER, media.screenshotFilename)
            .resolveSoundPlaceholder(WORD_AUDIO_MEDIA_PLACEHOLDER, media.wordAudioFilename)
            .resolveSoundPlaceholder(SENTENCE_AUDIO_MEDIA_PLACEHOLDER, media.sentenceAudio.filename)

    private fun resolveMediaPlaceholders(fields: Map<String, String>, media: CommittedAnkiCardMedia): Map<String, String> =
        fields.mapValues { (_, value) -> resolveMediaPlaceholders(value, media) }

    private fun String.resolveImagePlaceholder(placeholder: String, filename: String?): String =
        if (filename == null) replace("<img src=\"$placeholder\" />", "").replace(placeholder, "") else replace(placeholder, filename)

    private fun String.resolveSoundPlaceholder(placeholder: String, filename: String?): String =
        if (filename == null) replace("[sound:$placeholder]", "").replace(placeholder, "") else replace(placeholder, filename)

    fun generateScreenshotFilename(bytes: ByteArray): String {
        val hash = sha1Prefix(bytes)
        val extension = if (isAvif(bytes)) "avif" else "webp"
        return "komikun_$hash.$extension"
    }

    fun generateSentenceAudioFilename(bytes: ByteArray, extension: String): String =
        "komikun_sentence_${sha1Prefix(bytes)}.${AnkiSentenceAudioSource.safeExtension(extension, "m4a")}"

    fun dictionaryMediaFilename(path: String, bytes: ByteArray): String {
        val extension = path.substringBefore('?')
            .substringAfterLast('.', "png")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .ifBlank { "png" }
            .lowercase()
        return "komikun_dict_${sha1Prefix(bytes)}.$extension"
    }

    private fun sha1Prefix(bytes: ByteArray): String = try {
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)
    } catch (_: Exception) {
        System.currentTimeMillis().toString()
    }

    private fun isAvif(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() &&
            bytes[8] == 'a'.code.toByte() && bytes[9] == 'v'.code.toByte() &&
            bytes[10] == 'i'.code.toByte() && bytes[11] == 's'.code.toByte()

    fun normalizeAnkiTag(value: String): String? = value.trim()
        .replace(Regex("\\s+"), "_")
        .takeIf { it.isNotBlank() }

    /** Blank a `chrome-extension:` context URL (`builder:83-91`). */
    fun blankExtensionUrl(url: String): String = if (url.startsWith("chrome-extension:")) "" else url

    // KUN -->
    /** Merges an existing card field value with a new field value according to [mode]. */
    internal fun mergeFieldValue(existing: String, new: String, mode: String): String {
        if (new.isBlank()) return existing
        if (existing.isBlank()) return new
        return when (mode.lowercase()) {
            "append" -> "$existing<br>$new"
            "prepend" -> "$new<br>$existing"
            "if_empty", "ifempty" -> existing
            "skip" -> existing
            "overwrite" -> new
            else -> new
        }
    }
    // KUN <--
}
// KUN <--
