// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported/adapted from Chimahon's `chimahon/anki/AnkiDroidBridge.kt` (GPL-3.0). Talks to AnkiDroid
// through its `com.ichi2.anki.flashcards` ContentProvider (notes_v2 csum duplicate probe, notes
// insert + moveCardsToDeck, models/decks, media via FileProvider). The pure helpers
// (`fieldChecksum`, `stripHTML`, `parseFieldNames`, `sanitizePreferredBaseName`) avoid
// `android.text.Html`/`org.json` so they are JVM-testable (plan 05 §A3).
package mihon.core.dictionary.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The card bridge the builder talks to; faked in tests (plan 05 §B7 step 6). */
interface AnkiCardBridge {
    fun hasPermission(): Boolean

    suspend fun ensureDefaultDeckName(): String

    suspend fun ensureLapisModelName(): String

    suspend fun getDeckId(deckName: String): Long

    suspend fun findNotes(expression: String, modelName: String? = null, deckId: Long? = null): List<Long>

    suspend fun addNote(deckName: String, modelName: String, fields: Map<String, String>, tags: List<String>): Long

    suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>): Any?

    // KUN -->
    suspend fun getNoteFields(noteId: Long): Map<String, String> = emptyMap()
    // KUN <--

    suspend fun storeMedia(filename: String, data: ByteArray): String

    suspend fun storeMedia(source: AnkiSentenceAudioSource): String

    fun triggerSync()
}

class AnkiDroidBridge(private val context: Context) : AnkiCardBridge {

