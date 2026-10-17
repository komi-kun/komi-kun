// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.backup

import android.util.JsonReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Thrown when the picked file is not a Dexie export (or is otherwise unparseable). */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a Yomitan **Dexie/IndexedDB backup** (`yomitan-dictionaries-*.json`) into standard Yomitan
 * dictionary ZIPs that the existing "Import dictionary" engine path already consumes.
 *
 * Rows live in `data.data[]` — seven `{tableName, inbound, rows[]}` objects — not in `data.tables`
 * (a rowCount/schema manifest). Two row encodings exist and both are decoded:
 *  - anonymous-PK stores (`dictionaries/kanji/kanjiMeta/tagMeta/termMeta`): `{"$":[pk, value], …}`
 *  - flat named-`id` stores (`terms`/`media`): the value object itself.
 *
 * The whole file is streamed with a pull parser ([PullJsonReader]): memory is O(one row), never
 * O(file). The three PoC hazards are handled explicitly:
 *  1. `terms` rows are interleaved across dictionaries, so a [DictionaryStaging] stays open for the
 *     whole table and is only finalised once every table has been read.
 *  2. `media.content` is a base64 blob; it is decoded and written to a real file at `media.path`.
 *  3. The backup has no recoverable `styles.css`; an empty one is emitted.
 *
 * Unknown stores/keys and unknown `$types` markers are ignored; a malformed row is skipped and
 * counted rather than aborting the pass.
 */
