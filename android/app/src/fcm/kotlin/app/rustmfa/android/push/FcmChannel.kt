package app.rustmfa.android.push

import android.content.Context
import app.rustmfa.android.net.PushChannel as WireChannel
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Firebase Cloud Messaging. Needs `app/google-services.json` of the owner's Firebase project. */
class FcmChannel : PushChannel {
    override val wire = WireChannel.Fcm

    override suspend fun register(): String? =
        suspendCoroutine { c ->
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { c.resume(if (it.isSuccessful) it.result else null) }
            } catch (_: Exception) {
                c.resume(null)
            }
        }
}

@Suppress("UNUSED_PARAMETER")
fun platformPushChannel(context: Context): PushChannel = FcmChannel()
