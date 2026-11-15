package app.rustmfa.android.vault

import app.rustmfa.vault.BackupDecryptException
import app.rustmfa.vault.BackupFormatException
import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import app.rustmfa.vault.VaultAuthRequiredException
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.IOException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class RestoreFlowTest {
    private val gateway = FakeVaultGateway { 0L }
    private val backup = listOf(
        OtpAccount("", OtpType.TOTP, "Example", "alice", byteArrayOf(1, 2, 3)),
        OtpAccount("", OtpType.HOTP, "Other", "bob", byteArrayOf(4, 5, 6)),
    )

    private fun flow(decrypt: (ByteArray, CharArray) -> List<OtpAccount>) =
        RestoreFlow(gateway, decrypt, UnconfinedTestDispatcher())

    private val decryptOk: (ByteArray, CharArray) -> List<OtpAccount> = { _, _ ->
        gateway.calls += "decrypt"
        backup.map { it.copy(secret = it.secret.copyOf()) }
    }

    @Test
    fun `restore decrypts first and touches the gateway only after the confirm`() = runTest {
        gateway.stored = emptyList()
        val restore = flow(decryptOk)
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        assertEquals(listOf("decrypt"), gateway.calls)
        assertEquals(RestoreState.Decrypted, restore.state.value)
        restore.confirmReplace()
        assertEquals(listOf("decrypt", "reset", "unlock", "import"), gateway.calls)
        assertEquals(RestoreState.Done(ImportResult(2, 0)), restore.state.value)
        assertEquals(2, gateway.stored?.size)
        assertNull(restore.pendingList)
    }

    @Test
    fun `a wrong passphrase fails and never resets`() = runTest {
        val restore = flow { _, _ ->
            gateway.calls += "decrypt"
            throw BackupDecryptException()
        }
        restore.start(byteArrayOf(1), "wrong-pass".toCharArray())
        assertIs<BackupDecryptException>(assertIs<RestoreState.Failed>(restore.state.value).error)
        restore.confirmReplace()
        assertFalse("reset" in gateway.calls)
        assertEquals(listOf("decrypt"), gateway.calls)
    }

    @Test
    fun `a cancelled prompt after the reset keeps the list and finish imports it`() = runTest {
        val restore = flow(decryptOk)
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        gateway.unlockFailures += VaultAuthRequiredException()
        restore.confirmReplace()
        assertEquals(RestoreState.FinishPending, restore.state.value)
        assertNotNull(restore.pendingList)
        assertEquals(listOf("decrypt", "reset", "unlock"), gateway.calls)
        restore.finish()
        assertEquals(RestoreState.Done(ImportResult(2, 0)), restore.state.value)
        assertEquals(listOf("decrypt", "reset", "unlock", "unlock", "import"), gateway.calls)
        assertEquals(1, gateway.calls.count { it == "reset" })
    }

    @Test
    fun `a file with bad contents fails without a reset`() = runTest {
        val restore = RestoreFlow(gateway, cpu = UnconfinedTestDispatcher())
        restore.start("{\"app\":\"something-else\"}".toByteArray(), "passphrase".toCharArray())
        assertIs<BackupFormatException>(assertIs<RestoreState.Failed>(restore.state.value).error)
        restore.confirmReplace()
        assertTrue(gateway.calls.isEmpty())
    }

    /** The lists [decryptOk] handed out, to check that their secrets were zeroed. */
    private val handedOut = mutableListOf<List<OtpAccount>>()

    private val decryptTracked: (ByteArray, CharArray) -> List<OtpAccount> = { b, p -> decryptOk(b, p).also { handedOut += it } }

    private fun zeroed(list: List<OtpAccount>) = list.all { a -> a.secret.all { it == 0.toByte() } }

    /** A flow left in FinishPending: the old vault is reset and the prompt was cancelled. */
    private suspend fun finishPending(): RestoreFlow {
        val restore = flow(decryptTracked)
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        gateway.unlockFailures += VaultAuthRequiredException()
        restore.confirmReplace()
        assertEquals(RestoreState.FinishPending, restore.state.value)
        return restore
    }

    @Test
    fun `finish does nothing before the reset`() = runTest {
        val restore = flow(decryptOk)
        restore.finish()
        assertTrue(gateway.calls.isEmpty())
        assertEquals(RestoreState.Idle, restore.state.value)
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        restore.finish()
        // no import into the old vault without its reset
        assertEquals(listOf("decrypt"), gateway.calls)
        assertEquals(RestoreState.Decrypted, restore.state.value)
        assertNotNull(restore.pendingList)
    }

    @Test
    fun `start while FinishPending is refused and keeps the list`() = runTest {
        val restore = finishPending()
        val kept = restore.pendingList
        val again = "other-pass".toCharArray()
        restore.start(byteArrayOf(2), again)
        assertEquals(RestoreState.FinishPending, restore.state.value)
        assertEquals(1, gateway.calls.count { it == "decrypt" })
        assertTrue(kept === restore.pendingList)
        assertFalse(zeroed(handedOut.single()))
        assertTrue(again.all { it == Char(0) }, "the refused passphrase is zeroed")
        restore.finish()
        assertEquals(RestoreState.Done(ImportResult(2, 0)), restore.state.value)
    }

    @Test
    fun `abandon zeroes the kept list and finish has nothing left to import`() = runTest {
        val restore = finishPending()
        restore.abandon()
        assertEquals(RestoreState.Idle, restore.state.value)
        assertNull(restore.pendingList)
        assertTrue(zeroed(handedOut.single()))
        restore.finish()
        assertFalse("import" in gateway.calls)
    }

    @Test
    fun `abandon after the decrypt zeroes the list and never resets`() = runTest {
        val restore = flow(decryptTracked)
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        restore.abandon()
        assertTrue(zeroed(handedOut.single()))
        restore.confirmReplace()
        assertEquals(listOf("decrypt"), gateway.calls)
    }

    @Test
    fun `a successful import zeroes the list`() = runTest {
        val restore = flow(decryptTracked)
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        restore.confirmReplace()
        assertTrue(zeroed(handedOut.single()))
        // the vault holds its own copies
        assertContentEquals(byteArrayOf(1, 2, 3), gateway.stored!!.first { it.issuer == "Example" }.secret)
    }

    @Test
    fun `start zeroes the passphrase and shows Working while it decrypts`() = runTest {
        lateinit var restore: RestoreFlow
        var during: RestoreState? = null
        restore = flow { b, p ->
            during = restore.state.value
            decryptOk(b, p)
        }
        val pass = "passphrase".toCharArray()
        restore.start(byteArrayOf(1), pass)
        assertEquals(RestoreState.Working, during)
        assertTrue(pass.all { it == Char(0) })
    }

    @Test
    fun `a read failure fails without a decrypt`() = runTest {
        val restore = flow(decryptOk)
        restore.start({ throw IOException("gone") }, "passphrase".toCharArray())
        assertIs<IOException>(assertIs<RestoreState.Failed>(restore.state.value).error)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `a failed reset drops the list and imports nothing`() = runTest {
        val restore = RestoreFlow(FailingResetGateway(gateway), decryptTracked, UnconfinedTestDispatcher())
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        restore.confirmReplace()
        val failed = assertIs<RestoreState.Failed>(restore.state.value)
        assertFalse(failed.canFinish)
        assertTrue(zeroed(handedOut.single()))
        assertEquals(listOf("decrypt", "reset"), gateway.calls)
    }

    @Test
    fun `a failed import after the reset keeps the list for finish`() = runTest {
        val restore = flow(decryptTracked)
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        gateway.mutationFailures += IOException("disk full")
        restore.confirmReplace()
        val failed = assertIs<RestoreState.Failed>(restore.state.value)
        assertTrue(failed.canFinish)
        assertFalse(zeroed(handedOut.single()))
        // a new start would throw away the only copy
        restore.start(byteArrayOf(1), "passphrase".toCharArray())
        assertEquals(1, gateway.calls.count { it == "decrypt" })
        restore.finish()
        assertEquals(RestoreState.Done(ImportResult(2, 0)), restore.state.value)
        assertEquals(1, gateway.calls.count { it == "reset" })
    }

    /** Fails the reset, records it in the wrapped fake's calls. */
    private class FailingResetGateway(private val inner: FakeVaultGateway) : VaultGateway by inner {
        override suspend fun reset() {
            inner.calls += "reset"
            throw IOException("cannot delete")
        }
    }
}
