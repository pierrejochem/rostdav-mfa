package app.rustmfa.vault

import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Seeds encrypted under a random 32-byte data key. The blob is magic "RMFAV1", a 12-byte nonce, then
 * AES-256-GCM ciphertext and tag, with the magic as associated data. The [KeyWrapper] holds the data key.
 */
class EncryptedSeedStore(
    private val keyWrapper: KeyWrapper,
    private val blobStore: BlobStore,
    private val random: SecureRandom = SecureRandom(),
) : SeedStore {
    private var dataKey: ByteArray? = null

    override suspend fun load(): List<OtpAccount> {
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
                throw e
            }
            replaceKey(fresh)
            return emptyList()
        }
        if (!hasKey) throw VaultKeyLostException()
        val key = keyWrapper.unwrap()
        if (blob == null) {
            replaceKey(key)
            return emptyList()
        }
        try {
            val accounts = decryptBlob(key, blob)
            replaceKey(key)
            return accounts
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        }
    }

    override suspend fun save(accounts: List<OtpAccount>) {
        val key = dataKey ?: throw VaultLockedException()
        val nonce = ByteArray(NONCE_SIZE)
        random.nextBytes(nonce)
        val plain = VaultAccountsJson.encode(accounts)
        try {
            val ct = VaultCipher.encrypt(key, nonce, MAGIC, plain)
            blobStore.write(MAGIC + nonce + ct)
        } finally {
            plain.fill(0)
        }
    }

    override fun lock() {
        dataKey?.fill(0)
        dataKey = null
    }

    private fun replaceKey(key: ByteArray) {
        dataKey?.fill(0)
        dataKey = key
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
