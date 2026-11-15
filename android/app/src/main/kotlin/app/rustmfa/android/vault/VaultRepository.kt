package app.rustmfa.android.vault

import app.rustmfa.vault.VaultSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's entry to the vault: [unlock] makes the user authenticate (opening the Keystore window), then loads the
 * session. [authenticate] throws `VaultAuthRequiredException` when the user cancels or fails.
 *
 * [unlock] and [reset] are serialised by a mutex: two overlapping unlocks show one prompt and create one key, and
 * a reset never runs between an unlock's load and its install (which would leave the session open under a
 * destroyed key). So [reset] waits for an unlock whose prompt is showing. [lock], [appLeft] and [appReturned] never
 * take the mutex.
 */
class VaultRepository(
    val session: VaultSession,
    private val authenticate: suspend () -> Unit,
    private val exists: suspend () -> Boolean,
) {
    private val gate = Mutex()

    /** True when a vault is stored. Needs no authentication and never decrypts. */
    suspend fun hasVault(): Boolean = exists()

    /** Does nothing when the session is already unlocked, so no second prompt appears. */
    suspend fun unlock() {
        if (session.isUnlocked) return
        gate.withLock {
            // re-checked: an unlock that waited here behind another one finds the session open
            if (session.isUnlocked) return
            authenticate()
            session.open()
        }
    }

    fun appLeft() = session.appLeft()

    fun appReturned() = session.appReturned()

    fun lock() = session.lock()

    /** Deletes the vault and its key (for a restore or a new vault); the next [unlock] creates an empty one. */
    suspend fun reset() = gate.withLock { session.reset() }
}
