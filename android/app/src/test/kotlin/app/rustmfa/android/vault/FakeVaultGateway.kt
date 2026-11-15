package app.rustmfa.android.vault

import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.SeedStore
import app.rustmfa.vault.VaultSession

/**
 * A [VaultGateway] over a real [VaultSession] with an in-memory store. It records the calls in order and can be
 * told to fail the next unlocks or mutations.
 */
class FakeVaultGateway(clock: () -> Long) : VaultGateway {
    val calls = mutableListOf<String>()
    val unlockFailures = ArrayDeque<Throwable>()
    val mutationFailures = ArrayDeque<Throwable>()

    /** The stored vault; null when there is none. */
    var stored: List<OtpAccount>? = null

    private val store =
        object : SeedStore {
            override suspend fun load(): List<OtpAccount> = stored.orEmpty().map { it.copy(secret = it.secret.copyOf()) }

            override suspend fun save(accounts: List<OtpAccount>) {
                stored = accounts.map { it.copy(secret = it.secret.copyOf()) }
            }

            override suspend fun exists(): Boolean = stored != null

            override suspend fun reset() {
                stored = null
            }
        }

    val session = VaultSession(store, clock)

    override val isUnlocked: Boolean get() = session.isUnlocked

    override suspend fun hasVault(): Boolean = stored != null

    override suspend fun unlock() {
        calls += "unlock"
        unlockFailures.removeFirstOrNull()?.let { throw it }
        if (!session.isUnlocked) session.open()
    }

    override fun lock() {
        calls += "lock"
        session.lock()
    }

    override suspend fun reset() {
        calls += "reset"
        session.reset()
    }

    override fun appLeft() = session.appLeft()

    override fun appReturned() = session.appReturned()

    override fun accounts(): List<OtpAccount> = session.accounts()

    override fun code(id: String, epochSeconds: Long): String = session.code(id, epochSeconds)

    override suspend fun nextHotp(id: String): String {
        calls += "nextHotp"
        failIfTold()
        return session.nextHotp(id)
    }

    override suspend fun import(accounts: List<OtpAccount>): ImportResult {
        calls += "import"
        failIfTold()
        return session.import(accounts)
    }

    override suspend fun update(account: OtpAccount) {
        calls += "update"
        failIfTold()
        session.update(account)
    }

    override suspend fun remove(id: String) {
        calls += "remove"
        failIfTold()
        session.remove(id)
    }

    private fun failIfTold() {
        mutationFailures.removeFirstOrNull()?.let { throw it }
    }
}
