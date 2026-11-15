package app.rustmfa.vault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImportResult(val added: Int, val skipped: Int)

/**
 * The unlocked, in-memory view of the vault, with an auto-lock after [autoLockMillis] in the background.
 *
 * Threading: [appLeft], [appReturned] and [lock] may run on the UI thread while [add], [update], [remove],
 * [import] and [nextHotp] are suspended inside [SeedStore.save]. The state (accounts, unlocked flag, generation)
 * is guarded by a private monitor that is never held across a suspension point or a store call. [lock]
 * increments the generation; an operation captures it before its store call and, when the call returns,
 * commits only if it is unchanged. Otherwise it zeroes what it built, leaves the session locked and throws
 * [VaultLockedException], so an operation in flight when [lock] ran cannot resurrect the session. A mutation is
 * committed to memory only after its save succeeded, so a failed save leaves the previous list in place.
 * Mutations are serialized by a coroutine [Mutex] (held across the save, which is fine: the monitor is the one
 * that must not be). [lock], [appLeft], [appReturned], [accounts] and [code] never take it. A mutation records the
 * generation before queueing, so one that waited while [lock] ran fails without calling the store.
 *
 * The session owns its secret arrays: secrets are copied in and out, and zeroed on [lock].
 */
class VaultSession(
    private val store: SeedStore,
    private val clock: () -> Long,
    private val autoLockMillis: Long = 60_000,
    private val newId: () -> String = { newAccountId() },
) {
    private val monitor = Any()
    private val mutations = Mutex()
    private var current: List<OtpAccount>? = null
    private var generation = 0L
    private var leftAt: Long? = null

    val isUnlocked: Boolean get() = synchronized(monitor) { current != null }

    suspend fun open() {
        val gen = synchronized(monitor) { generation }
        val loaded = store.load()
        val owned = loaded.map { it.owned() }
        synchronized(monitor) {
            if (generation != gen) {
                owned.zero()
                throw VaultLockedException()
            }
            current?.zero()
            current = owned
            leftAt = null
        }
    }

    fun accounts(): List<OtpAccount> = synchronized(monitor) { requireOpen().map { it.owned() } }

    suspend fun add(account: OtpAccount): OtpAccount {
        val created = account.owned().copy(id = newId())
        mutate { it + created }
        return created.owned()
    }

    suspend fun update(account: OtpAccount) {
        val replacement = account.owned()
        try {
            mutate { list ->
                if (list.none { it.id == replacement.id }) throw NoSuchElementException("no such account")
                list.map { if (it.id == replacement.id) replacement else it }
            }
        } catch (e: Throwable) {
            replacement.secret.fill(0)
            throw e
        }
    }

    suspend fun remove(id: String) {
        mutate { list ->
            if (list.none { it.id == id }) throw NoSuchElementException("no such account")
            list.filter { it.id != id }
        }
    }

    suspend fun import(accounts: List<OtpAccount>): ImportResult {
        var added = 0
        var skipped = 0
        mutate { list ->
            added = 0
            skipped = 0
            val out = list.toMutableList()
            for (a in accounts) {
                if (out.any { sameAccount(it, a) }) {
                    skipped++
                } else {
                    out += a.owned().copy(id = newId())
                    added++
                }
            }
            if (added == 0) null else out
        }
        return ImportResult(added, skipped)
    }

    fun code(id: String, epochSeconds: Long): String = synchronized(monitor) {
        val account = requireOpen().firstOrNull { it.id == id } ?: throw NoSuchElementException("no such account")
        require(account.type == OtpType.TOTP) { "not a TOTP account" }
        Otp.totp(account, epochSeconds)
    }

    suspend fun nextHotp(id: String): String {
        var code = ""
        mutate { list ->
            val account = list.firstOrNull { it.id == id } ?: throw NoSuchElementException("no such account")
            require(account.type == OtpType.HOTP) { "not a HOTP account" }
            code = Otp.hotp(account.secret, account.algorithm, account.digits, account.counter)
            list.map { if (it.id == id) it.copy(counter = it.counter + 1) else it }
        }
        return code
    }

    fun appLeft() {
        synchronized(monitor) {
            if (current != null) leftAt = clock()
        }
    }

    fun appReturned() {
        val expired = synchronized(monitor) {
            val left = leftAt ?: return
            leftAt = null
            current != null && clock() - left >= autoLockMillis
        }
        if (expired) lock()
    }

    fun lock() {
        synchronized(monitor) {
            generation++
            current?.zero()
            current = null
            leftAt = null
        }
        store.lock()
    }

    /**
     * Applies [change] to the current list, saves the result, then commits it unless [lock] ran meanwhile.
     * [change] returns the new list (sharing unchanged accounts with the old one) or null for "nothing to save".
     */
    private suspend fun mutate(change: (List<OtpAccount>) -> List<OtpAccount>?) {
        val gen = synchronized(monitor) { requireOpen(); generation }
        mutations.withLock {
            val snapshot = synchronized(monitor) {
                if (generation != gen) throw VaultLockedException()
                requireOpen().map { it.owned() }
            }
            val next = try {
                change(snapshot)
            } catch (e: Throwable) {
                snapshot.zero()
                throw e
            }
            if (next == null) {
                snapshot.zero()
                return
            }
            try {
                store.save(next.map { it.owned() })
            } catch (e: Throwable) {
                next.zero()
                snapshot.zero()
                throw e
            }
            synchronized(monitor) {
                if (generation != gen || current == null) {
                    next.zero()
                    snapshot.zero()
                    throw VaultLockedException()
                }
                current?.zero()
                current = next
            }
            // Arrays of accounts the change replaced or dropped were copies owned by this call.
            snapshot.filter { old -> next.none { it.secret === old.secret } }.zero()
        }
    }

    private fun requireOpen(): List<OtpAccount> = current ?: throw VaultLockedException()

    private fun sameAccount(a: OtpAccount, b: OtpAccount): Boolean =
        a.issuer == b.issuer && a.label == b.label && a.algorithm == b.algorithm &&
            a.digits == b.digits && a.period == b.period && a.secret.contentEquals(b.secret)

    private fun OtpAccount.owned(): OtpAccount = copy(secret = secret.copyOf())

    private fun List<OtpAccount>.zero() = forEach { it.secret.fill(0) }
}
