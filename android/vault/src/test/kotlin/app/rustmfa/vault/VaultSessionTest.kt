package app.rustmfa.vault

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeSeedStore(var stored: List<OtpAccount> = emptyList()) : SeedStore {
    val events = mutableListOf<String>()
    var saves = 0
    var lockCalls = 0
    var resets = 0
    var failSave = false
    var saveGate: CompletableDeferred<Unit>? = null
    var lastSaved: List<OtpAccount> = emptyList()

    override suspend fun load(): List<OtpAccount> = stored.map { it.copy(secret = it.secret.copyOf()) }

    override suspend fun save(accounts: List<OtpAccount>) {
        saveGate?.await()
        if (failSave) throw IllegalStateException("save failed")
        saves++
        events += "save"
        lastSaved = accounts.map { it.copy(secret = it.secret.copyOf()) }
        stored = lastSaved
    }

    override suspend fun reset() {
        resets++
        stored = emptyList()
    }

    override fun lock() {
        lockCalls++
    }
}

class VaultSessionTest {
    private val rfc4226 = "12345678901234567890".toByteArray()
    private val totp = OtpAccount("t1", OtpType.TOTP, "Ex", "alice", rfc4226, digits = 8)
    private val hotp = OtpAccount("h1", OtpType.HOTP, "Ex", "bob", rfc4226)
    private val store = FakeSeedStore(listOf(totp, hotp))
    private var now = 0L
    private var ids = 0
    private fun session() = VaultSession(store, { now }, newId = { "id${ids++}" })

    @Test
    fun lockedBeforeOpen() = runTest {
        val s = session()
        assertFalse(s.isUnlocked)
        assertFailsWith<VaultLockedException> { s.accounts() }
        assertFailsWith<VaultLockedException> { s.add(totp) }
        assertFailsWith<VaultLockedException> { s.update(totp) }
        assertFailsWith<VaultLockedException> { s.remove("t1") }
        assertFailsWith<VaultLockedException> { s.import(listOf(totp)) }
        assertFailsWith<VaultLockedException> { s.code("t1", 59) }
        assertFailsWith<VaultLockedException> { s.nextHotp("h1") }
    }

    @Test
    fun openCachesStoreAccounts() = runTest {
        val s = session()
        s.open()
        assertTrue(s.isUnlocked)
        assertEquals(listOf(totp, hotp), s.accounts())
    }

    @Test
    fun addSavesFullListOnceWithNewId() = runTest {
        val s = session()
        s.open()
        val added = s.add(OtpAccount("ignored", OtpType.TOTP, "N", "n", byteArrayOf(9)))
        assertEquals("id0", added.id)
        assertEquals(1, store.saves)
        assertEquals(listOf("t1", "h1", "id0"), store.lastSaved.map { it.id })
        assertEquals(3, s.accounts().size)
    }

    @Test
    fun updateReplacesById() = runTest {
        val s = session()
        s.open()
        s.update(totp.copy(label = "changed"))
        assertEquals("changed", s.accounts().first { it.id == "t1" }.label)
        assertEquals("changed", store.lastSaved.first { it.id == "t1" }.label)
        assertFailsWith<NoSuchElementException> { s.update(totp.copy(id = "nope")) }
        assertEquals(1, store.saves)
    }

    @Test
    fun removeSavesWithout() = runTest {
        val s = session()
        s.open()
        s.remove("t1")
        assertEquals(listOf("h1"), store.lastSaved.map { it.id })
        assertEquals(listOf("h1"), s.accounts().map { it.id })
    }

    @Test
    fun codeForTotpAndRejectsHotp() = runTest {
        val s = session()
        s.open()
        assertEquals("94287082", s.code("t1", 59))
        assertFailsWith<IllegalArgumentException> { s.code("h1", 59) }
    }

    @Test
    fun nextHotpSavesCounterBeforeReturning() = runTest {
        val s = session()
        s.open()
        val code = s.nextHotp("h1")
        store.events += "returned"
        assertEquals("755224", code)
        assertEquals(1L, store.lastSaved.first { it.id == "h1" }.counter)
        assertEquals(listOf("save", "returned"), store.events)
        assertEquals(1L, s.accounts().first { it.id == "h1" }.counter)
    }

    @Test
    fun failedSaveKeepsOldCounterAndRethrows() = runTest {
        val s = session()
        s.open()
        store.failSave = true
        assertFailsWith<IllegalStateException> { s.nextHotp("h1") }
        assertEquals(0L, s.accounts().first { it.id == "h1" }.counter)
        assertFailsWith<IllegalStateException> { s.add(totp) }
        assertFailsWith<IllegalStateException> { s.remove("t1") }
        assertEquals(listOf("t1", "h1"), s.accounts().map { it.id })
    }

    @Test
    fun autoLockBoundary() = runTest {
        val s = session()
        s.open()
        now = 0
        s.appLeft()
        now = 59_999
        s.appReturned()
        assertTrue(s.isUnlocked)
        assertEquals(0, store.lockCalls)
        s.appLeft()
        now = 59_999 + 60_000
        s.appReturned()
        assertFalse(s.isUnlocked)
        assertEquals(1, store.lockCalls)
    }

