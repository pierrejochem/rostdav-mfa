package app.rustmfa.android.enroll

import app.rustmfa.android.net.PushChannel

/** What the app remembers about its enrollment. No keys: they stay in the Keystore. */
data class Enrollment(
    val server: String,
    val deviceId: String,
    val pushChannel: PushChannel,
    val pushToken: String,
)

interface DeviceStore {
    suspend fun save(e: Enrollment)

    suspend fun load(): Enrollment?

    suspend fun clear()
}
