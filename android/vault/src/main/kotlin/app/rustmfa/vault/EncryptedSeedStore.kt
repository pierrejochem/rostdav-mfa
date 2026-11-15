package app.rustmfa.vault

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
 * so the store never ends up unlocked after [lock] returned.
 */
class EncryptedSeedStore(
    private val keyWrapper: KeyWrapper,
    private val blobStore: BlobStore,
    private val random: SecureRandom = SecureRandom(),
) : SeedStore {
    private val monitor = Any()
    private var dataKey: ByteArray? = null
    private var generation = 0L

    override suspend fun load(): List<OtpAccount> {
        val gen = synchronized(monitor) { generation }
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
        val blob = try {
            // Non-suspending, so the monitor is held for the whole use of the key.
            synchronized(monitor) {
                val key = dataKey ?: throw VaultLockedException()
                MAGIC + nonce + VaultCipher.encrypt(key, nonce, MAGIC, plain)
            }
        } finally {
            plain.fill(0)
        }
        blobStore.write(blob)
    }

    override fun lock() {
        synchronized(monitor) {
            generation++
            dataKey?.fill(0)
            dataKey = null
        }
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