    @Test
    fun autoLockExactlyAtLimit() = runTest {
        val s = session()
        s.open()
        s.appLeft()
        now = 60_000
        s.appReturned()
        assertFalse(s.isUnlocked)
        assertEquals(1, store.lockCalls)
    }

    @Test
    fun returnedWithoutLeftDoesNothing() = runTest {
        val s = session()
        s.open()
        now = 1_000_000
        s.appReturned()
        assertTrue(s.isUnlocked)
        assertEquals(0, store.lockCalls)
    }

    @Test
    fun snapshotIsACopyAndLockZeroesInternalSecrets() = runTest {
        val s = session()
        s.open()
        val snap = s.accounts()
        snap[0].secret.fill(7)
        assertContentEquals(rfc4226, s.accounts()[0].secret)
        val snap2 = s.accounts()
        s.lock()
        assertContentEquals(rfc4226, snap2[0].secret)
        s.open()
        assertContentEquals(rfc4226, s.accounts()[0].secret)
    }

    @Test
    fun lockZeroesInternalArrays() = runTest {
        val given = totp.copy(secret = rfc4226.copyOf())
        val s = session()
        s.open()
        s.add(given)
        s.lock()
        assertContentEquals(rfc4226, given.secret) // caller's array was copied in
        // the store's own copy is untouched too
        assertContentEquals(rfc4226, store.stored.first { it.id == "t1" }.secret)
    }

    @Test
    fun addCopiesSecretIn() = runTest {
        val s = session()
        s.open()
        val mine = byteArrayOf(1, 2, 3)
        s.add(OtpAccount("x", OtpType.TOTP, "", "x", mine))
        mine.fill(0)
        assertContentEquals(byteArrayOf(1, 2, 3), s.accounts().last().secret)
    }

    @Test
    fun lockTwiceAndReopen() = runTest {
        val s = session()
        s.open()
        s.lock()
        s.lock()
        assertFalse(s.isUnlocked)
        assertFailsWith<VaultLockedException> { s.accounts() }
        s.open()
        assertTrue(s.isUnlocked)
        assertEquals(2, s.accounts().size)
    }

    @Test
    fun importSkipsDuplicates() = runTest {
        val s = session()
        s.open()
        val dup = totp.copy(id = "other")
        val n1 = OtpAccount("a", OtpType.TOTP, "A", "a", byteArrayOf(1))
        val n2 = OtpAccount("b", OtpType.TOTP, "B", "b", byteArrayOf(2))
        val batch = listOf(dup, n1, n2)
        assertEquals(ImportResult(2, 1), s.import(batch))
        assertEquals(1, store.saves)
        assertEquals(4, s.accounts().size)
        assertEquals(ImportResult(0, 3), s.import(batch))
        assertEquals(1, store.saves)
    }


    @Test
    fun resetLocksThenOpenIsEmpty() = runTest {
        val s = session()
        s.open()
        s.reset()
        assertFalse(s.isUnlocked)
        assertEquals(1, store.resets)
        s.open()
        assertEquals(emptyList(), s.accounts())
    }

    @Test
    fun resetDuringGatedSaveDoesNotWaitAndFailsTheSaver() = runTest {
        val s = session()
        s.open()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        val job = async { runCatching { s.nextHotp("h1") } }
        val queued = async { runCatching { s.add(totp) } }
        runCurrent()
        s.reset()
        assertEquals(1, store.resets)
        assertFalse(s.isUnlocked)
        gate.complete(Unit)
        runCurrent()
        assertTrue(job.await().exceptionOrNull() is VaultLockedException)
        assertTrue(queued.await().exceptionOrNull() is VaultLockedException)
        assertFalse(s.isUnlocked)
    }

