package app.rustmfa.android.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.rustmfa.vault.BlobStore
import app.rustmfa.vault.VaultCorruptException
import java.util.Base64
import kotlinx.coroutines.flow.first

/**
 * [BlobStore] on a Preferences DataStore file named [name], holding the bytes as base64 under one key.
 * The bytes are already encrypted by the caller. The backup rules exclude the app's DataStore files.
 *
 * A damaged file is replaced by preferences holding only a marker key, never by empty preferences (which would
 * read as "nothing stored" and let a first run overwrite the damaged vault silently). [read] throws
 * [VaultCorruptException] while the marker is there, or when the stored value is not base64; [clear] and [write]
 * remove the marker.
 */
class DataStoreBlobStore internal constructor(private val store: DataStore<Preferences>) : BlobStore {
    constructor(context: Context, name: String) : this(storeFor(context.applicationContext, name))

    override suspend fun read(): ByteArray? {
        val prefs = store.data.first()
        if (prefs[CORRUPT] == true) throw VaultCorruptException()
        val encoded = prefs[BLOB] ?: return null
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw VaultCorruptException()
        }
    }

    override suspend fun write(bytes: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        store.edit {
            it.remove(CORRUPT)
            it[BLOB] = encoded
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val BLOB = stringPreferencesKey("blob")
        val CORRUPT = booleanPreferencesKey("corrupt")

        // DataStore allows one instance per file in a process, so instances are shared by name.
        private val stores = HashMap<String, DataStore<Preferences>>()

        fun storeFor(context: Context, name: String): DataStore<Preferences> = synchronized(stores) {
            stores.getOrPut(name) {
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(CORRUPT to true) },
                ) { context.preferencesDataStoreFile(name) }
            }
        }
    }
}
