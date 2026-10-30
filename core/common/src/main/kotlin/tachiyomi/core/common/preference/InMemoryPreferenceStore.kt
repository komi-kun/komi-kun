package tachiyomi.core.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Local-copy implementation of PreferenceStore mostly for test and preview purposes
 *
 * KUN -->
 * **Write-through, keyed.** `getString(key)`/`getBoolean(key)`/… return the *same* wrapper for a
 * key, so `store.getX(key, default).set(value)` is visible to the next `store.getX(key, default).get()`
 * (and to `isSet()`), exactly like the real `AndroidPreferenceStore`. A per-call copy — what this
 * used to hand out — silently dropped every write to a key that was not seeded through the
 * constructor, which forced tests to either seed every key up front or reimplement a store.
 * [InMemoryPreference.changes] follows those writes too.
 * KUN <--
 */
class InMemoryPreferenceStore(
    initialPreferences: Sequence<InMemoryPreference<*>> = sequenceOf(),
) : PreferenceStore {

    // KUN -->
    private val preferences: MutableMap<String, InMemoryPreference<*>> = mutableMapOf()

    init {
        initialPreferences.forEach { preferences[it.key()] = it }
    }

    /** The wrapper for [key], created on first use so later writes have somewhere to land. */
    private fun <T> resolve(key: String, defaultValue: T): InMemoryPreference<T> {
        @Suppress("UNCHECKED_CAST")
        preferences[key]?.let { return it as InMemoryPreference<T> }
        return InMemoryPreference(key, null, defaultValue).also { preferences[key] = it }
    }
    // KUN <--

    override fun getString(key: String, defaultValue: String): Preference<String> =
        resolve(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        resolve(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        resolve(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        resolve(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        resolve(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        resolve(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = resolve(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = resolve(key, defaultValue)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = resolve(key, defaultValue)

    override fun getAll(): Map<String, *> {
        return preferences
    }

    class InMemoryPreference<T>(
        private val key: String,
        initialData: T?,
        private val defaultValue: T,
    ) : Preference<T> {
        // KUN --> A state flow keeps `set()` visible to every wrapper-reader and to `changes()`.
        private val data = MutableStateFlow(initialData)

        override fun key(): String = key

        override fun get(): T = data.value ?: defaultValue()

        override fun isSet(): Boolean = data.value != null

        override fun delete() {
            data.value = null
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = data.map { it ?: defaultValue() }.distinctUntilChanged()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> {
            return changes().stateIn(scope, SharingStarted.Eagerly, get())
        }

        override fun set(value: T) {
            data.value = value
        }
        // KUN <--
    }
}
