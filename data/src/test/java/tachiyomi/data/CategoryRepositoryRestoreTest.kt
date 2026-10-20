// KUN -->
package tachiyomi.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.category.CategoryMapper
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.domain.category.model.Category

/**
 * Guards the category restore path used by SyncYomi: the whole restore has to commit once so the
 * library's category tabs are not redrawn with the negative sentinel orders of the reorder
 * pre-pass or with one intermediate list per written row.
 */
class CategoryRepositoryRestoreTest {

    @Test
    fun `restore emits a single category list update`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val repository = CategoryRepositoryImpl(database)
                driver.insertCategory(id = 1L, name = "Action", order = 0L)
                driver.insertCategory(id = 2L, name = "Drama", order = 1L)

                var notifications = 0
                // The listener fires on the committed state, so this also proves the single list the
                // UI receives is the final one and not the reorder pre-pass.
                val snapshots = mutableListOf<List<String>>()
                database.categoryQueries
                    .getCategories(CategoryMapper::mapCategory)
                    .addListener {
                        notifications++
                        snapshots += driver.categoryNamesInOrder()
                    }

                repository.restoreCategories(
                    categories = listOf(
                        // Reverses the existing orders, renames one category and adds a third.
                        category(name = "Action Renamed", order = 1L, uid = Category.nameToUid("Action")),
                        category(name = "Comedy", order = 2L),
                        category(name = "Drama", order = 0L, uid = Category.nameToUid("Drama")),
                    ),
                    isSync = true,
                )

                notifications shouldBe 1
                snapshots shouldBe listOf(listOf("Drama", "Action Renamed", "Comedy"))
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `restore never leaves sentinel orders behind`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val repository = CategoryRepositoryImpl(database)
                driver.insertCategory(id = 1L, name = "Action", order = 0L)
                driver.insertCategory(id = 2L, name = "Drama", order = 1L)

                repository.restoreCategories(
                    categories = listOf(
                        category(name = "Action", order = 1L, uid = Category.nameToUid("Action")),
                        category(name = "Comedy", order = 2L),
                        category(name = "Drama", order = 0L, uid = Category.nameToUid("Drama")),
                    ),
                    isSync = true,
                )

                (driver.scalarLong("SELECT min(`order`) FROM category WHERE id > 0") ?: -1L) shouldBe 0L
                driver.scalarLong("SELECT count(*) FROM category WHERE is_syncing != 0") shouldBe 0L
                // No `freeOrder()` fallbacks: the user-visible orders are the incoming ones.
                driver.categoryNamesInOrder() shouldBe listOf("Drama", "Action", "Comedy")
                driver.categoryOrdersInOrder() shouldBe listOf(0L, 1L, 2L)
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `restore keeps existing category ids`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val repository = CategoryRepositoryImpl(database)
                driver.insertCategory(id = 7L, name = "Action", order = 0L)
                driver.insertCategory(id = 9L, name = "Drama", order = 1L)

                repository.restoreCategories(
                    categories = listOf(
                        category(name = "Action", order = 1L, uid = Category.nameToUid("Action")),
                        category(name = "Drama", order = 0L, uid = Category.nameToUid("Drama")),
                    ),
                    isSync = true,
                )

                // The rows are updated in place, so the selected tab survives the restore.
                driver.scalarLong("SELECT id FROM category WHERE name = 'Action'") shouldBe 7L
                driver.scalarLong("SELECT id FROM category WHERE name = 'Drama'") shouldBe 9L
            } finally {
                driver.close()
            }
        }
    }

    @Test
    fun `restore converges uids`() {
        runBlocking {
            val driver = newDriver()
            try {
                val database = DatabaseBindings.providesDatabase(driver)
                val repository = CategoryRepositoryImpl(database)
                // Seeded while syncing so the insert trigger does not hand out a random uid.
                driver.insertCategory(id = 1L, name = "Action", order = 0L, uid = 0L, isSyncing = 1)

                repository.restoreCategories(
                    categories = listOf(
                        // Matched by normalized name, the existing row adopts the incoming uid.
                        category(name = "  action ", order = 0L, uid = Category.nameToUid("action")),
                        // A new category without a uid gets the deterministic one.
                        category(name = "Comedy", order = 1L),
                    ),
                    isSync = true,
                )

                driver.scalarLong("SELECT id FROM category WHERE name = '  action '") shouldBe 1L
                driver.scalarLong("SELECT uid FROM category WHERE id = 1") shouldBe
                    Category.nameToUid("action")
                driver.scalarLong("SELECT uid FROM category WHERE name = 'Comedy'") shouldBe
                    Category.nameToUid("Comedy")
                driver.scalarLong("SELECT count(*) FROM category WHERE is_syncing != 0") shouldBe 0L
            } finally {
                driver.close()
            }
        }
    }

    private suspend fun newDriver(): SqlDriver {
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            Database.Schema.create(it).await()
        }
    }

    private fun category(name: String, order: Long, uid: Long = 0L): Category {
        return Category(
            id = 0L,
            name = name,
            order = order,
            flags = 0L,
            hidden = false,
            uid = uid,
        )
    }

    private suspend fun SqlDriver.insertCategory(
        id: Long,
        name: String,
        order: Long,
        uid: Long = Category.nameToUid(name),
        isSyncing: Int = 0,
    ) {
        execute(
            null,
            """
            INSERT INTO category(id, name, `order`, flags, hidden, version, uid, last_modified_at, is_syncing)
            VALUES ($id, '$name', $order, 0, 0, 0, $uid, 0, $isSyncing)
            """.trimIndent(),
            0,
        ).await()
    }

    private fun SqlDriver.categoryNamesInOrder(): List<String> =
        executeQuery(
            null,
            "SELECT name FROM category WHERE id > 0 ORDER BY `order`",
            { cursor ->
                QueryResult.Value(
                    buildList {
                        while (cursor.next().value) add(cursor.getString(0)!!)
                    },
                )
            },
            0,
        ).value

    private fun SqlDriver.categoryOrdersInOrder(): List<Long> =
        executeQuery(
            null,
            "SELECT `order` FROM category WHERE id > 0 ORDER BY `order`",
            { cursor ->
                QueryResult.Value(
                    buildList {
                        while (cursor.next().value) add(cursor.getLong(0)!!)
                    },
                )
            },
            0,
        ).value

    private fun SqlDriver.scalarLong(sql: String): Long? =
        executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).value
}
// KUN <--
