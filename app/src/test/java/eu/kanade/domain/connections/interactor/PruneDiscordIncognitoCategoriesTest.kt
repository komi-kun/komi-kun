// KUN -->
package eu.kanade.domain.connections.interactor

import eu.kanade.domain.connections.service.ConnectionsPreferences
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Deleting a category must drop its id from the Discord RPC incognito set, and must leave the set
 * untouched — no write at all — when the id is not in it.
 */
class PruneDiscordIncognitoCategoriesTest {

    private val store = PruneTrackingStore()
    private val connectionsPreferences = ConnectionsPreferences(store)
    private val prune = PruneDiscordIncognitoCategories(connectionsPreferences)

    private fun setIncognito(vararg ids: String) {
        connectionsPreferences.discordRPCIncognitoCategories.set(ids.toSet())
        store.writes.clear()
    }

    @Test
    fun `stale ids are removed and live ids kept`() {
        setIncognito("1", "2", "3")

        prune.prune(listOf(1L, 2L))

        assertEquals(setOf("3"), connectionsPreferences.discordRPCIncognitoCategories.get())
        assertEquals(listOf("discord_rpc_incognito_categories"), store.writes)
    }

    @Test
    fun `an id that is a prefix of another only removes its own entry`() {
        // The set holds strings: deleting category 1 must not touch category 10.
        setIncognito("1", "10")

        prune.prune(listOf(1L))

        assertEquals(setOf("10"), connectionsPreferences.discordRPCIncognitoCategories.get())
    }

    @Test
    fun `prune writes nothing when the set does not carry the id`() {
        setIncognito("7")

        prune.prune(listOf(1L))

        // Not merely "wrote the same value": the store was not touched at all.
        assertEquals(emptyList<String>(), store.writes)
        assertEquals(setOf("7"), connectionsPreferences.discordRPCIncognitoCategories.get())
    }

    @Test
    fun `prune with an empty collection is a no-op`() {
        setIncognito("1", "2")

        prune.prune(emptyList())

        assertEquals(emptyList<String>(), store.writes)
        assertEquals(setOf("1", "2"), connectionsPreferences.discordRPCIncognitoCategories.get())
    }

    @Test
    fun `pruning the last id leaves an empty set rather than the unset default`() {
        setIncognito("8")

        prune.prune(listOf(8L))

        assertEquals(emptySet<String>(), connectionsPreferences.discordRPCIncognitoCategories.get())
        assertEquals(listOf("discord_rpc_incognito_categories"), store.writes)
    }
}

/**
 * Delegating store that records every write, because [InMemoryPreferenceStore] cannot tell "wrote
 * the same value" from "did not write".
 */
private class PruneTrackingStore(
    private val delegate: PreferenceStore = InMemoryPreferenceStore(),
) : PreferenceStore by delegate {

    val writes = mutableListOf<String>()

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
        return track(key, delegate.getStringSet(key, defaultValue))
    }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> {
        return track(key, delegate.getObjectFromString(key, defaultValue, serializer, deserializer))
    }

    private fun <T> track(key: String, preference: Preference<T>): Preference<T> {
        return object : Preference<T> {
            override fun key() = preference.key()
            override fun get() = preference.get()

            override fun set(value: T) {
                writes += key
                preference.set(value)
            }

            override fun isSet() = preference.isSet()

            override fun delete() {
                writes += key
                preference.delete()
            }

            override fun defaultValue() = preference.defaultValue()
            override fun changes() = preference.changes()
            override fun stateIn(scope: CoroutineScope) = preference.stateIn(scope)
        }
    }
}
// KUN <--
