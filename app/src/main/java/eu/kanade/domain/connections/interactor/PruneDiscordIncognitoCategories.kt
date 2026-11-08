// KUN -->
package eu.kanade.domain.connections.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.connections.service.ConnectionsPreferences

/**
 * Removes the ids of deleted categories from the Discord RPC incognito set
 * ([ConnectionsPreferences.discordRPCIncognitoCategories]), which stores local category ids as
 * strings.
 *
 * This is the steady-state sibling of `PruneCategoryReferences` for the one category-id preference
 * that lives at app level, in `ConnectionsPreferences`, and is therefore out of `domain`'s reach. It
 * runs on both deletion paths — `CategoryViewModel`, which drives `DeleteCategory`, and the non-v2
 * sync merge — because a stale id there already makes an unrelated category incognito once the rowid
 * is reused.
 *
 * The set is written only when it actually contains one of [prune]'s ids, so nothing emits when
 * there is nothing to prune, and an empty [prune] is a no-op.
 */
@Inject
class PruneDiscordIncognitoCategories(
    private val connectionsPreferences: ConnectionsPreferences,
) {

    fun prune(categoryIds: Collection<Long>) {
        if (categoryIds.isEmpty()) return

        val preference = connectionsPreferences.discordRPCIncognitoCategories
        val ids = preference.get()
        val categoryIdStrings = categoryIds.mapTo(mutableSetOf()) { it.toString() }
        val remaining = ids - categoryIdStrings
        if (remaining.size != ids.size) preference.set(remaining)
    }
}
// KUN <--
