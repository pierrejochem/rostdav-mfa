package app.rustmfa.android.enroll

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.rustmfa.android.net.PushChannel
import kotlinx.coroutines.flow.first

private val Context.enrollmentStore by preferencesDataStore(name = "enrollment")

/** [DeviceStore] on Preferences DataStore. The backup rules exclude this file. */
class DataStoreDeviceStore(context: Context) : DeviceStore {
    private val store = context.applicationContext.enrollmentStore

    override suspend fun save(e: Enrollment) {
        store.edit {
            it[SERVER] = e.server
            it[DEVICE_ID] = e.deviceId
            it[CHANNEL] = e.pushChannel.name
            it[TOKEN] = e.pushToken
            it[RECONNECT] = e.needsReconnect
        }
    }

    override suspend fun load(): Enrollment? {
        val p = store.data.first()
        val channel = p[CHANNEL]?.let { n -> PushChannel.entries.firstOrNull { it.name == n } } ?: return null
        return Enrollment(p[SERVER] ?: return null, p[DEVICE_ID] ?: return null, channel, p[TOKEN] ?: return null, p[RECONNECT] ?: false)
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val CHANNEL = stringPreferencesKey("push_channel")
        val TOKEN = stringPreferencesKey("push_token")
        val RECONNECT = booleanPreferencesKey("needs_reconnect")
    }
}
