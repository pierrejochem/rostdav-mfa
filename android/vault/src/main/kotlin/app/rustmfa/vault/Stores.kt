package app.rustmfa.vault

interface SeedStore {
    suspend fun load(): List<OtpAccount>
    suspend fun save(accounts: List<OtpAccount>)

    /**
     * True when a vault blob exists. Never decrypts, never needs authentication, and never throws for a missing
     * blob.
     */
    suspend fun exists(): Boolean

    /** Drops the in-memory data key. */
    fun lock() {}

    /**
     * Drops the data key and deletes the stored blob and wrapped key, so the next [load] is a first use.
     * Works from any state, including after a failed [load].
     */
    suspend fun reset()
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
