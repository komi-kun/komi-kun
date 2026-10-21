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
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class PreferenceRestorer(
    private val context: Context,
    private val getCategories: GetCategories,
    private val preferenceStore: PreferenceStore,
) {
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
        // KUN <--
    ) {
        val allCategories = if (backupCategories != null) getCategories.await() else emptyList()
        val categoriesByName = allCategories.associateBy { it.name }
        val backupCategoriesById = backupCategories?.associateBy { it.id.toString() }.orEmpty()
        val prefs = preferenceStore.getAll()
        toRestore.forEach { (key, value) ->
            // KUN -->
            // App-state keys are device-local state (sync cursors, the pinned-source choice, ...)
            // and are already excluded from newly created backups; never restore them from an
            // older or hand-edited payload.
            if (Preference.isAppState(key)) return@forEach
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
                            preferenceStore.getString(key).set(value.value)
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
        val categoryPreferences = LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys
        if (key !in categoryPreferences) return false

        val ids = value.mapNotNull {
            backupCategoriesById[it]?.name?.let { name ->
                categoriesByName[name]?.id?.toString()
            }
        }

        if (ids.isNotEmpty()) {
            // KUN -->
            preferenceStore.getStringSet(key).set(ids.toSet())
            // KUN <--
        }
        return true
    }

    internal fun restorePinnedSourcesPreference(
        key: String,
        value: Set<String>,
        preferenceStore: PreferenceStore,
        // KUN -->
        options: RestoreOptions,
        // KUN <--
    ) {
        // KUN -->
        val valueToSet = EXHMigrations.migrateSourceIds(value)
        if (options.replacePinnedSources) {
            // Opted in: the synced set is authoritative, so an un-pin propagates to this device and
            // an empty set clears the local pins.
            preferenceStore.getStringSet(key).set(valueToSet)
        } else {
            // Default: pinned sources are usually device-specific, so keep the local pins and add
            // the synced ones.
            val existing = preferenceStore.getStringSet(key).get()
            preferenceStore.getStringSet(key).set(existing + valueToSet)
        }
        // KUN <--
    }
}
