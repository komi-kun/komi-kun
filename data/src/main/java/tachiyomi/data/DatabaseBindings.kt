package tachiyomi.data

import app.cash.sqldelight.db.SqlDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {

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
