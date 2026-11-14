package app.rustmfa.android.push

import app.rustmfa.android.AppContainer
import app.rustmfa.android.net.PushChannel as WireChannel
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/** A push carries only a challenge id; the details come from the signed GET. */
class RustMfaMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val id = PushPayload.challengeId(message.data) ?: return
        val app = AppContainer.get(applicationContext)
        runBlocking {
            val e = runCatching { app.store.load() }.getOrNull() ?: return@runBlocking
            runCatching { app.notifier(e).onPush(id) }
        }
    }

    override fun onNewToken(token: String) {
        val app = AppContainer.get(applicationContext)
        runBlocking {
            val e = runCatching { app.store.load() }.getOrNull() ?: return@runBlocking
            runCatching { app.signedApi(e).updatePushToken(e.deviceId, WireChannel.Fcm, token) }
        }
    }
}
