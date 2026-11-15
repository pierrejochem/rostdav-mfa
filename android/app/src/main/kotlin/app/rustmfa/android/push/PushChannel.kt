package app.rustmfa.android.push

import app.rustmfa.android.net.PushChannel as WireChannel

/** A way the server can wake the phone. [register] gives the push token, or null if there is none. */
interface PushChannel {
    val wire: WireChannel

    suspend fun register(): String?
}
