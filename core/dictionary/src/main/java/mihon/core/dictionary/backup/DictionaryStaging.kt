// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package mihon.core.dictionary.backup

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * One numbered bank file (`<prefix>_N.json`), appended to as a JSON array and rotated every
 * [bankSize] entries. Only one [BankWriter] file handle per bank type is open at a time.
 */
internal class BankWriter(
    private val dir: File,
    private val prefix: String,
    private val bankSize: Int,
) {
    private var file: BufferedWriter? = null
    private var count = 0
    var total: Long = 0L
        private set
    var files: Int = 0
        private set

    private fun open() {
        files++
        file = BufferedWriter(
            OutputStreamWriter(File(dir, "${prefix}_$files.json").outputStream(), StandardCharsets.UTF_8),
            BUFFER_BYTES,
        ).also { it.write("[") }
    }

    fun add(entry: List<Any?>) {
        val out = file ?: openAndGet()
        out.write(if (count == 0) "\n" else ",\n")
        out.write(MiniJson.encode(entry))
        count++
        total++
        if (count >= bankSize) close()
    }

    private fun openAndGet(): BufferedWriter {
        open()
        return file!!
    }

    fun close() {
        val out = file ?: return
        out.write("\n]\n")
        out.close()
        file = null
        count = 0
    }

    companion object {
        private const val BUFFER_BYTES = 1 shl 16
    }
}

/**
 * Reconstruction of one dictionary's Yomitan banks while the backup is streamed.
 *
 * `terms` rows in the real backup are interleaved across dictionaries (94 transitions), so one
 * [DictionaryStaging] stays open for the entire `terms` table — it must never be finalised just
 * because a different dictionary's row appeared. All bank files are closed together in [close].
 */
