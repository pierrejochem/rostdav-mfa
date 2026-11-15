package app.rustmfa.android.vault

import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.SeedStore
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

            override suspend fun reset() {
                events += "store.reset"
            }

            override fun lock() {
                events += "store.lock"
            }
        }
    private val session = VaultSession(store, { now })

    private fun repository(authenticate: suspend () -> Unit = { events += "auth" }) =
        VaultRepository(session, authenticate)

    @Test
    fun `unlock authenticates before the store loads`() = runTest {
        val repo = repository()
        repo.unlock()
        assertEquals(listOf("auth", "load"), events)
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `a failed authentication leaves the session locked and the store untouched`() = runTest {
        val repo = repository { throw VaultAuthRequiredException() }
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
}
