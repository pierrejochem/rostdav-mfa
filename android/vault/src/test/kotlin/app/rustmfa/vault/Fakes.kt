package app.rustmfa.vault

class FakeKeyWrapper(var authOpen: Boolean = true) : KeyWrapper {
    var key: ByteArray? = null
    var createCalls = 0
    var unwrapCalls = 0

    override suspend fun hasKey(): Boolean = key != null

    override suspend fun create(dataKey: ByteArray) {
        createCalls++
        if (!authOpen) throw VaultAuthRequiredException()
        key = dataKey.copyOf()
    }

    override suspend fun unwrap(): ByteArray {
        unwrapCalls++
        if (!authOpen) throw VaultAuthRequiredException()
        return (key ?: throw VaultKeyLostException()).copyOf()
    }

    override suspend fun destroy() {
        key = null
    }
}

class MemoryBlobStore : BlobStore {
    var bytes: ByteArray? = null
    var writes = 0

    override suspend fun read(): ByteArray? = bytes?.copyOf()

    override suspend fun write(bytes: ByteArray) {
        writes++
        this.bytes = bytes.copyOf()
    }

    override suspend fun clear() {
        bytes = null
    }
}
