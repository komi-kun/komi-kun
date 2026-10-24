// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Ported from Chimahon (https://github.com/Chimahon/Chimahon, GPL-3.0-or-later),
// `app/src/main/java/eu/kanade/tachiyomi/data/ocr/OcrStore.kt` @ f85c063.
// Adapted for komi-kun: Metro-friendly (plain constructor + `OcrBindings` `@Provides`), no Injekt,
// and the queue status enum lives here so the store, manager and UI items share it.
// KUN <--
package mihon.core.ocr.cache

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted OCR queue, backed by `SharedPreferences` (a JSON `[OcrTask]` map keyed by chapter id,
 * plus an order counter). It survives process death so a background run can resume.
 */
class OcrStore(
    context: Context,
    private val json: Json,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All queued tasks, ordered by enqueue order. */
    fun getAll(): List<OcrTask> {
        return preferences.all
            .filterKeys { it != COUNTER_KEY }
            .mapNotNull { (_, value) -> (value as? String)?.let(::deserialize) }
            .sortedBy { it.order }
    }

    fun get(chapterId: Long): OcrTask? = preferences.getString(chapterId.toString(), null)?.let(::deserialize)

    fun hasRunnableTasks(): Boolean = hasRunnableStatuses(getAll().map { it.status })

    /** Adds/refreshes the requested chapters; returns true when anything changed. */
    fun enqueue(requests: List<OcrEnqueueRequest>): Boolean {
        if (requests.isEmpty()) return false

        val existing = getAll().associateBy { it.chapterId }.toMutableMap()
        var counter = preferences.getInt(COUNTER_KEY, 0)
        var changed = false

        requests.forEach { request ->
            val current = existing[request.chapterId]
            when {
                current == null -> {
                    existing[request.chapterId] = OcrTask(
                        mangaId = request.mangaId,
                        chapterId = request.chapterId,
                        order = counter++,
                        waitForDownload = request.waitForDownload,
                        status = if (request.waitForDownload) {
                            OcrQueueStatus.WAITING_DOWNLOAD
                        } else {
                            OcrQueueStatus.PENDING
                        },
                    )
                    changed = true
                }
                current.status == OcrQueueStatus.ERROR -> {
                    existing[request.chapterId] = current.copy(
                        waitForDownload = request.waitForDownload,
                        status = if (request.waitForDownload) {
                            OcrQueueStatus.WAITING_DOWNLOAD
                        } else {
                            OcrQueueStatus.PENDING
                        },
                        currentPage = 0,
                        totalPages = 0,
                    )
                    changed = true
                }
                request.waitForDownload && !current.waitForDownload -> {
                    existing[request.chapterId] = current.copy(waitForDownload = true)
                    changed = true
                }
            }
        }

        if (!changed) return false

        preferences.edit().apply {
            putInt(COUNTER_KEY, counter)
            existing.values.forEach { putString(it.chapterId.toString(), serialize(it)) }
        }.commit()
        return true
    }

    fun save(task: OcrTask) {
        preferences.edit().putString(task.chapterId.toString(), serialize(task)).commit()
    }

    fun update(chapterId: Long, transform: (OcrTask) -> OcrTask): OcrTask? {
        val current = get(chapterId) ?: return null
        val updated = transform(current)
        save(updated)
        return updated
    }

    fun remove(chapterId: Long) {
        preferences.edit().remove(chapterId.toString()).commit()
    }

    fun removeAll(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        preferences.edit().apply { chapterIds.forEach { remove(it.toString()) } }.commit()
    }

    /**
     * Normalises persisted tasks after a restart: an interrupted [OcrQueueStatus.PROCESSING] task
     * goes back to `PENDING`, and completed/cancelled tasks are dropped. Returns true when changed.
     */
    fun normalizeForRestore(): Boolean {
        val tasks = getAll()
        if (tasks.isEmpty()) return false

        var changed = false
        val normalized = tasks.mapNotNull { task ->
            when (task.status) {
                OcrQueueStatus.PROCESSING -> {
                    changed = true
                    task.copy(status = OcrQueueStatus.PENDING)
                }
                OcrQueueStatus.COMPLETED,
                OcrQueueStatus.CANCELLED,
                -> {
                    changed = true
                    null
                }
                else -> task
            }
        }

        if (!changed) return false

        val counter = preferences.getInt(COUNTER_KEY, 0)
        preferences.edit().apply {
            clear()
            putInt(COUNTER_KEY, counter)
            normalized.forEach { putString(it.chapterId.toString(), serialize(it)) }
        }.commit()
        return true
    }

    private fun serialize(task: OcrTask): String = json.encodeToString(task)

    private fun deserialize(value: String): OcrTask? = try {
        json.decodeFromString<OcrTask>(value)
    } catch (_: Exception) {
        null
    }

    private companion object {
        private const val PREFS_NAME = "active_ocr"
        private const val COUNTER_KEY = "__counter"
    }
}

/** A chapter requested for OCR. */
data class OcrEnqueueRequest(
    val mangaId: Long,
    val chapterId: Long,
    val waitForDownload: Boolean,
)

/** One persisted OCR queue entry. */
@Serializable
data class OcrTask(
    val mangaId: Long,
    val chapterId: Long,
    val order: Int,
    val waitForDownload: Boolean,
    val status: OcrQueueStatus = OcrQueueStatus.PENDING,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
)

enum class OcrQueueStatus {
    PENDING,
    WAITING_DOWNLOAD,
    PROCESSING,
    COMPLETED,
    ERROR,
    CANCELLED,
}

fun OcrQueueStatus.isActionable(): Boolean = this in listOf(
    OcrQueueStatus.PENDING,
    OcrQueueStatus.WAITING_DOWNLOAD,
    OcrQueueStatus.PROCESSING,
)

fun OcrQueueStatus.isRunnable(): Boolean = this in listOf(
    OcrQueueStatus.PENDING,
    OcrQueueStatus.PROCESSING,
)

/**
 * True when any of [statuses] is a status a queue run actually picks up ([isRunnable]).
 *
 * The single definition of "there is work" — [OcrStore.hasRunnableTasks] and the app-side launch /
 * enable resume both come through here, so the two can never drift apart (KUN -->).
 */
fun hasRunnableStatuses(statuses: Iterable<OcrQueueStatus>): Boolean = statuses.any { it.isRunnable() }
