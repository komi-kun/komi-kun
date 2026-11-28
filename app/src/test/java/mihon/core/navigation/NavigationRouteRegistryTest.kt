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
 * A declaration counts as a route when it is a `@Serializable` `data class`/`data object` implementing one
 * of [SUPERTYPES] — the interfaces through which everything in this app is dispatched. Keying on the
 * supertype rather than on a `…Route` name suffix matters: 24 of the registered routes are named for their
 * screen (`SettingsAnkiScreen`, `SettingsOcrScreen`, …) or are `TopLevelRoute` tabs (`TopLevelRoute.History`).
 *
 * Four things a route needs to be reachable at all:
 * 1. every route declared under either scanned root has an `entry<…>` somewhere;
 * 2. every route declared in a root-stack route file has its `entry<…>` in `AppNavGraph.kt`'s
 *    `appEntries()` specifically — that is the registry the root `NavigatorDisplay` uses, so a route
 *    registered only in a nested provider is still unregistered for the root stack;
 * 3. every route type loads and has a generated kotlinx serializer — an unserializable `NavKey` breaks
 *    stack persistence, which is how a plain `object` route would otherwise slip in;
 * 4. the scan itself is not silently broken: each root has to yield a plausible number of declarations.
 */
class NavigationRouteRegistryTest {

    /** A directory scanned for route declarations, with the minimum it is expected to contribute. */
    private data class Root(val path: String, val directory: File, val minimum: Int)

    /**
     * Both source roots that declare routes: this module and `:core:navigation`, whose `AppRoutes.kt` holds
     * upstream's routes (and is listed in [rootRouteSources], so it has to be scanned or that list is a lie).
     *
     * Unit tests run with the `app` module as the working directory, so `:core:navigation` is reached
     * through the repository root.
     */
    private val roots = listOf(
        Root("app", File("src/main/java"), minimum = 50),
        Root("core:navigation", File("../core/navigation/src/main/kotlin"), minimum = 25),
    )

    /**
     * Route sources whose routes are dispatched by the root `NavigatorDisplay` (`appEntries()`):
     * upstream's `AppRoutes.kt`, this fork's `KunRoutes.kt`, and the recommendation routes that are
     * declared next to their screens.
     */
    private val rootRouteSources = listOf(
        File("../core/navigation/src/main/kotlin/mihon/core/navigation/AppRoutes.kt").path,
        File("src/main/java/mihon/core/navigation/KunRoutes.kt").path,
        File("src/main/java/exh/recs/RecommendsScreen.kt").path,
    )

    private val registry = File("src/main/java/mihon/core/navigation/AppNavGraph.kt")

    /**
     * A route declaration: its (unqualified) name, how the registry names it (a tab is nested in
     * `TopLevelRoute`), where it is declared, and what is needed to load it.
     */
    private data class Declaration(
        val name: String,
        val registeredAs: String,
        val file: String,
        val packageName: String,
        val enclosing: String?,
    )