internal class DictionaryStaging(
    val title: String,
    val safeName: String,
    /** The `dictionaries` row, used to build `index.json`. */
    private val info: Map<String, Any?>,
    val dir: File,
    bankSize: Int,
) {
    private val terms = BankWriter(dir, "term_bank", bankSize)
    private val termMeta = BankWriter(dir, "term_meta_bank", bankSize)
    private val kanji = BankWriter(dir, "kanji_bank", bankSize)
    private val kanjiMeta = BankWriter(dir, "kanji_meta_bank", bankSize)
    private val tags = BankWriter(dir, "tag_bank", bankSize)

    var mediaCount: Long = 0L
        private set
    private var malformed: Long = 0L

    /** Rows this dictionary dropped (missing key, undecodable base64) — surfaced as a counted warning. */
    val malformedRowCount: Long get() = malformed

    init {
        dir.mkdirs()
    }

    fun addTerm(row: Map<String, Any?>) {
        // Always emit the V3 8-element shape; hoshidicts has one fixed-size Term struct, so a legacy
        // V1 row missing `sequence`/`termTags` must be padded rather than passed through short.
        terms.add(
            listOf(
                row.str("expression"),
                row.str("reading"),
                row.str("definitionTags"),
                row.str("rules"),
                row.num("score"),
                row.value("glossary") ?: emptyList<Any?>(),
                row.num("sequence"),
                row.str("termTags"),
            ),
        )
    }

    fun addTermMeta(row: Map<String, Any?>) {
        val expression = row.str("expression")
        if (expression.isEmpty()) {
            malformed++
            return
        }
        termMeta.add(listOf(expression, row.str("mode"), row.value("data")))
    }

    fun addKanji(row: Map<String, Any?>) {
        // Yomitan stores meanings under `meanings`; hoshidicts' 5th kanji-bank element is `definitions`.
        kanji.add(
            listOf(
                row.str("character"),
                row.str("onyomi"),
                row.str("kunyomi"),
                row.str("tags"),
                row.value("meanings") ?: emptyList<Any?>(),
                row.value("stats") ?: emptyMap<String, Any?>(),
            ),
        )
    }

    fun addKanjiMeta(row: Map<String, Any?>) {
        val character = row.str("character")
        if (character.isEmpty()) {
            malformed++
            return
        }
        kanjiMeta.add(listOf(character, row.str("mode"), row.value("data")))
    }

    fun addTag(row: Map<String, Any?>) {
        tags.add(
            listOf(
                row.str("name"),
                row.str("category"),
                row.num("order"),
                row.str("notes"),
                row.num("score"),
            ),
        )
    }

    /** Decode a media row's base64 `content` and write the real bytes to its `path`. */
    fun addMedia(row: Map<String, Any?>) {
        val path = sanitizeMediaPath(row.str("path"))
        val encoded = row.value("content") as? String
        if (path == null || encoded.isNullOrEmpty()) {
            malformed++
            return
        }
        val bytes = try {
            Base64.getMimeDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            malformed++
            return
        }
        val dest = File(dir, path)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        mediaCount++
    }

    /** Irrecoverable-but-countable row problem (missing key, undecodable base64). */
    fun countMalformed() {
        malformed++
    }

    fun closeBanks() {
        terms.close()
        termMeta.close()
        kanji.close()
        kanjiMeta.close()
        tags.close()
    }

    fun writeIndexAndStyles() {
        val index = LinkedHashMap<String, Any?>()
        index["title"] = title
        index["revision"] = info.str("revision")
        // The DB `version` is Yomitan's index `format` (3 for the whole 2024 backup).
        (info.value("version") as? Number)?.let { index["format"] = it.toLong() }
        for (key in INDEX_PASSTHROUGH) {
            info[key]?.let { index[key] = it }
        }
        File(dir, "index.json").writeText(MiniJson.encode(index), StandardCharsets.UTF_8)

        // This backup predates Yomitan persisting dictionary CSS (styles were only stored from
        // 2024-06-20), so `styles` is absent. Emit an empty file: harmless, and the importer treats
        // an empty stylesheet as "no custom CSS" rather than a missing-file error.
        val styles = info.str("styles")
        File(dir, "styles.css").writeText(styles, StandardCharsets.UTF_8)
    }

    /** Bank-file counts for a summary line, e.g. `term=3 termMeta=1 …`. */
    fun summary(): String =
        "terms=${terms.total} termMeta=${termMeta.total} kanji=${kanji.total} " +
            "kanjiMeta=${kanjiMeta.total} tags=${tags.total} media=$mediaCount malformed=$malformed"

    companion object {
        // index.json fields that pass through verbatim from the `dictionaries` row (§3 mapping).
        private val INDEX_PASSTHROUGH = listOf(
            "sequenced", "author", "url", "description", "attribution", "frequencyMode",
            "sourceLanguage", "targetLanguage", "minimumYomitanVersion", "isUpdatable",
            "indexUrl", "downloadUrl",
        )

        /**
         * Sanitise a media `path` for the staging directory: normalise separators, drop empty/`.`/`..`
         * segments (path-traversal guard) and reject absolute paths / NULs.
         */
        fun sanitizeMediaPath(raw: String): String? {
            if (raw.isBlank() || raw.indexOf('\u0000') >= 0) return null
            val cleaned = raw.replace('\\', '/').trimStart('/')
            val segments = cleaned.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            return segments.joinToString("/").ifEmpty { null }
        }
    }
}

/** Filesystem-safe directory/ZIP stem for a dictionary title. */
internal fun sanitizeTitleForPath(title: String, fallback: String): String {
    val cleaned = buildString(title.length) {
        for (c in title) {
            when {
                c == '/' || c == '\\' || c == '\u0000' -> append('_')
                c.code < 0x20 -> append('_')
                else -> append(c)
            }
        }
    }.trim().trim('.').trim()
    val collapsed = cleaned.take(120).ifBlank { fallback }
    return collapsed.ifBlank { fallback }
}
// KUN <--
