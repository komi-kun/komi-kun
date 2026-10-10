package mihon.core.migration.migrations

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Inject
@ContributesIntoSet(AppScope::class)
class SetupSyncDataMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        // KMK -->
        val syncPreferences = Injekt.get<SyncPreferences>()
        val syncEnabled = syncPreferences.isSyncEnabled()
        if (syncEnabled) {
            // KMK <--
            SyncDataJob.setupTask(context)
        }
        return true
    }
}
