package app.rustmfa.android

import android.content.Context
import app.rustmfa.android.crypto.KeyStoreDeviceKey
import app.rustmfa.android.enroll.DataStoreDeviceStore
import app.rustmfa.android.enroll.EnrollmentRepository
import app.rustmfa.android.enroll.PushInfo
import app.rustmfa.android.domain.ChallengeFlow
import app.rustmfa.android.domain.GuardedApi
import app.rustmfa.android.domain.SessionGuard
import app.rustmfa.android.net.MfaApi
import app.rustmfa.android.domain.Delayer
import app.rustmfa.android.enroll.Enrollment
import app.rustmfa.android.net.ClockSkew
import app.rustmfa.android.net.RequestSigner
import android.os.SystemClock
import app.rustmfa.android.vault.DataStoreBlobStore
import app.rustmfa.android.vault.KeystoreKeyWrapper
import app.rustmfa.android.vault.VaultRepository
import app.rustmfa.vault.EncryptedSeedStore
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import app.rustmfa.android.net.OkHttpMfaApi
import app.rustmfa.android.push.AndroidNotificationSink
import app.rustmfa.android.push.ChallengeNotifier
import app.rustmfa.android.push.PendingPoller
import app.rustmfa.android.push.PushChannel
import app.rustmfa.android.push.platformPushChannel

/** Hand-wired dependencies; small enough that a DI framework would only hide them. */
class AppContainer private constructor(private val context: Context) {
    val store = DataStoreDeviceStore(context)
    val key = KeyStoreDeviceKey(context)
    val skew = ClockSkew()
    val guard = SessionGuard(store, key)

    /** Set by the activity: the server revoked this phone, the guard has wiped it, the screen must restart. */
    @Volatile var onSessionEnded: (() -> Unit)? = null
    val pushChannel: PushChannel by lazy { platformPushChannel(context) }
    private val sink by lazy { AndroidNotificationSink(context) }
    private var notifierFor: Pair<String, ChallengeNotifier>? = null

    /** One notifier per enrollment, shared by the app and the push service so they do not post twice. */
    @Synchronized
    fun notifier(e: Enrollment): ChallengeNotifier =
        notifierFor?.takeIf { it.first == e.deviceId }?.second
            ?: ChallengeNotifier(signedApi(e), sink, skew).also { notifierFor = e.deviceId to it }

    /** Set by the UI when it has an activity; until then an unlock fails with [VaultAuthRequiredException]. */
    @Volatile var authenticator: suspend () -> Unit = { throw VaultAuthRequiredException() }

    /** The auto-lock clock is monotonic, so changing the wall clock cannot keep the vault open. */
    val vault: VaultRepository by lazy {
        val seedStore = EncryptedSeedStore(KeystoreKeyWrapper(context), DataStoreBlobStore(context, "vault"))
        VaultRepository(
            VaultSession(seedStore, SystemClock::elapsedRealtime),
            { authenticator() },
            { seedStore.exists() },
        )
    }

    fun poller(e: Enrollment) = PendingPoller(signedApi(e), skew, Delayer { delay(it) })

    fun enrollmentRepository() =
        EnrollmentRepository(
            apiFor = { server -> OkHttpMfaApi(server, signer = null, skew = skew, allowHttp = BuildConfig.DEBUG) },
            key = key,
            store = store,
            push = {
                // a push service that gives no token (no Google file, no Play services) falls back to polling
                val token = pushChannel.register()
                if (token.isNullOrEmpty()) PushInfo(app.rustmfa.android.net.PushChannel.None, "") else PushInfo(pushChannel.wire, token)
            },
        )

    /** A signed client for the enrolled server, on the corrected clock. */
    fun signedApi(e: Enrollment): MfaApi =
        GuardedApi(
            OkHttpMfaApi(e.server.toHttpUrl(), RequestSigner(e.deviceId, key, skew), skew, allowHttp = BuildConfig.DEBUG),
            guard,
        ) { onSessionEnded?.invoke() }

    fun challengeFlow(e: Enrollment) = ChallengeFlow(signedApi(e), key, e.deviceId, skew, Delayer { delay(it) })

    /** The phone forgets this enrollment: keys and stored data go. The user connects again. */
    fun resetEnrollment() {
        runCatching { key.delete() }
        runBlocking { runCatching { store.clear() } }
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) { instance ?: AppContainer(context.applicationContext).also { instance = it } }
    }
}
