package mihon.app.di

import android.app.ActivityManager
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import com.eygraber.sqldelight.androidx.driver.SqliteJournalMode
import com.eygraber.sqldelight.androidx.driver.SqliteSync
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.core.archive.CbzCrypto
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.data.Database

@BindingContainer
object AppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesSqlDriver(
        context: Context,
        securityPreferences: SecurityPreferences,
    ): SqlDriver {
        // KMK -->
        // Configure connections like Room: WAL unless the device is low on RAM.
        val isWal = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice != true
        // KMK <--

        // SY -->
        if (securityPreferences.encryptDatabase.get()) {
            System.loadLibrary("sqlcipher")

            return AndroidSqliteDriver(
                schema = Database.Schema.synchronous(),
                context = context,
                name = CbzCrypto.DATABASE_NAME,
                factory = SupportOpenHelperFactory(CbzCrypto.getDecryptedPasswordSql(), null, false, 25),
                callback = object : AndroidSqliteDriver.Callback(Database.Schema.synchronous()) {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        setPragma(db, "foreign_keys = ON")
                        // KMK -->
                        setPragma(db, "journal_mode = ${if (isWal) "WAL" else "TRUNCATE"}")
                        // KMK <--
                        setPragma(db, "synchronous = NORMAL")
                        // KMK -->
                        setPragma(db, "busy_timeout = 3000")
                        // KMK <--
                    }

                    private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                        val cursor = db.query("PRAGMA $pragma")
                        cursor.moveToFirst()
                        cursor.close()
                    }
                },
            )
        }
        // SY <--

        return AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, "tachiyomi.db"),
            schema = Database.Schema,
            configuration = AndroidxSqliteConfiguration(
                isForeignKeyConstraintsEnabled = true,
                // KMK -->
                journalMode = if (isWal) SqliteJournalMode.WAL else SqliteJournalMode.Truncate,
                sync = SqliteSync.Normal,
                // KMK <--
            ),
            // KMK -->
            onConfigure = {
                executePragma("busy_timeout = 3000")
            },
            // KMK <--
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesXML(): XML = XML {
        defaultPolicy {
            ignoreUnknownChildren()
        }
        autoPolymorphic = true
        xmlDeclMode = XmlDeclMode.Charset
        indent = 2
        xmlVersion = XmlVersion.XML10
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesProtoBuf(): ProtoBuf = ProtoBuf

    // KMK -->
    @Provides
    @SingleIn(AppScope::class)
    fun providesAllowHiddenFiles(sourcePreferences: SourcePreferences): Boolean =
        sourcePreferences.allowLocalSourceHiddenFolders.get()

    @Provides
    @SingleIn(AppScope::class)
    fun providesFilterSerializer(): xyz.nulldev.ts.api.http.serializer.FilterSerializer =
        xyz.nulldev.ts.api.http.serializer.FilterSerializer()
    // KMK <--

    // SY -->
    // Metro cannot bind a function type, so DeleteCategory's sync tombstone callback is wired here.
    @Provides
    @SingleIn(AppScope::class)
    fun providesDeleteCategory(
        categoryRepository: tachiyomi.domain.category.repository.CategoryRepository,
        libraryPreferences: tachiyomi.domain.library.service.LibraryPreferences,
        downloadPreferences: tachiyomi.domain.download.service.DownloadPreferences,
        syncPreferences: eu.kanade.domain.sync.SyncPreferences,
    ): tachiyomi.domain.category.interactor.DeleteCategory = tachiyomi.domain.category.interactor.DeleteCategory(
        categoryRepository = categoryRepository,
        libraryPreferences = libraryPreferences,
        downloadPreferences = downloadPreferences,
        onCategoryDeleted = syncPreferences::rememberDeletedCategory,
    )
    // SY <--
}
