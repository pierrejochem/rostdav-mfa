package app.rustmfa.android.vault

import app.rustmfa.vault.VaultSession

/**
 * The app's entry to the vault: [unlock] makes the user authenticate (opening the Keystore window), then loads the
 * session. [authenticate] throws `VaultAuthRequiredException` when the user cancels or fails.
 */
class VaultRepository(val session: VaultSession, private val authenticate: suspend () -> Unit) {
    /** Does nothing when the session is already unlocked, so no second prompt appears. */
    suspend fun unlock() {
        if (session.isUnlocked) return
        authenticate()
        session.open()
    }

    fun appLeft() = session.appLeft()

    fun appReturned() = session.appReturned()

    fun lock() = session.lock()
}
