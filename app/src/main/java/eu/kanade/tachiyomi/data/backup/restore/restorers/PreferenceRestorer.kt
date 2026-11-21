package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreateWorker
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.data.updater.AppUpdateJob
import eu.kanade.tachiyomi.source.sourcePreferences
import exh.EXHMigrations
import exh.log.xLogE
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.service.CategoryIdPreferences
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class PreferenceRestorer(
    private val context: Context,
    private val getCategories: GetCategories,
    // KUN -->
    // The category-id inventory the pruner and the repair migration share. Restoring a backup has
    // to translate the other device's category rowids in exactly the same preferences, so deriving
    // the key set from `CategoryIdPreferences` keeps the three in step; the hand-maintained
    // `LibraryPreferences`/`DownloadPreferences` key sets missed the Discord incognito set and every
    // `List<Long>` filter.
    private val categoryIdPreferences: CategoryIdPreferences,
    // KUN <--
    private val preferenceStore: PreferenceStore,
) {
    // KUN -->
    /**
     * The category-id keys, split by how the value is stored, both derived from [CategoryIdPreferences]:
     *
     * - [categoryIdStringKeys] are `Set<String>` (`StringSetPreferenceValue` in a payload);
     * - [categoryIdLongKeys] are `List<Long>` written by `getLongArray`, i.e. a comma-joined `String`
     *   (`StringPreferenceValue` in a payload).
     *
     * The History filters of `longFilters` are app-state keys: `PreferenceBackupCreator` drops them
     * when creating a backup and [restorePreferences] drops them again on the way in, so a payload
     * never carries them. They are in the set because it is the inventory, not a copy of it.
     */
    private val categoryIdStringKeys = categoryIdPreferences.stringFilters.mapTo(mutableSetOf()) { it.key() }
    private val categoryIdLongKeys = categoryIdPreferences.longFilters.mapTo(mutableSetOf()) { it.key() }
    // KUN <--

    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupCategories: List<BackupCategory>?,
        // KUN -->
        options: RestoreOptions = RestoreOptions(),
        // KUN <--
    ) {
        restorePreferences(
            preferences,
            preferenceStore,
            backupCategories,
            // KUN -->
            options,
            isAppPreferences = true,
            // KUN <--
        )

        LibraryUpdateWorker.setupTask(context)
        BackupCreateWorker.setupTask(context)
        // KMK -->
        AppUpdateJob.setupTask(context)
        // KMK <--
    }

    suspend fun restoreSource(preferences: List<BackupSourcePreferences>) {
        preferences.forEach {
            val sourcePrefs = AndroidPreferenceStore(sourcePreferences(it.sourceKey))
            restorePreferences(it.prefs, sourcePrefs)
        }
    }

    internal suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        backupCategories: List<BackupCategory>? = null,
        // KUN -->
        options: RestoreOptions = RestoreOptions(),
        isAppPreferences: Boolean = false,
        // KUN <--
    ) {
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        val categoriesByName = allCategories.associateBy { it.name }
        val backupCategoriesById = backupCategories?.associateBy { it.id.toString() }.orEmpty()
        val prefs = preferenceStore.getAll()
        toRestore.forEach { (key, value) ->
            // KUN -->
            // App-state keys are device-local state (sync cursors, the pinned-source choice, ...)
            // and are already excluded from newly created app-preference backups; never restore
            // them from an older or hand-edited payload. Source preferences are namespaced
            // separately and keep whatever keys their source uses.
            if (isAppPreferences && Preference.isAppState(key)) return@forEach
            // KUN <--
            try {
                when (value) {
                    is IntPreferenceValue -> {
                        if (prefs[key] is Int?) {
                            val newValue = if (key == LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY) {
                                backupCategoriesById[value.value.toString()]
                                    ?.let { categoriesByName[it.name]?.id?.toInt() }
                            } else {
                                value.value
                            }

                            newValue?.let { preferenceStore.getInt(key).set(it) }
                        }
                    }
                    is LongPreferenceValue -> {
                        if (prefs[key] is Long?) {
                            preferenceStore.getLong(key).set(value.value)
                        }
                    }
                    is FloatPreferenceValue -> {
                        if (prefs[key] is Float?) {
                            preferenceStore.getFloat(key).set(value.value)
                        }
                    }
                    is StringPreferenceValue -> {
                        if (prefs[key] is String?) {
                            // KUN -->
                            val restored = restoreCommaJoinedCategoriesPreference(
                                key,
                                value.value,
                                preferenceStore,
                                backupCategoriesById,
                                categoriesByName,
                            )
                            if (!restored) {
                                preferenceStore.getString(key).set(value.value)
                            }
                            // KUN <--
                        }
                    }
                    is BooleanPreferenceValue -> {
                        if (prefs[key] is Boolean?) {
                            preferenceStore.getBoolean(key).set(value.value)
                        }
                    }
                    is StringSetPreferenceValue -> {
                        if (prefs[key] is Set<*>?) {
                            val restored = restoreCategoriesPreference(
                                key,
                                value.value,
                                preferenceStore,
                                backupCategoriesById,
                                categoriesByName,
                            )
                            if (!restored) {
                                // KMK -->
                                when (key) {
                                    SourcePreferences.PINNED_SOURCES_PREF_KEY ->
                                        restorePinnedSourcesPreference(
                                            key,
                                            value.value,
                                            preferenceStore,
                                            // KUN -->
                                            options,
                                            // KUN <--
                                        )
                                    else ->
                                        // KMK <--
                                        preferenceStore.getStringSet(key).set(value.value)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // KMK -->
                xLogE("Failed to restore preference <$key>", e)
                // KMK <--
            }
        }
    }

    private fun restoreCategoriesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        backupCategoriesById: Map<String, BackupCategory>,
        categoriesByName: Map<String, Category>,
    ): Boolean {
        // KUN -->
        // The key set is the shared category-id inventory, not a second hand-maintained list: it
        // covers the Discord incognito set as well as the library and download filters.
        if (key !in categoryIdStringKeys) return false
        // KUN <--

        val ids = value.mapNotNull {
            remapCategoryId(it, backupCategoriesById, categoriesByName)
        }

        if (ids.isNotEmpty()) {
            // KUN -->
            preferenceStore.getStringSet(key).set(ids.toSet())
            // KUN <--
        }
        return true
    }

    // KUN -->
    /**
     * Remaps a `List<Long>` category filter, which `getLongArray` stores as a comma-joined string of
     * ids, so a restore of another device's backup would otherwise write its rowids verbatim.
     *
     * Returns `false` for a key that is not one of those filters, so the caller writes the value as
     * it came. Like [restoreCategoriesPreference], a list whose ids all name categories missing here
     * leaves the local preference alone rather than clearing it.
     */
    private fun restoreCommaJoinedCategoriesPreference(
        key: String,
        value: String,
        preferenceStore: PreferenceStore,
        backupCategoriesById: Map<String, BackupCategory>,
        categoriesByName: Map<String, Category>,
    ): Boolean {
        if (key !in categoryIdLongKeys) return false

        val ids = value.split(",").mapNotNull {
            remapCategoryId(it, backupCategoriesById, categoriesByName)
        }
        if (ids.isNotEmpty()) {
            preferenceStore.getString(key).set(ids.joinToString(","))
        }
        return true
    }

    /** A backup category id as this device's id for the same category name; an unknown id drops. */
    private fun remapCategoryId(
        backupId: String,
        backupCategoriesById: Map<String, BackupCategory>,
        categoriesByName: Map<String, Category>,
    ): String? = backupCategoriesById[backupId]?.name?.let { name ->
        categoriesByName[name]?.id?.toString()
    }
    // KUN <--

    internal fun restorePinnedSourcesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        // KUN -->
        options: RestoreOptions,
        // KUN <--
    ) {
        // KUN -->
        // Pinned sources are device-local unless the user opted into syncing them: leave both the
        // local pins and the synced set alone in that case.
        if (options.skipPinnedSources) return

        val valueToSet = EXHMigrations.migrateSourceIds(value)
        if (options.replacePinnedSources) {
            // Synced: the incoming set is authoritative, so an un-pin propagates to this device and
            // an empty set clears the local pins.
            preferenceStore.getStringSet(key).set(valueToSet)
        } else {
            // Manual/file restore keeps the historical union behaviour.
            val existing = preferenceStore.getStringSet(key).get()
            preferenceStore.getStringSet(key).set(existing + valueToSet)
        }
        // KUN <--
    }
}
