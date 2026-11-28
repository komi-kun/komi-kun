package mihon.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import kotlin.reflect.full.createType

/**
 * Guard for the navigation registry.
 *
 * Navigation 3's `entryProvider` **throws** for a key it does not know, and `NavigatorDisplay` asks it for
 * every route on the back stack, so a route that is pushed but never registered force-closes the app the
 * moment it is reached. This test is what keeps that from shipping again: it reads the route declarations
 * and the registry, and fails naming the route that is missing.
 *
 * Three things a route needs to be reachable at all:
 * 1. every `NavKey` route declared anywhere under `app/src/main/java` has an `entry<…>` somewhere;
 * 2. every route declared in a root-stack route file has its `entry<…>` in `AppNavGraph.kt`'s
 *    `appEntries()` specifically — that is the registry the root `NavigatorDisplay` uses, so a route
 *    registered only in a nested provider is still unregistered for the root stack;
 * 3. every route type loads and has a generated kotlinx serializer — an unserializable `NavKey` breaks
 *    stack persistence, which is how a plain `object` route would otherwise slip in.
 */
class NavigationRouteRegistryTest {

    /**
     * Route sources whose routes are dispatched by the root `NavigatorDisplay` (`appEntries()`):
     * upstream's `AppRoutes.kt`, this fork's `KunRoutes.kt`, and the recommendation routes that are
     * declared next to their screens.
     */
    private val rootRouteSources = listOf(
        File("../core/navigation/src/main/kotlin/mihon/core/navigation/AppRoutes.kt"),
        File("src/main/java/mihon/core/navigation/KunRoutes.kt"),
        File("src/main/java/exh/recs/RecommendsScreen.kt"),
    )

    private val registry = File("src/main/java/mihon/core/navigation/AppNavGraph.kt")

    /** A route declaration: its name, its package and the file it lives in. */
    private data class Declaration(val name: String, val packageName: String, val file: String)

    @Test
    fun `every declared NavKey route is registered and serializable`() {
        val declarations = routeDeclarations()
        if (declarations.isEmpty()) {
            fail("found no route declarations at all — the scan is broken, not the registry")
        }

        val sources = sourceFiles().map { it to it.readText() }

        val unregistered = declarations.filterNot { declaration ->
            sources.any { (_, text) -> text.hasEntryFor(declaration.name) }
        }
        if (unregistered.isNotEmpty()) {
            fail(
                "these routes have no entry<…> anywhere, so pushing them throws: " +
                    unregistered.joinToString { "${it.name} (${it.file})" },
            )
        }

        val rootFiles = rootRouteSources.map { it.path }.toSet()
        val notInAppEntries = declarations
            .filter { it.file in rootFiles }
            .filterNot { registry.readText().hasEntryFor(it.name) }
        if (notInAppEntries.isNotEmpty()) {
            fail(
                "these root-stack routes are missing from AppNavGraph.appEntries(): " +
                    notInAppEntries.joinToString { it.name },
            )
        }

        val unserializable = declarations.filter { declaration ->
            val type = Class.forName("${declaration.packageName}.${declaration.name}").kotlin
            !NavKey::class.java.isAssignableFrom(type.java) ||
                runCatching { serializer(type.createType()) }.isFailure
        }
        if (unserializable.isNotEmpty()) {
            fail(
                "these routes are not serializable NavKeys, so the back stack cannot be persisted: " +
                    unserializable.joinToString { it.name },
            )
        }
    }

    /**
     * Source guard for the other half of the registry's job: a route's state has to reach its screen.
     * Registration alone is not enough — `MangaScreen` and `BrowseSourceScreen` take their smart-search,
     * saved-search and filter state as parameters, so an `entry` that only forwards the ids compiles, runs,
     * and silently drops the feature the route was created for.
     */
    @Test
    fun `the registry forwards the state-carrying route arguments`() {
        val registry = registry.readText()

        // Each assignment only exists at the registry, so a match pins the forwarding to this file.
        listOf(
            "smartSearchTitle = route.smartSearchTitle",
            "smartSearchMangaId = route.smartSearchMangaId",
            "filtersJson = route.filtersJson",
            "savedSearch = route.savedSearch",
            "smartSearchConfig = route.smartSearchTitle?.let",
        ).forEach { assignment ->
            if (!registry.contains(assignment)) {
                fail("AppNavGraph.appEntries() no longer forwards `$assignment` to the screen it dispatches")
            }
        }

        // The recommendation routes must hand their whole argument to the screen, not just the key.
        if (!registry.contains("route.args()")) {
            fail("the recommendation entries no longer decode their route arguments")
        }
    }

    /** Every `@Serializable` `…Route` class or object declared under `app/src/main/java`. */
    private fun routeDeclarations(): List<Declaration> = sourceFiles().flatMap { file ->
        val text = file.readText()
        val packageName = PACKAGE.find(text)?.groupValues?.get(1) ?: return@flatMap emptyList()
        ROUTE.findAll(text).map { Declaration(it.groupValues[1], packageName, file.path) }.toList()
    }.distinctBy { it.name }

    private fun sourceFiles(): List<File> = File("src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /**
     * Whether [this] source registers [route]. `entry<Name>` is the only way a route is registered, and
     * the angle brackets make it unambiguous: a bare `Name` also appears in imports and in pushes.
     */
    private fun String.hasEntryFor(route: String): Boolean = contains("entry<$route>")

    private companion object {
        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

        /** A `@Serializable` (optionally `internal`, `data`) `class`/`object` whose name ends in `Route`. */
        val ROUTE = Regex("""@Serializable\s*(?:\r?\n)+\s*(?:internal\s+)?data\s+(?:class|object)\s+(\w*Route)\b""")
    }
}
