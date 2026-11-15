package app.rustmfa.vault

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
        blobs.bytes = ByteArray(6 + 12 + 15)
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
}
