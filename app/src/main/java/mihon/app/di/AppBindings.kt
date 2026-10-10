package mihon.app.di

import android.app.ActivityManager
import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConnectionFactory
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.core.archive.CbzCrypto
import mihon.core.metro.AppCoroutineScope
import mihon.core.metro.IsDebugBuild
import mihon.telemetry.TelemetryConfig
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.AllowHiddenFiles

@BindingContainer
object AppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesSqlDriver(
        context: Context,
        securityPreferences: SecurityPreferences,
        // KMK -->
        // Provided by :data's DatabaseBindings, so the app does not reference the generated Database.
        schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
        // KMK <--
    ): SqlDriver {
        // KMK -->
        // Ported from Mihon 38e93086c8 ("Configure database connections like Room") and
        // 61cd786e7f ("Use WAL on every device"): WAL everywhere, one reader on low-RAM devices.
        val isLowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        // KMK <--

        // SY -->
        if (securityPreferences.encryptDatabase.get()) {
            System.loadLibrary("sqlcipher")

            return AndroidSqliteDriver(
                schema = schema.synchronous(),
                context = context,
                name = CbzCrypto.DATABASE_NAME,
                factory = SupportOpenHelperFactory(CbzCrypto.getDecryptedPasswordSql(), null, false, 25),
                callback = object : AndroidSqliteDriver.Callback(schema.synchronous()) {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        setPragma(db, "foreign_keys = ON")
                        // KMK -->
                        // Mihon 61cd786e7f: WAL on every device. SY hardcoded WAL here too.
                        setPragma(db, "journal_mode = WAL")
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
            // KMK -->
            // Mihon 38e93086c8: set busy_timeout on every pooled connection, not once on open.
            connectionFactory = object : AndroidxSqliteConnectionFactory {
                override val driver: SQLiteDriver = BundledSQLiteDriver()

                override fun createConnection(name: String): SQLiteConnection {
                    return driver.open(name).apply {
                        execSQL("PRAGMA busy_timeout = 3000")
                    }
                }
            },
            // KMK <--
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, "tachiyomi.db"),
            schema = schema,
            configuration = AndroidxSqliteConfiguration(
                isForeignKeyConstraintsEnabled = true,
                // KMK -->
                // Mihon 61cd786e7f: WAL on every device; one reader on low-RAM, four otherwise.
                concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = if (isLowRam) 1 else 4),
                // KMK <--
            ),
        )
    }

    @Provides
    @SingleIn(AppScope::class)
    @AppCoroutineScope
    fun providesAppCoroutineScope(@IsDebugBuild isDebugBuild: Boolean): CoroutineScope {
        // Work here outlives the screen that started it, so a failure would crash the app on whatever screen is open
        // by then. Debug builds still crash so the bug gets noticed.
        val handler = CoroutineExceptionHandler { _, throwable ->
            if (isDebugBuild) {
                val thread = Thread.currentThread()
                thread.uncaughtExceptionHandler?.uncaughtException(thread, throwable)
            } else {
                logcat(LogPriority.ERROR, throwable) { "Uncaught exception in the app scope" }
                TelemetryConfig.recordException(throwable)
            }
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
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
    fun providesAllowHiddenFiles(sourcePreferences: SourcePreferences): AllowHiddenFiles =
        AllowHiddenFiles { sourcePreferences.allowLocalSourceHiddenFolders.get() }

    @Provides
    @SingleIn(AppScope::class)
    fun providesFilterSerializer(): xyz.nulldev.ts.api.http.serializer.FilterSerializer =
        xyz.nulldev.ts.api.http.serializer.FilterSerializer()
    // KMK <--

    // SY -->
    // KMK -->
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
    // KMK <--
    // SY <--
}
