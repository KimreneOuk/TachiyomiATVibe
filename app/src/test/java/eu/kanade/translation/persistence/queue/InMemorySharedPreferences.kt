package eu.kanade.translation.persistence.queue

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] stand-in for plain-JVM store tests
 * (there is no Robolectric on this source set, and the unit-test android.jar
 * throws on real implementations). Editor mutations are buffered and land in
 * the visible map on [SharedPreferences.Editor.commit]/apply, mirroring the
 * `androidx.core.content.edit(commit = true)` write path the translation
 * stores use.
 */
internal class InMemorySharedPreferences : SharedPreferences {

    /** Visible so tests can plant malformed/corrupt entries directly. */
    val entries = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(entries)

    override fun getString(key: String, defValues: String?): String? =
        entries[key] as? String ?: defValues

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String> =
        @Suppress("UNCHECKED_CAST")
        (entries[key] as? MutableSet<String>)
            ?: defValues ?: LinkedHashSet()

    override fun getInt(key: String, defValue: Int): Int = entries[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = entries[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = entries[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        entries[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = entries.containsKey(key)

    override fun edit(): SharedPreferences.Editor = BufferingEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class BufferingEditor : SharedPreferences.Editor {

        private var cleared = false
        private val pending = LinkedHashMap<String, Any?>()
        private val removedKeys = LinkedHashSet<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            pending[key] = values
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            pending[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply {
            pending.remove(key)
            removedKeys.add(key)
        }

        override fun clear(): SharedPreferences.Editor = apply {
            cleared = true
            pending.clear()
            removedKeys.clear()
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (cleared) entries.clear()
            entries.putAll(pending)
            // A removed key must also erase an already-committed value, like
            // the real Editor contract.
            removedKeys.forEach { entries.remove(it) }
        }
    }
}
