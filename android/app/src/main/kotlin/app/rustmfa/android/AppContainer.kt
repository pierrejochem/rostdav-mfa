package app.rustmfa.android

import android.content.Context
import app.rustmfa.android.crypto.KeyStoreDeviceKey
import app.rustmfa.android.enroll.DataStoreDeviceStore
import app.rustmfa.android.enroll.EnrollmentRepository
import app.rustmfa.android.enroll.PushInfo
import app.rustmfa.android.net.ClockSkew
import app.rustmfa.android.net.OkHttpMfaApi
import app.rustmfa.android.net.PushChannel

/** Hand-wired dependencies; small enough that a DI framework would only hide them. */
class AppContainer(context: Context) {
    val store = DataStoreDeviceStore(context)
    val key = KeyStoreDeviceKey(context)
    val skew = ClockSkew()

    fun enrollmentRepository() =
        EnrollmentRepository(
            apiFor = { server -> OkHttpMfaApi(server, signer = null, skew = skew, allowHttp = BuildConfig.DEBUG) },
            key = key,
            store = store,
            // Task 7 gives the fcm flavor its own provider; polling only until then
            push = { PushInfo(PushChannel.None, "") },
        )
}
