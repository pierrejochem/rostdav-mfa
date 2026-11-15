package app.rustmfa.android.vault

import app.rustmfa.vault.BackupDecryptException
import app.rustmfa.vault.BackupFormatException
import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import app.rustmfa.vault.VaultAuthRequiredException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
}
