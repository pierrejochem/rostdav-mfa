package app.rustmfa.vault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Seeds encrypted under a random 32-byte data key. The blob is magic "RMFAV1", a 12-byte nonce, then
 * AES-256-GCM ciphertext and tag, with the magic as associated data. The [KeyWrapper] holds the data key.
 *
 * Threading: [lock] may be called from any thread. The in-memory data key and a lock generation counter are
 * guarded by a private monitor: [lock], taking the key for use in [save], and installing a key in [load] all
 * happen under it, so [lock] never zeroes a key mid-encryption. [load] captures the generation before its first
 * suspension; if [lock] ran meanwhile, the freshly obtained key is zeroed and [VaultLockedException] is thrown,
 * so the store never ends up unlocked after [lock] returned. Loads are serialised by a private mutex that
 * [lock] and [reset] do not take; a load that waited for it while [lock] ran fails the same way.
 */
class EncryptedSeedStore(
    private val keyWrapper: KeyWrapper,
    private val blobStore: BlobStore,
    private val random: SecureRandom = SecureRandom(),
) : SeedStore {
    private val monitor = Any()
    private var dataKey: ByteArray? = null
    private var generation = 0L

    // Orders blob writes against reset's clear: held around a save's generation re-check plus write, and around clear.
    private val blobLock = Mutex()

    // Serialises loads, so two first-run loads cannot both create a key (the second would replace the first's
    // wrapped key while the first's data key stays in memory). reset() and lock() never take it: they must not
    // wait for a load suspended in a prompt-gated unwrap.
    private val loadLock = Mutex()

    override suspend fun load(): List<OtpAccount> {
        val gen = synchronized(monitor) { generation }
        return loadLock.withLock { loadLocked(gen) }
    }

    private suspend fun loadLocked(gen: Long): List<OtpAccount> {
        val blob = blobStore.read()
        val hasKey = keyWrapper.hasKey()
        if (blob == null && !hasKey) {
            val fresh = ByteArray(KEY_SIZE)
            random.nextBytes(fresh)
            try {
                // Created at once, while the Keystore auth window is open.
                keyWrapper.create(fresh)
            } catch (e: Throwable) {
                fresh.fill(0)
                try {
                    keyWrapper.destroy()
                } catch (d: Throwable) {
                    e.addSuppressed(d)
                }
                throw e
            }
            install(fresh, gen)
            return emptyList()
        }
        if (!hasKey) throw VaultKeyLostException()
        val key = keyWrapper.unwrap()
        if (blob == null) {
            install(key, gen)
            return emptyList()
        }
        try {
            val accounts = decryptBlob(key, blob)
            install(key, gen)
            return accounts
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        }
    }

    override suspend fun save(accounts: List<OtpAccount>) {
        val nonce = ByteArray(NONCE_SIZE)
        random.nextBytes(nonce)
        val plain = VaultAccountsJson.encode(accounts)
        var gen = 0L
        val blob = try {
            // Non-suspending, so the monitor is held for the whole use of the key.
            synchronized(monitor) {
                val key = dataKey ?: throw VaultLockedException()
                gen = generation
                MAGIC + nonce + VaultCipher.encrypt(key, nonce, MAGIC, plain)
            }
        } finally {
            plain.fill(0)
        }
        blobLock.withLock {
            // A reset (or lock) since the key was taken must not be followed by a stale write.
            if (synchronized(monitor) { generation } != gen) throw VaultLockedException()
            blobStore.write(blob)
        }
    }

    override fun lock() {
        synchronized(monitor) {
            generation++
            dataKey?.fill(0)
            dataKey = null
        }
    }

    override suspend fun reset() {
        // Same discipline as lock(): a load suspended meanwhile sees the new generation and zeroes its key.
        lock()
        var failure: Throwable? = null
        try {
            blobLock.withLock { blobStore.clear() }
        } catch (e: Throwable) {
            failure = e
        }
        try {
            keyWrapper.destroy()
        } catch (e: Throwable) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        if (failure != null) throw failure
    }

    private fun install(key: ByteArray, gen: Long) {
        synchronized(monitor) {
            if (generation != gen) {
                key.fill(0)
                throw VaultLockedException()
            }
            dataKey?.fill(0)
            dataKey = key
        }
    }

    private fun decryptBlob(key: ByteArray, blob: ByteArray): List<OtpAccount> {
        if (blob.size < MAGIC.size + NONCE_SIZE + TAG_SIZE) throw VaultCorruptException()
        if (!blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw VaultCorruptException()
        val nonce = blob.copyOfRange(MAGIC.size, MAGIC.size + NONCE_SIZE)
        val ct = blob.copyOfRange(MAGIC.size + NONCE_SIZE, blob.size)
        val plain = try {
            VaultCipher.decrypt(key, nonce, MAGIC, ct)
        } catch (e: AEADBadTagException) {
            throw VaultCorruptException()
        }
        try {
            return VaultAccountsJson.decode(plain)
        } catch (e: IllegalArgumentException) {
            throw VaultCorruptException()
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        val MAGIC = "RMFAV1".toByteArray(Charsets.US_ASCII)
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
    }
}