class DexieBackupConverter(
    private val bankSize: Int = DEFAULT_BANK_SIZE,
    /**
     * Creates the pull-parser over the backup stream. Production defaults to the platform
     * [android.util.JsonReader]; tests inject a JVM source over a synthetic fixture.
     */
    private val readerFactory: (InputStream) -> PullJsonReader = { input ->
        AndroidPullJsonReader(
            JsonReader(BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8), READER_BUFFER)),
        )
    },
) {

    /**
     * @param shouldInclude evaluated per discovered dictionary title; return false to skip it (no
     *   banks written, no ZIP produced). Used for resume/idempotency.
     * @param onDictionaryReady invoked once per produced ZIP, before its staging dir is deleted;
     *   the caller imports it, then the converter frees the dir.
     */
    suspend fun convert(
        input: InputStream,
        stagingDir: File,
        shouldInclude: (title: String) -> Boolean = { true },
        onProgress: (BackupConversionProgress) -> Unit = {},
        onDictionaryReady: suspend (StagedDictionary) -> Unit = {},
    ): BackupConversionResult = Run(
        input = input,
        stagingDir = stagingDir,
        shouldInclude = shouldInclude,
        onProgress = onProgress,
        onDictionaryReady = onDictionaryReady,
        bankSize = bankSize,
        readerFactory = readerFactory,
    ).execute()

    private class Run(
        input: InputStream,
        stagingDir: File,
        private val shouldInclude: (title: String) -> Boolean,
        private val onProgress: (BackupConversionProgress) -> Unit,
        private val onDictionaryReady: suspend (StagedDictionary) -> Unit,
        bankSize: Int,
        readerFactory: (InputStream) -> PullJsonReader,
    ) {
        private val dirsRoot = File(stagingDir, "dirs").apply { mkdirs() }
        private val zipsRoot = File(stagingDir, "zips").apply { mkdirs() }
        private val stages = LinkedHashMap<String, DictionaryStaging>()
        private val usedSafeNames = HashSet<String>()
        private val skippedTitles = ArrayList<String>()

        private val bankSize = bankSize
        private val source: PullJsonReader = readerFactory(input)

        private var rowsRead = 0L
        private var totalRows = 0L

        suspend fun execute(): BackupConversionResult {
            try {
                onProgress(BackupConversionProgress.Sniffing)
                readRoot()
            } catch (e: BackupFormatException) {
                throw e
            } catch (e: Exception) {
                throw BackupFormatException("Could not parse the backup as JSON: ${e.message}", e)
            } finally {
                runCatching { source.close() }
            }

            for (staging in stages.values) staging.closeBanks()

            val total = stages.size
            var index = 0
            val staged = ArrayList<StagedDictionary>(total)
            for (staging in stages.values) {
                currentCoroutineContext().ensureActive()
                index++
                onProgress(BackupConversionProgress.Finalizing(index, total, staging.title))
                staging.writeIndexAndStyles()
                val zip = File(zipsRoot, "${staging.safeName}.zip")
                zipDirectory(staging.dir, zip)
                val stagedDictionary = StagedDictionary(staging.title, staging.safeName, zip)
                onDictionaryReady(stagedDictionary)
                staged += stagedDictionary
                staging.dir.deleteRecursively()
            }

            val malformed = stages.values.sumOf { it.malformedRowCount }
            return BackupConversionResult(
                staged = staged,
                skippedTitles = skippedTitles,
                rowsRead = rowsRead,
                totalRows = totalRows,
                malformedRows = malformed,
            )
        }

        // ---------------------------------------------------------------- JSON walk

        private fun readRoot() {
            source.beginObject()
            var sawFormat = false
            while (source.hasNext()) {
                when (source.nextName()) {
                    "formatName" -> {
                        sawFormat = true
                        val format = source.nextString()
                        if (format != "dexie") {
                            throw BackupFormatException("Not a Yomitan Dexie backup (formatName=\"$format\")")
                        }
                    }
                    "data" -> readDataObject()
                    else -> source.skipValue()
                }
            }
            source.endObject()
            if (!sawFormat) throw BackupFormatException("Not a Yomitan Dexie backup (no formatName)")
        }

        private fun readDataObject() {
            source.beginObject()
            while (source.hasNext()) {
                when (source.nextName()) {
                    "tables" -> readTablesManifest()
                    "data" -> readTableArray()
                    else -> source.skipValue()
                }
            }
            source.endObject()
        }

        private fun readTablesManifest() {
            source.beginArray()
            while (source.hasNext()) {
                source.beginObject()
                while (source.hasNext()) {
                    if (source.nextName() == "rowCount") {
                        totalRows += source.nextString().toLongOrNull() ?: 0L
                    } else {
                        source.skipValue()
                    }
                }
                source.endObject()
            }
            source.endArray()
        }

        private fun readTableArray() {
            source.beginArray()
            while (source.hasNext()) {
                source.beginObject()
                var tableName: String? = null
                while (source.hasNext()) {
                    when (source.nextName()) {
                        "tableName" -> tableName = source.nextString()
                        "rows" -> {
                            val name = tableName
                                ?: throw BackupFormatException("Unexpected table layout: `rows` before `tableName`")
                            streamRows(name)
                        }
                        else -> source.skipValue()
                    }
                }
                source.endObject()
            }
            source.endArray()
        }

        private fun streamRows(table: String) {
            source.beginArray()
            while (source.hasNext()) {
                val value = readValue()
                val row = (value as? Map<*, *>)?.let { unwrapRow(it) }
                if (row != null) {
                    rowsRead++
                    if (rowsRead % PROGRESS_EVERY == 0L) {
                        onProgress(BackupConversionProgress.Parsing(rowsRead, totalRows, table))
                    }
                    dispatchRow(table, row)
                }
            }
            source.endArray()
        }

        /** Decode the current token into plain Kotlin values (`Map`/`List`/`String`/`Long`/`Double`/…). */
        private fun readValue(): Any? = when (source.peek()) {
            JsonEvent.BEGIN_OBJECT -> {
                val map = LinkedHashMap<String, Any?>()
                source.beginObject()
                while (source.hasNext()) {
                    val name = source.nextName()
                    map[name] = readValue()
                }
                source.endObject()
                map
            }
            JsonEvent.BEGIN_ARRAY -> {
                val list = ArrayList<Any?>()
                source.beginArray()
                while (source.hasNext()) list.add(readValue())
                source.endArray()
                list
            }
            JsonEvent.STRING -> source.nextString()
            JsonEvent.NUMBER -> source.nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull() ?: it }
            JsonEvent.BOOLEAN -> source.nextBoolean()
            JsonEvent.NULL -> {
                source.nextNull()
                null
            }
            else -> throw BackupFormatException("Malformed JSON near row $rowsRead (token ${source.peek()})")
        }

        /** Unwrap the two Dexie row encodings into the plain value object. */
        private fun unwrapRow(map: Map<*, *>): Map<String, Any?>? {
            val anonymous = map["$"]
            val value = if (anonymous is List<*>) anonymous.getOrNull(1) else map
            @Suppress("UNCHECKED_CAST")
            return value as? Map<String, Any?>
        }

        private fun dispatchRow(table: String, row: Map<String, Any?>) {
            if (table == "dictionaries") {
                val title = row.str("title")
                if (title.isEmpty()) return
                if (!shouldInclude(title)) {
                    skippedTitles += title
                    return
                }
                if (stages.containsKey(title)) return
                val safeName = uniqueSafeName(title)
                stages[title] = DictionaryStaging(
                    title = title,
                    safeName = safeName,
                    info = row,
                    dir = File(dirsRoot, safeName),
                    bankSize = bankSize,
                )
                return
            }

            val title = row.str("dictionary")
            if (title.isEmpty()) return
            val staging = stages[title] ?: return
            try {
                when (table) {
                    "terms" -> staging.addTerm(row)
                    "termMeta" -> staging.addTermMeta(row)
                    "kanji" -> staging.addKanji(row)
                    "kanjiMeta" -> staging.addKanjiMeta(row)
                    "tagMeta" -> staging.addTag(row)
                    "media" -> staging.addMedia(row)
                    else -> Unit // unknown/newer store — ignore
                }
            } catch (_: Exception) {
                // A row we understood the shape of but could not map (e.g. missing key). Count and
                // keep going; never abort a multi-minute parse for one bad row.
                staging.countMalformed()
            }
        }

        private fun uniqueSafeName(title: String): String {
            val base = sanitizeTitleForPath(title, fallback = "dictionary")
            var candidate = base
            var n = 1
            while (!usedSafeNames.add(candidate)) {
                candidate = "$base ($n)"
                n++
            }
            return candidate
        }
    }

    companion object {
        const val DEFAULT_BANK_SIZE = 10_000
        private const val READER_BUFFER = 1 shl 16
        private const val PROGRESS_EVERY = 4096L
    }
}

/** Zip every file under [dir] into [zip] (DEFLATED), storing paths relative to [dir]. */
internal fun zipDirectory(dir: File, zip: File) {
    val files = dir.walkTopDown().filter { it.isFile }.toList().sortedBy { it.relativeTo(dir).path }
    ZipOutputStream(zip.outputStream().buffered(ZIP_BUFFER)).use { out ->
        out.setLevel(Deflater.DEFAULT_COMPRESSION)
        for (file in files) {
            val name = file.relativeTo(dir).invariantSeparatorsPath
            val entry = ZipEntry(name)
            entry.time = 0L // deterministic archives
            out.putNextEntry(entry)
            file.inputStream().buffered(ZIP_BUFFER).use { it.copyTo(out, ZIP_BUFFER) }
            out.closeEntry()
        }
    }
}

private const val ZIP_BUFFER = 1 shl 16
// KUN <--
