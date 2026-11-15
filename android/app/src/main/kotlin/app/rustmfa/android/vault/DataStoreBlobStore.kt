package app.rustmfa.android.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.rustmfa.vault.BlobStore
import java.util.Base64
import kotlinx.coroutines.flow.first

/**
 * [BlobStore] on a Preferences DataStore file named [name], holding the bytes as base64 under one key.
 * The bytes are already encrypted by the caller. The backup rules exclude the app's DataStore files.
 */
class DataStoreBlobStore(context: Context, name: String) : BlobStore {
    private val store = storeFor(context.applicationContext, name)

    override suspend fun read(): ByteArray? = store.data.first()[BLOB]?.let { Base64.getDecoder().decode(it) }

    override suspend fun write(bytes: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        store.edit { it[BLOB] = encoded }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val BLOB = stringPreferencesKey("blob")

        // DataStore allows one instance per file in a process, so instances are shared by name.
        private val stores = HashMap<String, DataStore<Preferences>>()

        fun storeFor(context: Context, name: String): DataStore<Preferences> = synchronized(stores) {
            stores.getOrPut(name) { PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) } }
        }
    }
}
