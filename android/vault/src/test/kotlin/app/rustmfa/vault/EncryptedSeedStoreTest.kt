package app.rustmfa.vault

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedSeedStoreTest {
    private val keys = FakeKeyWrapper()
    private val blobs = MemoryBlobStore()
    private fun store() = EncryptedSeedStore(keys, blobs)

    private val accounts = listOf(
        OtpAccount(
            id = "a1", type = OtpType.TOTP, issuer = "Example", label = "alice",
            secret = "12345678901234567890".toByteArray(),
        ),
        OtpAccount(id = "b2", type = OtpType.HOTP, issuer = "", label = "bob", secret = byteArrayOf(1, 2, 3), counter = 7),
    )

    @Test
    fun firstUseCreatesKeyAndReturnsEmptyWithoutBlob() = runTest {
        assertEquals(emptyList(), store().load())
        assertTrue(keys.hasKey())
        assertEquals(1, keys.createCalls)
        assertNull(blobs.bytes)
    }

    @Test
    fun saveThenReloadRoundTrips() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        assertEquals(accounts, store().load())
    }

    @Test
    fun blobStartsWithMagic() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        assertContentEquals("RMFAV1".toByteArray(), blobs.bytes!!.copyOfRange(0, 6))
    }

    @Test
    fun authClosedOnExistingKeyThrowsAuthRequired() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        keys.authOpen = false
        assertFailsWith<VaultAuthRequiredException> { store().load() }
    }

    @Test
    fun authClosedOnFirstUseLeavesNoKey() = runTest {
        keys.authOpen = false
        assertFailsWith<VaultAuthRequiredException> { store().load() }
        assertFalse(keys.hasKey())
    }

    @Test
    fun blobWithoutKeyIsKeyLost() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        keys.destroy()
        assertFailsWith<VaultKeyLostException> { store().load() }
    }

    @Test
    fun existsIsFalseOnAFreshStore() = runTest {
        assertFalse(store().exists())
    }

    @Test
    fun existsIsTrueAfterASave() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        assertTrue(store().exists())
    }

    @Test
    fun existsIsFalseAfterReset() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        s.reset()
        assertFalse(s.exists())
    }

    @Test
    fun existsIsTrueWhenTheBlobIsCorrupt() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val b = blobs.bytes!!
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        assertTrue(store().exists())
    }

    @Test
    fun existsDoesNotTouchTheKeyWrapper() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val creates = keys.createCalls
        val unwraps = keys.unwrapCalls
        keys.authOpen = false
        assertTrue(store().exists())
        assertEquals(creates, keys.createCalls)
        assertEquals(unwraps, keys.unwrapCalls)
        assertFalse(EncryptedSeedStore(FakeKeyWrapper(), MemoryBlobStore()).exists())
    }

    @Test
    fun flippedByteIsCorrupt() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val b = blobs.bytes!!
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        assertFailsWith<VaultCorruptException> { store().load() }
    }

    @Test
    fun flippedMagicByteIsCorrupt() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val b = blobs.bytes!!
        b[0] = (b[0].toInt() xor 1).toByte()
        assertFailsWith<VaultCorruptException> { store().load() }
    }

    @Test
    fun blobUnderOtherKeyIsCorrupt() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        keys.key = ByteArray(32) { 9 }
        assertFailsWith<VaultCorruptException> { store().load() }
    }

    @Test
    fun shortBlobIsCorrupt() = runTest {
        store().load()
        blobs.bytes = "RMFAV1".toByteArray() + ByteArray(26)
        assertFailsWith<VaultCorruptException> { store().load() }
    }

    @Test
    fun emptyBlobIsCorrupt() = runTest {
        store().load()
        blobs.bytes = ByteArray(0)
        assertFailsWith<VaultCorruptException> { store().load() }
    }

    @Test
    fun wrongMagicIsCorrupt() = runTest {
        store().load()
        blobs.bytes = "XXXXXX".toByteArray() + ByteArray(12 + 16)
        assertFailsWith<VaultCorruptException> { store().load() }
    }

    @Test
    fun saveBeforeLoadIsLocked() = runTest {
        assertFailsWith<VaultLockedException> { store().save(accounts) }
    }

    @Test
    fun lockThenSaveIsLockedAndLoadUnwrapsAgain() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        s.load()
        assertEquals(1, keys.unwrapCalls)
        s.lock()
        assertFailsWith<VaultLockedException> { s.save(accounts) }
        assertEquals(accounts, s.load())
        assertEquals(2, keys.unwrapCalls)
    }

    @Test
    fun eachSaveUsesFreshNonce() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val first = assertNotNull(blobs.bytes)
        s.save(accounts)
        val second = assertNotNull(blobs.bytes)
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(6, 18).contentEquals(second.copyOfRange(6, 18)))
    }


    @Test
    fun resetAfterKeyLostThenLoadIsEmptyWithNewKey() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        keys.key = null
        val fresh = store()
        assertFailsWith<VaultKeyLostException> { fresh.load() }
        fresh.reset()
        assertNull(blobs.bytes)
        assertEquals(emptyList(), fresh.load())
        assertTrue(keys.hasKey())
        fresh.save(accounts)
        assertEquals(accounts, store().load())
    }

    @Test
    fun resetAfterCorruptBlobThenLoadIsEmpty() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        keys.key = ByteArray(32) { 9 }
        val fresh = store()
        assertFailsWith<VaultCorruptException> { fresh.load() }
        fresh.reset()
        assertEquals(emptyList(), fresh.load())
    }

    @Test
    fun resetZeroesTheInMemoryKey() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val s2 = store()
        s2.load()
        val held = keys.unwrapped.last()
        assertFalse(held.all { it == 0.toByte() })
        s2.reset()
        assertZero(held)
        assertFailsWith<VaultLockedException> { s2.save(accounts) }
    }

    @Test
    fun resetDuringSuspendedLoadLeavesStoreLockedAndKeyZeroed() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        keys.unwrapGate = gate
        val fresh = store()
        val job = async { runCatching { fresh.load() } }
        runCurrent()
        assertEquals(1, keys.unwrapped.size)
        keys.unwrapGate = null
        fresh.reset()
        gate.complete(Unit)
        assertTrue(job.await().exceptionOrNull() is VaultLockedException)
        assertZero(keys.unwrapped.first())
        assertFailsWith<VaultLockedException> { fresh.save(accounts) }
    }

    @Test
    fun twoConcurrentFirstRunLoadsCreateOneKeyAndSavesSurviveAReload() = runTest {
        val s = store()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        keys.createGate = gate
        val first = async { s.load() }
        val second = async { s.load() }
        runCurrent()
        gate.complete(Unit)
        assertEquals(emptyList(), first.await())
        assertEquals(emptyList(), second.await())
        assertEquals(1, keys.createCalls)
        keys.createGate = null
        s.save(accounts)
        assertEquals(accounts, store().load())
    }

    @Test
    fun resetDoesNotWaitForALoadSuspendedInUnwrap() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        keys.unwrapGate = gate
        val fresh = store()
        val loading = async { runCatching { fresh.load() } }
        runCurrent()
        val resetJob = async { fresh.reset() }
        runCurrent()
        assertTrue(resetJob.isCompleted, "reset waited for a load suspended in unwrap")
        fresh.lock()
        gate.complete(Unit)
        assertTrue(loading.await().exceptionOrNull() is VaultLockedException)
    }

    @Test
    fun resetWithNothingIsANoOp() = runTest {
        store().reset()
        assertNull(blobs.bytes)
        assertFalse(keys.hasKey())
    }

    @Test
    fun resetWithFailingDestroyStillClearsTheBlobAndRethrows() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        keys.destroyThrows = true
        val e = assertFailsWith<IllegalStateException> { s.reset() }
        assertEquals("destroy failed", e.message)
        assertNull(blobs.bytes)
    }

    @Test
    fun resetWithFailingClearStillDestroysTheKeyAndRethrows() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        blobs.clearThrows = true
        val e = assertFailsWith<IllegalStateException> { s.reset() }
        assertEquals("clear failed", e.message)
        assertFalse(keys.hasKey())
    }

    @Test
    fun resetWithBothFailingSuppressesTheSecond() = runTest {
        val s = store()
        s.load()
        blobs.clearThrows = true
        keys.destroyThrows = true
        val e = assertFailsWith<IllegalStateException> { s.reset() }
        assertEquals(1, e.suppressed.size)
    }

    @Test
    fun resetDuringInProgressWriteLeavesNoBlobAndFirstUseWorks() = runTest {
        val s = store()
        s.load()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        blobs.writeGate = gate
        val saver = async { runCatching { s.save(accounts) } }
        runCurrent()
        val resetJob = async { s.reset() }
        runCurrent()
        assertTrue(resetJob.isActive) // waits for the write already inside the blob store
        gate.complete(Unit)
        runCurrent()
        resetJob.await()
        saver.await()
        assertNull(blobs.bytes)
        assertFalse(keys.hasKey())
        blobs.writeGate = null
        assertEquals(emptyList(), s.load())
    }

    @Test
    fun saveEncryptedButNotYetWritingWhenResetRunsDoesNotWrite() = runTest {
        val s = store()
        s.load()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        blobs.writeGate = gate
        val first = async { runCatching { s.save(accounts) } } // holds the write lock inside write
        runCurrent()
        val second = async { runCatching { s.save(accounts) } } // encrypted, queued on the write lock
        runCurrent()
        val resetJob = async { s.reset() } // bumps the generation, queues behind the second save
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        resetJob.await()
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().exceptionOrNull() is VaultLockedException)
        assertEquals(1, blobs.writes)
        assertNull(blobs.bytes)
        blobs.writeGate = null
        assertEquals(emptyList(), s.load())
    }

    private fun assertZero(a: ByteArray) = assertTrue(a.all { it == 0.toByte() }, "key array not zeroed")

    @Test
    fun lockZeroesTheDataKey() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val s2 = store()
        s2.load()
        val held = keys.unwrapped.last()
        assertFalse(held.all { it == 0.toByte() })
        s2.lock()
        assertZero(held)
    }

    @Test
    fun corruptLoadZeroesTheUnwrappedKey() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        val b = blobs.bytes!!
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        assertFailsWith<VaultCorruptException> { store().load() }
        assertZero(keys.unwrapped.last())
    }

    @Test
    fun reloadZeroesThePreviousKey() = runTest {
        val s = store()
        s.load()
        val first = keys.createdArg!!
        assertFalse(first.all { it == 0.toByte() })
        s.load()
        assertZero(first)
        val second = keys.unwrapped.last()
        assertFalse(second.all { it == 0.toByte() })
        s.load()
        assertZero(second)
    }

    @Test
    fun lockDuringSuspendedLoadLeavesStoreLockedAndKeyZeroed() = runTest {
        val s = store()
        s.load()
        s.save(accounts)
        s.lock()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        keys.unwrapGate = gate
        val fresh = store()
        val job = async { runCatching { fresh.load() } }
        runCurrent()
        assertEquals(1, keys.unwrapped.size)
        fresh.lock()
        gate.complete(Unit)
        val result = job.await()
        assertTrue(result.exceptionOrNull() is VaultLockedException)
        assertZero(keys.unwrapped.last())
        assertFailsWith<VaultLockedException> { fresh.save(accounts) }
    }

    @Test
    fun lockDuringSuspendedLoadWithoutBlobZeroesTheKey() = runTest {
        store().load()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        keys.unwrapGate = gate
        val fresh = store()
        val job = async { runCatching { fresh.load() } }
        runCurrent()
        fresh.lock()
        gate.complete(Unit)
        assertTrue(job.await().exceptionOrNull() is VaultLockedException)
        assertZero(keys.unwrapped.last())
    }

    @Test
    fun failedCreateDestroysTheKeyAndZeroesIt() = runTest {
        keys.createThrows = true
        assertFailsWith<IllegalStateException> { store().load() }
        assertEquals(1, keys.destroyCalls)
        assertZero(keys.createdArg!!)
    }
}
