package tachiyomi.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {

    // KMK -->
    // The generated schema lives in this module; expose it so the app's driver bindings do not
    // have to reference the generated `Database` type directly.
    @Provides
    @SingleIn(AppScope::class)
    fun providesSchema(): SqlSchema<QueryResult.AsyncValue<Unit>> = Database.Schema
    // KMK <--

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabase(driver: SqlDriver): Database {
        return Database(
            driver = driver,
            chapterAdapter = Chapter.Adapter(
                remote_memoAdapter = MemoColumnAdapter,
            ),
            historyAdapter = History.Adapter(
                read_atAdapter = DateColumnAdapter,
            ),
            mangaAdapter = Manga.Adapter(
                remote_genreAdapter = StringListColumnAdapter,
                remote_update_strategyAdapter = UpdateStrategyColumnAdapter,
                remote_memoAdapter = MemoColumnAdapter,
            ),
        )
    }
}