    companion object {
        private const val TAG = "AnkiDroidBridge"
        private const val SYNC_COOLDOWN_MS = 120_000L
        private const val ANKI_PACKAGE = "com.ichi2.anki"

        @Volatile
        private var lastSyncTimeMs = 0L

        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        const val PERMISSION_REQUEST_CODE = 2001

        private const val AUTHORITY = "com.ichi2.anki.flashcards"
        private val BASE_URI by lazy { Uri.parse("content://$AUTHORITY") }
        private val NOTES_URI by lazy { Uri.withAppendedPath(BASE_URI, "notes") }
        private val NOTES_V2_URI by lazy { Uri.withAppendedPath(BASE_URI, "notes_v2") }
        private val MODELS_URI by lazy { Uri.withAppendedPath(BASE_URI, "models") }
        private val DECKS_URI by lazy { Uri.withAppendedPath(BASE_URI, "decks") }
        private val MEDIA_URI by lazy { Uri.withAppendedPath(BASE_URI, "media") }

        private const val NOTE_ID = "_id"
        private const val NOTE_MID = "mid"
        private const val NOTE_FLDS = "flds"
        private const val NOTE_TAGS = "tags"
        private const val NOTE_CSUM = "csum"

        private const val MODEL_ID = "_id"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"

        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"

        private const val MEDIA_FILE_URI = "file_uri"
        private const val MEDIA_PREFERRED_NAME = "preferred_name"

        internal const val FIELD_SEPARATOR = "\u001f"

        // ======================================================================
        // Pure helpers (JVM-testable; no Context/org.json/android.text.Html)
        // ======================================================================

        private val STYLE_PATTERN = Regex("(?s)<style.*?>.*?</style>")
        private val SCRIPT_PATTERN = Regex("(?s)<script.*?>.*?</script>")
        private val TAG_PATTERN = Regex("<.*?>")
        private val IMG_PATTERN = Regex("<img src=[\"']?([^\"'>]+)[\"']? ?/?>")
        private val ENTITY_PATTERN = Regex("&#?\\w+;")

        private val NAMED_ENTITIES = mapOf(
            "nbsp" to " ",
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "39" to "'",
            "copy" to "©",
            "reg" to "®",
        )

        fun splitFields(str: String): List<String> = str.split(FIELD_SEPARATOR)

        /** AnkiDroid's `Utils.stripHTMLMedia` + SHA-1 → first 8 hex (duplicate-probe parity). */
        fun fieldChecksum(data: String): Long {
            val strippedData = stripHTMLMedia(data)
            return try {
                val digest = MessageDigest.getInstance("SHA-1").digest(strippedData.toByteArray(StandardCharsets.UTF_8))
                val hex = BigInteger(1, digest).toString(16).padStart(40, '0')
                hex.substring(0, 8).toLong(16)
            } catch (e: Exception) {
                Log.e(TAG, "Error making field checksum", e)
                0L
            }
        }

        fun entsToTxt(htmlText: String): String {
            val htmlReplaced = htmlText.replace("&nbsp;", " ")
            return ENTITY_PATTERN.replace(htmlReplaced) { match -> decodeEntity(match.value) ?: match.value }
        }

        private fun decodeEntity(entity: String): String? {
            val body = entity.removePrefix("&").removeSuffix(";")
            if (body.startsWith("#")) {
                val number = if (body.startsWith("#x") || body.startsWith("#X")) {
                    body.substring(2).toIntOrNull(16)
                } else {
                    body.substring(1).toIntOrNull()
                }
                return number?.let { codePoint -> String(Character.toChars(codePoint)) }
            }
            return NAMED_ENTITIES[body]
        }

        fun stripHTML(s: String): String {
            var strRep = STYLE_PATTERN.replace(s, "")
            strRep = SCRIPT_PATTERN.replace(strRep, "")
            strRep = TAG_PATTERN.replace(strRep, "")
            return entsToTxt(strRep)
        }

        fun stripHTMLMedia(s: String): String {
            val replacedImg = IMG_PATTERN.replace(s) { match -> " ${match.groupValues[1]} " }
            return stripHTML(replacedImg)
        }

        fun sanitizePreferredBaseName(value: String): String = value
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .take(96)
            .ifBlank { "komikun_media" }

        /** Parse AnkiDroid `field_names`: a JSON array of names/objects, or a `\u001f`-joined list. */
        fun parseFieldNames(rawData: String): List<String> {
            val trimmed = rawData.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val parsed = runCatching {
                    (AnkiJson.parseToJsonElement(trimmed) as? JsonArray)?.map { item ->
                        val obj = item as? JsonObject
                        if (obj != null) {
                            obj["name"]?.jsonPrimitive?.contentOrNull ?: obj.toString()
                        } else {
                            item.jsonPrimitive.contentOrNull ?: item.toString()
                        }
                    }
                }.getOrNull()
                if (parsed != null) return parsed
            }
            return splitFields(rawData)
        }
    }

    // ==========================================================================
    // Public API
    // ==========================================================================

    suspend fun isAnkiDroidInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            context.packageManager.getPackageInfo(ANKI_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: android.app.Activity) {
        activity.requestPermissions(arrayOf(PERMISSION), PERMISSION_REQUEST_CODE)
    }

    suspend fun deckNames(): List<String> = queryColumn(DECKS_URI, DECK_NAME)

    suspend fun modelNames(): List<String> = queryColumn(MODELS_URI, MODEL_NAME)

    suspend fun modelFieldNames(modelName: String): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val result = mutableListOf<String>()
        try {
            context.contentResolver.query(MODELS_URI, arrayOf(MODEL_NAME, MODEL_FIELD_NAMES), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val dbModelName = c.getString(0) ?: continue
                    if (dbModelName != modelName) continue
                    val rawData = c.getString(1) ?: continue
                    val parsed = parseFieldNames(rawData)
                    if (parsed.size > result.size) {
                        result.clear()
                        result.addAll(parsed)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "modelFieldNames", e)
        }
        result
    }

    override suspend fun ensureDefaultDeckName(): String = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ""
        val defaultName = LapisPreset.DEFAULT_DECK_NAME
        findDeckIdOrNull(defaultName) ?: createDeck(defaultName)
        defaultName
    }

    override suspend fun ensureLapisModelName(): String = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ""
        // komi-kun ships no bundled note type (06-anki.md §d): only return an existing Lapis model.
        if (readModelInfos().any { it.name == LapisPreset.MODEL_NAME }) LapisPreset.MODEL_NAME else ""
    }

    override suspend fun getDeckId(deckName: String): Long = withContext(Dispatchers.IO) {
        findDeckId(deckName)
    }

    override suspend fun findNotes(expression: String, modelName: String?, deckId: Long?): List<Long> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            val ids = mutableListOf<Long>()
            try {
                val csum = fieldChecksum(expression)
                context.contentResolver.query(
                    NOTES_V2_URI,
                    arrayOf(NOTE_ID, NOTE_MID),
                    "$NOTE_CSUM=?",
                    arrayOf(csum.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val nid = c.getLong(0)
                        if (deckId != null && !isNoteInDeck(nid, deckId)) continue
                        ids.add(nid)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "findNotes", e)
            }
            ids
        }

    override suspend fun addNote(
        deckName: String,
        modelName: String,
        fields: Map<String, String>,
        tags: List<String>,
    ): Long = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw Exception("AnkiDroid permission not granted")
        val deckId = findDeckId(deckName)
        val modelId = findModelId(modelName)
        val fieldNames = getModelFields(modelId)
        val values = Array(fieldNames.size) { i -> fields[fieldNames[i]] ?: "" }
        val tagSet = tags.toMutableSet()
        val cv = ContentValues().apply {
            put(NOTE_MID, modelId)
            put(NOTE_FLDS, values.joinToString(FIELD_SEPARATOR))
            if (tagSet.isNotEmpty()) put(NOTE_TAGS, tagSet.joinToString(" "))
        }
        val result = runCatching { context.contentResolver.insert(NOTES_URI, cv) }.getOrNull()
            ?: throw Exception("AnkiDroid insert failed")
        val newNoteId = result.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse note ID from insert result")
        moveCardsToDeck(newNoteId, deckId)
        newNoteId
    }

    override suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>) =
        withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(NOTES_URI, noteId.toString())
            context.contentResolver.query(uri, arrayOf(NOTE_MID, NOTE_FLDS), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@withContext
                val modelId = c.getLong(0)
                val oldFields = splitFields(c.getString(1))
                val fieldNames = getModelFields(modelId)
                val newFields = Array(fieldNames.size) { i ->
                    when {
                        fields.containsKey(fieldNames[i]) -> fields[fieldNames[i]]!!
                        i < oldFields.size -> oldFields[i]
                        else -> ""
                    }
                }
                val cv = ContentValues().apply { put(NOTE_FLDS, newFields.joinToString(FIELD_SEPARATOR)) }
                context.contentResolver.update(uri, cv, null, null)
            }
        }

    // KUN -->
    override suspend fun getNoteFields(noteId: Long): Map<String, String> =
        withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(NOTES_URI, noteId.toString())
            context.contentResolver.query(uri, arrayOf(NOTE_MID, NOTE_FLDS), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@withContext emptyMap()
                val modelId = c.getLong(0)
                val oldFields = splitFields(c.getString(1))
                val fieldNames = getModelFields(modelId)
                fieldNames.mapIndexed { i, name ->
                    name to (if (i < oldFields.size) oldFields[i] else "")
                }.toMap()
            } ?: emptyMap()
        }
    // KUN <--

    fun guiBrowse(query: String) {
        try {
            val uri = Uri.parse("anki://x-callback-url/browser?search=${Uri.encode(query)}")
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { setPackage(ANKI_PACKAGE) })
        } catch (e: Exception) {
            Log.w(TAG, "guiBrowse", e)
        }
    }

    fun guiEditNote(noteId: Long) {
        // AnkiDroid's NoteEditor has no public intent filter for external apps; use the browser.
        guiBrowse("nid:$noteId")
    }

    override suspend fun storeMedia(filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        saveMediaBytes(filename, data)
    }

    override suspend fun storeMedia(source: AnkiSentenceAudioSource): String = withContext(Dispatchers.IO) {
        val baseName = sanitizePreferredBaseName(source.preferredBaseName)
        val extension = AnkiSentenceAudioSource.safeExtension(source.extension, "m4a")
        saveMediaBytes("$baseName.$extension", source.data)
    }

    suspend fun storeMediaFromBase64(filename: String, base64: String): String =
        storeMedia(filename, Base64.decode(base64, Base64.NO_WRAP))

    suspend fun storeMediaFromUrl(filename: String, urlString: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw Exception("HTTP ${conn.responseCode}")
            val buffer = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                var r: Int
                while (input.read(buf).also { r = it } != -1) buffer.write(buf, 0, r)
            }
            saveMediaBytes(filename, buffer.toByteArray())
        } finally {
            conn.disconnect()
        }
    }

    suspend fun storeMediaFromFile(filename: String, filePath: String): String = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) throw Exception("File not found: $filePath")
        saveMediaBytes(filename, file.readBytes())
    }

    override fun triggerSync() {
        try {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastSyncTimeMs
            if (elapsed < SYNC_COOLDOWN_MS) {
                Log.d(TAG, "triggerSync: skipped (cooldown remaining)")
                return
            }
            context.packageManager.getPackageInfo(ANKI_PACKAGE, 0)
            val intent = Intent("com.ichi2.anki.DO_SYNC").apply {
                setPackage(ANKI_PACKAGE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            lastSyncTimeMs = now
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "triggerSync: AnkiDroid not installed")
        } catch (e: Exception) {
            Log.w(TAG, "triggerSync: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ==========================================================================
    // Internal helpers
    // ==========================================================================

    private suspend fun queryColumn(uri: Uri, column: String): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val names = mutableListOf<String>()
        try {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let(names::add)
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryColumn($column)", e)
        }
        names
    }

    private fun findDeckId(deckName: String): Long =
        findDeckIdOrNull(deckName) ?: throw Exception("Deck '$deckName' not found")

    private fun findDeckIdOrNull(deckName: String): Long? {
        try {
            context.contentResolver.query(DECKS_URI, arrayOf(DECK_ID, DECK_NAME), null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DECK_ID)
                val nameIdx = c.getColumnIndex(DECK_NAME)
                if (idIdx == -1 || nameIdx == -1) throw Exception("Missing deck columns")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == deckName) return c.getLong(idIdx)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findDeckId failed", e)
        }
        return null
    }

    private fun createDeck(deckName: String): Long {
        val cv = ContentValues().apply { put(DECK_NAME, deckName) }
        val uri = context.contentResolver.insert(DECKS_URI, cv) ?: throw Exception("Failed to create deck '$deckName'")
        return uri.lastPathSegment?.toLongOrNull() ?: throw Exception("Failed to parse deck ID for '$deckName'")
    }

    private fun findModelId(modelName: String): Long {
        try {
            context.contentResolver.query(MODELS_URI, arrayOf(MODEL_ID, MODEL_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == modelName) return c.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findModelId failed", e)
        }
        throw Exception("Model '$modelName' not found")
    }

    private fun getModelFields(modelId: Long): List<String> {
        try {
            context.contentResolver.query(MODELS_URI, arrayOf(MODEL_ID, MODEL_FIELD_NAMES), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) == modelId) return parseFieldNames(c.getString(1) ?: continue)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getModelFields failed", e)
        }
        throw Exception("Model fields not found for ID: $modelId")
    }

    private data class ModelInfo(val name: String, val fields: List<String>)

    private fun readModelInfos(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        try {
            context.contentResolver.query(MODELS_URI, arrayOf(MODEL_NAME, MODEL_FIELD_NAMES), null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(MODEL_NAME)
                val fieldsIdx = c.getColumnIndex(MODEL_FIELD_NAMES)
                if (nameIdx == -1 || fieldsIdx == -1) return@use
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    models += ModelInfo(name, parseFieldNames(c.getString(fieldsIdx) ?: ""))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readModelInfos failed", e)
        }
        return models
    }

    private fun moveCardsToDeck(noteId: Long, targetDeckId: Long) {
        val cardsUri = Uri.withAppendedPath(NOTES_URI, "$noteId/cards")
        try {
            context.contentResolver.query(cardsUri, arrayOf("ord", "deck_id"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val ord = c.getInt(0)
                    if (c.getLong(1) != targetDeckId) {
                        val cardUri = Uri.withAppendedPath(cardsUri, ord.toString())
                        context.contentResolver.update(cardUri, ContentValues().apply { put("deck_id", targetDeckId) }, null, null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "moveCardsToDeck", e)
        }
    }

    private fun saveMediaBytes(name: String, data: ByteArray): String {
        val mediaDir = File(context.getExternalFilesDir(null), "anki_media")
        mediaDir.mkdirs()
        val file = File(mediaDir, name)
        file.writeBytes(data)
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        context.grantUriPermission(ANKI_PACKAGE, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val mediaCv = ContentValues().apply {
            put(MEDIA_FILE_URI, contentUri.toString())
            put(MEDIA_PREFERRED_NAME, name.replace(Regex("\\.[^.]*$"), ""))
        }
        val res = context.contentResolver.insert(MEDIA_URI, mediaCv)
        file.delete()
        if (res != null) return File(res.path!!).name
        throw Exception("AnkiDroid failed to copy the media")
    }

    private fun isNoteInDeck(noteId: Long, deckId: Long): Boolean {
        val cardsUri = Uri.withAppendedPath(Uri.withAppendedPath(NOTES_URI, noteId.toString()), "cards")
        var inDeck = false
        try {
            context.contentResolver.query(cardsUri, arrayOf("deck_id"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) == deckId) {
                        inDeck = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "isNoteInDeck", e)
        }
        return inDeck
    }

    // Pure helpers live in the companion object (JVM-testable without a Context).
}
// KUN <--
