package app.rustmfa.vault

interface SeedStore {
    suspend fun load(): List<OtpAccount>
    suspend fun save(accounts: List<OtpAccount>)

    /** Drops the in-memory data key. */
    fun lock() {}
}

/** Wraps the random data key with the Keystore. [create] and [unwrap] throw [VaultAuthRequiredException] outside the auth window. */
interface KeyWrapper {
    suspend fun hasKey(): Boolean
    suspend fun create(dataKey: ByteArray)
    suspend fun unwrap(): ByteArray
    suspend fun destroy()
}

interface BlobStore {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    suspend fun clear()
}