    @Test
    fun `every declared route is registered and serializable`() {
        val declarations = declarationsByRoot()
        declarations.forEach { (root, found) ->
            if (found.size < root.minimum) {
                fail(
                    "the scan found only ${found.size} route declaration(s) under ${root.path}, expected at " +
                        "least ${root.minimum}: the scan is broken, not the registry",
                )
            }
        }
        val all = declarations.values.flatten()
        if (all.size < MINIMUM_DECLARATIONS) {
            fail("the scan found only ${all.size} route declarations in total, expected at least $MINIMUM_DECLARATIONS")
        }

        val sources = sourceFiles().map { it to it.readText() }

        val unregistered = all.filterNot { declaration ->
            sources.any { (_, text) -> text.hasEntryFor(declaration.registeredAs) }
        }
        if (unregistered.isNotEmpty()) {
            fail(
                "these routes have no entry<…> anywhere, so pushing them throws: " +
                    unregistered.joinToString { "${it.registeredAs} (${it.file})" },
            )
        }

        val notInAppEntries = all
            .filter { it.file in rootRouteSources }
            .filterNot { registry.readText().hasEntryFor(it.registeredAs) }
        if (notInAppEntries.isNotEmpty()) {
            fail(
                "these root-stack routes are missing from AppNavGraph.appEntries(): " +
                    notInAppEntries.joinToString { it.registeredAs },
            )
        }

        val unserializable = all.filter { declaration ->
            val type = runCatching { routeClass(declaration).kotlin }.getOrNull()
            type == null ||
                !NavKey::class.java.isAssignableFrom(type.java) ||
                runCatching { serializer(type.createType()) }.isFailure
        }
        if (unserializable.isNotEmpty()) {
            fail(
                "these routes do not load as serializable NavKeys, so the back stack cannot be persisted: " +
                    unserializable.joinToString { it.registeredAs },
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

    /** The route declarations of each scanned root, keyed by the root they came from. */
    private fun declarationsByRoot(): Map<Root, List<Declaration>> = roots.associateWith(::declarationsIn)

    private fun declarationsIn(root: Root): List<Declaration> {
        val found = mutableListOf<Declaration>()
        for (file in root.directory.walkTopDown()) {
            if (!file.isFile || file.extension != "kt") continue
            val text = file.readText()
            val packageName = PACKAGE.find(text)?.groupValues?.get(1) ?: continue
            for (match in ROUTE.findAll(text)) {
                val supertypes = match.groupValues[2].split(',').map { it.substringAfterLast('.').trim() }
                val supertype = supertypes.firstOrNull { it in SUPERTYPES } ?: continue
                val name = match.groupValues[1]
                // A tab is nested in `TopLevelRoute`, so the registry spells it `TopLevelRoute.History`.
                val isTab = supertype == "TopLevelRoute"
                found += Declaration(
                    name = name,
                    registeredAs = if (isTab) "TopLevelRoute.$name" else name,
                    file = file.path,
                    packageName = packageName,
                    enclosing = if (isTab) "TopLevelRoute" else null,
                )
            }
        }
        return found.distinctBy { it.registeredAs }
    }

    /**
     * The loaded route type. A nested tab's binary name separates its enclosing declaration with `$`, which
     * is how `Class.forName` spells `TopLevelRoute.Library`.
     */
    private fun routeClass(declaration: Declaration): Class<*> {
        val enclosing = declaration.enclosing
        val binaryName = if (enclosing == null) {
            declaration.packageName + "." + declaration.name
        } else {
            declaration.packageName + "." + enclosing + "\$" + declaration.name
        }
        return Class.forName(binaryName)
    }

    private fun sourceFiles(): List<File> = roots
        .flatMap { root -> root.directory.walkTopDown().filter { it.isFile && it.extension == "kt" } }

    /**
     * Whether [this] source registers [route]. `entry<Name>` is the only way a route is registered, and
     * the angle brackets make it unambiguous: a bare `Name` also appears in imports and in pushes.
     */
    private fun String.hasEntryFor(route: String): Boolean = contains("entry<$route>")

    private companion object {
        /** The interfaces a route is dispatched through. */
        val SUPERTYPES = setOf("NavKey", "SearchableSettings", "TopLevelRoute")

        /**
         * A floor for the whole scan, so a pattern that stops matching fails loudly instead of passing on an
         * empty list. Sits below the current total (see the per-root minimums) with room for routes being
         * removed.
         */
        const val MINIMUM_DECLARATIONS = 80

        val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

        /** A `@Serializable` (optionally `internal`, `data`) `class`/`object` and the supertypes it lists. */
        val ROUTE = Regex(
            """@Serializable\s*(?:\r?\n)+\s*(?:internal\s+)?data\s+(?:class|object)\s+(\w+)\s*(?:\([\s\S]*?\))?\s*:\s*([^\n{]+)""",
        )
    }
}
