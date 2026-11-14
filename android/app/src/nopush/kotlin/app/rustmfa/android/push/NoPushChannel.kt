package app.rustmfa.android.push

import android.content.Context
import app.rustmfa.android.net.PushChannel as WireChannel

/** The default build: no push service, so the phone polls. The server gets channel `none` and an empty token. */
class NoPushChannel : PushChannel {
    override val wire = WireChannel.None

    override suspend fun register(): String? = null
}

/** Each flavor has its own; the shared code only sees [PushChannel]. */
@Suppress("UNUSED_PARAMETER")
fun platformPushChannel(context: Context): PushChannel = NoPushChannel()
