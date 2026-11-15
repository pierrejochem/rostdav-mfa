package app.rustmfa.android.vault

import app.rustmfa.vault.BlobStore
import app.rustmfa.vault.EncryptedSeedStore
import app.rustmfa.vault.KeyWrapper
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import app.rustmfa.vault.SeedStore
import app.rustmfa.vault.VaultKeyLostException
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class VaultRepositoryTest {
    private val events = mutableListOf<String>()
    private var now = 0L

    private val store =
        object : SeedStore {
            override suspend fun load(): List<OtpAccount> {
                events += "load"
                return emptyList()
            }

            override suspend fun save(accounts: List<OtpAccount>) {}

            override suspend fun exists(): Boolean = false

            override suspend fun reset() {
                events += "store.reset"
            }

            override fun lock() {
                events += "store.lock"
            }
        }
    private val session = VaultSession(store, { now })

    private fun repository(
        authenticate: suspend () -> Unit = { events += "auth" },
        exists: suspend () -> Boolean = { false },
    ) = VaultRepository(session, authenticate, exists)

    @Test
    fun `hasVault delegates to exists`() = runTest {
        assertTrue(repository(exists = { true }).hasVault())
        assertFalse(repository(exists = { false }).hasVault())
    }

    @Test
    fun `unlock authenticates before the store loads`() = runTest {
        val repo = repository()
        repo.unlock()
        assertEquals(listOf("auth", "load"), events)
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `a failed authentication leaves the session locked and the store untouched`() = runTest {
        val repo = repository(authenticate = { throw VaultAuthRequiredException() })
        assertFailsWith<VaultAuthRequiredException> { repo.unlock() }
        assertFalse(session.isUnlocked)
        assertFalse("load" in events)
    }

    @Test
    fun `unlock while unlocked does not prompt again`() = runTest {
        val repo = repository()
        repo.unlock()
        repo.unlock()
        assertEquals(1, events.count { it == "auth" })
        assertEquals(1, events.count { it == "load" })
    }

    @Test
    fun `leaving for 60 seconds locks the session`() = runTest {
        val repo = repository()
        repo.unlock()
        repo.appLeft()
        now = 60_000
        repo.appReturned()
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `returning before the limit keeps it unlocked`() = runTest {
        val repo = repository()
        repo.unlock()
        repo.appLeft()
        now = 59_999
        repo.appReturned()
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `lock locks the session`() = runTest {
        val repo = repository()
        repo.unlock()
        repo.lock()
        assertFalse(session.isUnlocked)
    }

    @Test
    fun `reset delegates and the next unlock authenticates once and opens`() = runTest {
        val repo = repository()
        repo.unlock()
        events.clear()
        repo.reset()
        assertFalse(session.isUnlocked)
        assertEquals(listOf("store.lock", "store.reset"), events)
        events.clear()
        repo.unlock()
        assertEquals(listOf("auth", "load"), events)
        assertTrue(session.isUnlocked)
    }

    /** In-memory Keystore stand-in: the key bytes themselves play the wrapped key. */
    private class MemoryKeys : KeyWrapper {
        var key: ByteArray? = null
        var createCalls = 0

        override suspend fun hasKey() = key != null

        override suspend fun create(dataKey: ByteArray) {
            createCalls++
            key = dataKey.copyOf()
        }

        override suspend fun unwrap(): ByteArray = key?.copyOf() ?: throw VaultKeyLostException()

        override suspend fun destroy() {
            key = null
        }
    }

    private class MemoryBlobs : BlobStore {
        var bytes: ByteArray? = null
        var clearGate: CompletableDeferred<Unit>? = null

        override suspend fun read() = bytes?.copyOf()

        override suspend fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }

        override suspend fun clear() {
            clearGate?.await()
            bytes = null
        }
    }

    private val account = OtpAccount("x", OtpType.TOTP, "Acme", "alice", byteArrayOf(1, 2, 3, 4, 5))

    @Test
    fun `two overlapping unlocks prompt once and create the key once`() = runTest {
        val keys = MemoryKeys()
        val vault = VaultSession(EncryptedSeedStore(keys, MemoryBlobs()), { now })
        val gate = CompletableDeferred<Unit>()
        var prompts = 0
        val repo = VaultRepository(vault, {
            prompts++
            gate.await()
        }, { false })
        val first = async { repo.unlock() }
        val second = async { repo.unlock() }
        runCurrent()
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, prompts)
        assertEquals(1, keys.createCalls)
        assertTrue(vault.isUnlocked)
    }

    @Test
    fun `unlock overlapping reset leaves the keys consistent`() = runTest {
        val keys = MemoryKeys()
        val blobs = MemoryBlobs()
        val existing = VaultSession(EncryptedSeedStore(keys, blobs), { now })
        existing.open()
        existing.add(account)
        existing.lock()

        val vault = VaultSession(EncryptedSeedStore(keys, blobs), { now })
        val authGate = CompletableDeferred<Unit>()
        val repo = VaultRepository(vault, { authGate.await() }, { false })
        val clearGate = CompletableDeferred<Unit>()
        blobs.clearGate = clearGate
        val unlocking = async { runCatching { repo.unlock() } }
        runCurrent()
        val resetting = async { repo.reset() }
        runCurrent()
        authGate.complete(Unit)
        runCurrent()
        clearGate.complete(Unit)
        unlocking.await()
        resetting.await()
        blobs.clearGate = null

        repo.unlock()
        val added = vault.add(account)
        val reloaded = VaultSession(EncryptedSeedStore(keys, blobs), { now })
        reloaded.open()
        assertEquals(listOf(added), reloaded.accounts())
    }
}