    @Test
    fun resetDuringRealStoreWriteLeavesNothingBehind() = runTest {
        val keys = FakeKeyWrapper()
        val blobs = MemoryBlobStore()
        val s = VaultSession(EncryptedSeedStore(keys, blobs), { now }, newId = { "id${ids++}" })
        s.open()
        val gate = CompletableDeferred<Unit>()
        blobs.writeGate = gate
        val job = async { runCatching { s.add(totp) } }
        runCurrent()
        val resetJob = async { s.reset() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        resetJob.await()
        assertTrue(job.await().exceptionOrNull() is VaultLockedException)
        assertEquals(null, blobs.bytes)
        assertFalse(keys.hasKey())
        blobs.writeGate = null
        s.open()
        assertEquals(emptyList(), s.accounts())
    }

    @Test
    fun restoreFlowAfterKeyLost() = runTest {
        var lost = true
        val inner = FakeSeedStore()
        val lossy = object : SeedStore by inner {
            override suspend fun load(): List<OtpAccount> {
                if (lost) throw VaultKeyLostException()
                return inner.load()
            }

            override suspend fun reset() {
                lost = false
                inner.reset()
            }
        }
        val s = VaultSession(lossy, { now }, newId = { "id${ids++}" })
        assertFailsWith<VaultKeyLostException> { s.open() }
        s.reset()
        s.open()
        assertEquals(ImportResult(2, 0), s.import(listOf(totp, hotp)))
        assertEquals(listOf(totp.copy(id = "id0"), hotp.copy(id = "id1")), s.accounts())
    }

    @Test
    fun lockDuringInFlightSaveCannotResurrect() = runTest {
        val s = session()
        s.open()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        val job = async { runCatching { s.nextHotp("h1") } }
        runCurrent()
        s.lock()
        gate.complete(Unit)
        runCurrent()
        assertTrue(job.await().exceptionOrNull() is VaultLockedException)
        assertFalse(s.isUnlocked)
        assertFailsWith<VaultLockedException> { s.accounts() }
    }

    @Test
    fun lockDuringInFlightSaveFailureStillLocked() = runTest {
        val s = session()
        s.open()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        store.failSave = true
        val job = async { runCatching { s.add(totp) } }
        runCurrent()
        s.lock()
        gate.complete(Unit)
        runCurrent()
        assertTrue(job.await().exceptionOrNull() is IllegalStateException)
        assertFalse(s.isUnlocked)
    }

    @Test
    fun lockDuringOpenCannotResurrect() = runTest {
        val s = session()
        val slow = object : SeedStore {
            val gate = CompletableDeferred<Unit>()
            override suspend fun load(): List<OtpAccount> { gate.await(); return listOf(totp.copy(secret = rfc4226.copyOf())) }
            override suspend fun save(accounts: List<OtpAccount>) {}
            override suspend fun reset() {}
        }
        val s2 = VaultSession(slow, { now })
        val job = async { runCatching { s2.open() } }
        runCurrent()
        s2.lock()
        slow.gate.complete(Unit)
        runCurrent()
        assertTrue(job.await().exceptionOrNull() is VaultLockedException)
        assertFalse(s2.isUnlocked)
        assertFalse(s.isUnlocked)
    }

    @Test
    fun lockZeroesTheSessionsOwnArrays() = runTest {
        val s = session()
        s.open()
        val field = VaultSession::class.java.getDeclaredField("current").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val internal = (field.get(s) as List<OtpAccount>).map { it.secret }
        assertTrue(internal.all { arr -> arr.any { it != 0.toByte() } })
        s.lock()
        assertTrue(internal.all { arr -> arr.all { it == 0.toByte() } })
    }

    @Test
    fun inFlightOpDoesNotClobberASessionReopenedAfterLock() = runTest {
        val s = session()
        s.open()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        val job = async { runCatching { s.remove("t1") } }
        runCurrent()
        s.lock()
        store.saveGate = null
        s.open()
        gate.complete(Unit)
        runCurrent()
        assertTrue(job.await().exceptionOrNull() is VaultLockedException)
        assertEquals(listOf("t1", "h1"), s.accounts().map { it.id })
    }

    @Test
    fun overlappingNextHotpReturnDifferentCodes() = runTest {
        val s = session()
        s.open()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        val a = async { s.nextHotp("h1") }
        val b = async { s.nextHotp("h1") }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(setOf("755224", "287082"), setOf(a.await(), b.await()))
        assertEquals(2L, s.accounts().first { it.id == "h1" }.counter)
        assertEquals(2, store.saves)
    }

    @Test
    fun overlappingAddAndNextHotpKeepBoth() = runTest {
        val s = session()
        s.open()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        val a = async { s.add(OtpAccount("x", OtpType.TOTP, "N", "n", byteArrayOf(9))) }
        val b = async { s.nextHotp("h1") }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        a.await()
        b.await()
        val list = s.accounts()
        assertEquals(3, list.size)
        assertEquals(1L, list.first { it.id == "h1" }.counter)
    }

    @Test
    fun queuedMutationFailsAfterLockWithoutSaving() = runTest {
        val s = session()
        s.open()
        val gate = CompletableDeferred<Unit>()
        store.saveGate = gate
        val first = async { runCatching { s.remove("t1") } }
        val queued = async { runCatching { s.nextHotp("h1") } }
        runCurrent()
        s.lock()
        s.open()
        gate.complete(Unit)
        runCurrent()
        assertTrue(first.await().exceptionOrNull() is VaultLockedException)
        assertTrue(queued.await().exceptionOrNull() is VaultLockedException)
        assertEquals(1, store.saves) // only the save already in flight; the queued one never reached the store
        assertEquals(listOf("t1", "h1"), s.accounts().map { it.id })
    }

    @Test
    fun lockDoesNotWaitForAGatedSave() = runTest {
        val s = session()
        s.open()
        store.saveGate = CompletableDeferred()
        val job = async { runCatching { s.nextHotp("h1") } }
        runCurrent()
        s.lock()
        assertFalse(s.isUnlocked)
        assertEquals(1, store.lockCalls)
        store.saveGate!!.complete(Unit)
        runCurrent()
        job.await()
    }
}
