package app.rustmfa.vault

class FakeKeyWrapper(var authOpen: Boolean = true) : KeyWrapper {
    var key: ByteArray? = null
    var createCalls = 0
    var unwrapCalls = 0
    var destroyCalls = 0
    var createThrows = false
    var destroyThrows = false

    /** The array last handed to [create] (the store's own reference). */
    var createdArg: ByteArray? = null

    /** Every array handed out by [unwrap], retained so tests can observe the store zeroing them. */
    val unwrapped = mutableListOf<ByteArray>()

    /** When set, [unwrap] suspends until it completes. */
    var unwrapGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun hasKey(): Boolean = key != null

    override suspend fun create(dataKey: ByteArray) {
        createCalls++
        createdArg = dataKey
        if (createThrows) throw IllegalStateException("create failed")
        if (!authOpen) throw VaultAuthRequiredException()
        key = dataKey.copyOf()
    }

    override suspend fun unwrap(): ByteArray {
        unwrapCalls++
        if (!authOpen) throw VaultAuthRequiredException()
        val k = key ?: throw VaultKeyLostException()
        val out = k.copyOf()
        unwrapped += out
        unwrapGate?.await()
        return out
    }

    override suspend fun destroy() {
        destroyCalls++
        if (destroyThrows) throw IllegalStateException("destroy failed")
        key = null
    }
}

class MemoryBlobStore : BlobStore {
    var bytes: ByteArray? = null
    var writes = 0
    var clearThrows = false

    /** When set, [write] suspends on it before storing. */
    var writeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun read(): ByteArray? = bytes?.copyOf()

    override suspend fun write(bytes: ByteArray) {
        writeGate?.await()
        writes++
        this.bytes = bytes.copyOf()
    }

    override suspend fun clear() {
        if (clearThrows) throw IllegalStateException("clear failed")
        bytes = null
    }
}
