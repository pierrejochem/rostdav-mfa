package app.rustmfa.android.vault

import android.net.Uri
import app.rustmfa.android.ui.design.groupCode
import app.rustmfa.vault.Otp
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultCorruptException
import app.rustmfa.vault.VaultKeyLostException
import app.rustmfa.vault.VaultLockedException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    private val main = UnconfinedTestDispatcher()

    /** Wall-clock offset added to virtual time, in milliseconds. */
    private var offset = 0L
    private val clock = { offset + main.scheduler.currentTime }
    private val gateway = FakeVaultGateway(clock)
    private val clipboard = FakeClipboard()
    private val prefs = FakePrefs()

    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)
    private val totp = OtpAccount("", OtpType.TOTP, "Example", "alice", rfcSecret)
    private val hotp = OtpAccount("", OtpType.HOTP, "Counter", "bob", rfcSecret)

    @BeforeTest fun setUp() = Dispatchers.setMain(main)

    /** Stands in for the app-wide scope of AppContainer: it outlives the view model. */
    private val appScope = CoroutineScope(SupervisorJob() + main)

    @AfterTest fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    /** How many values the ticker produced: it only runs while it is collected. */
    private var ticks = 0

    private class FakeClipboard : ClipboardWriter {
        val copied = mutableListOf<String>()
        val cleared = mutableListOf<String>()

        override fun copySensitive(text: String) {
            copied += text
        }

        override fun clearIfStillOurs(text: String) {
            cleared += text
        }
    }

    private class FakePrefs : VaultPrefs {
        override var lastBackupAt: Long? = null
        override var nudgeHiddenUntil: Long = 0
    }

    private object NoFiles : BackupFiles {
        override suspend fun read(uri: Uri, maxBytes: Int): ByteArray = error("unused")

        override suspend fun write(uri: Uri, bytes: ByteArray) = error("unused")
    }

    /** Ends the view model's ticker when a test is over, so runTest does not chase it through virtual time. */
    private var finished = false

    private fun viewModel() =
        VaultViewModel(
            gateway, clock, clipboard, prefs, NoFiles, appScope,
            ticker = secondTicker(clock).takeWhile { !finished }.onEach { ticks++ },
        )

    private fun vaultTest(body: suspend TestScope.() -> Unit) = runTest(main) {
        try {
            body()
        } finally {
            finished = true
        }
    }

    private fun TestScope.events(vm: VaultViewModel): List<VaultEvent> {
        val out = mutableListOf<VaultEvent>()
        backgroundScope.launch(main) { vm.events.toList(out) }
        return out
    }

    /** A stored, unlocked vault holding [accounts]. */
    private suspend fun openVault(vararg accounts: OtpAccount) {
        gateway.stored = emptyList()
        gateway.session.open()
        if (accounts.isNotEmpty()) gateway.session.import(accounts.toList())
    }

    private fun VaultViewModel.rows() = assertIs<VaultUiState.Unlocked>(state.value).rows

    @Test
    fun `no vault, then unlock gives Empty`() = vaultTest {
        val vm = viewModel()
        assertEquals(VaultUiState.NoVault, vm.state.value)
        vm.unlock()
        assertEquals(VaultUiState.Empty, vm.state.value)
    }

    @Test
    fun `a stored vault starts Locked and unlocks to its rows`() = vaultTest {
        gateway.stored = emptyList()
        gateway.session.open()
        gateway.session.import(listOf(totp))
        gateway.session.lock()
        val vm = viewModel()
        assertEquals(VaultUiState.Locked, vm.state.value)
        vm.unlock()
        assertEquals(1, vm.rows().size)
    }

    @Test
    fun `a cancelled prompt stays Locked and emits nothing`() = vaultTest {
        gateway.stored = emptyList()
        val vm = viewModel()
        val events = events(vm)
        gateway.unlockFailures += VaultAuthRequiredException()
        vm.unlock()
        assertEquals(VaultUiState.Locked, vm.state.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a lost key or a corrupt vault needs a reset`() = vaultTest {
        gateway.stored = emptyList()
        val vm = viewModel()
        gateway.unlockFailures += VaultKeyLostException()
        vm.unlock()
        assertEquals(VaultUiState.NeedsReset, vm.state.value)
        val vm2 = viewModel()
        gateway.unlockFailures += VaultCorruptException()
        vm2.unlock()
        assertEquals(VaultUiState.NeedsReset, vm2.state.value)
    }

    @Test
    fun `returning after 59 999 ms stays Unlocked`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        vm.appLeft()
        advanceTimeBy(59_999)
        runCurrent()
        vm.appReturned()
        assertIs<VaultUiState.Unlocked>(vm.state.value)
    }

    @Test
    fun `returning after 60 000 ms shows Locked`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        vm.appLeft()
        advanceTimeBy(60_000)
        runCurrent()
        vm.appReturned()
        assertEquals(VaultUiState.Locked, vm.state.value)
    }

    @Test
    fun `the ticker updates the rows each second and the code changes at the boundary`() = vaultTest {
        offset = 28_000
        openVault(totp)
        val vm = viewModel()
        val first = vm.rows().single()
        assertEquals(2, first.secondsLeft)
        assertEquals(groupCode(Otp.totp(totp, 28)), first.code)
        advanceTimeBy(1_000)
        runCurrent()
        val second = vm.rows().single()
        assertEquals(1, second.secondsLeft)
        assertEquals(first.code, second.code)
        advanceTimeBy(1_000)
        runCurrent()
        val third = vm.rows().single()
        assertEquals(30, third.secondsLeft)
        assertEquals(groupCode(Otp.totp(totp, 30)), third.code)
        assertNotEquals(first.code, third.code)
    }

    @Test
    fun `the next tick shows Locked when the gateway locked itself`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        gateway.session.lock()
        assertIs<VaultUiState.Unlocked>(vm.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(VaultUiState.Locked, vm.state.value)
    }

    @Test
    fun `copy puts the ungrouped code on the clipboard and clears it after 30 s`() = vaultTest {
        offset = 59_000
        openVault(totp)
        val vm = viewModel()
        val events = events(vm)
        val id = vm.rows().single().id
        vm.copy(id)
        assertEquals(listOf(Otp.totp(totp, 59)), clipboard.copied)
        assertEquals(listOf<VaultEvent>(VaultEvent.Copied), events)
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(clipboard.cleared.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(clipboard.copied, clipboard.cleared)
    }

    @Test
    fun `adding a new account emits Added, an existing one Duplicate`() = vaultTest {
        openVault()
        val vm = viewModel()
        val events = events(vm)
        vm.addAccount(totp)
        vm.addAccount(totp.copy(secret = rfcSecret.copyOf()))
        assertEquals(listOf(VaultEvent.Added, VaultEvent.Duplicate), events)
        assertEquals(1, vm.rows().size)
    }

    @Test
    fun `the first add on no vault unlocks and creates it`() = vaultTest {
        val vm = viewModel()
        val events = events(vm)
        vm.addAccount(totp)
        assertEquals(listOf("import", "unlock", "import"), gateway.calls)
        assertEquals(listOf<VaultEvent>(VaultEvent.Added), events)
        assertEquals(1, vm.rows().size)
    }

    @Test
    fun `add after a lock unlocks and retries once`() = vaultTest {
        openVault()
        val vm = viewModel()
        val events = events(vm)
        gateway.mutationFailures += VaultLockedException()
        vm.addAccount(totp)
        assertEquals(listOf("import", "unlock", "import"), gateway.calls)
        assertEquals(listOf<VaultEvent>(VaultEvent.Added), events)
    }

    @Test
    fun `add retries exactly once`() = vaultTest {
        openVault()
        val vm = viewModel()
        val events = events(vm)
        gateway.mutationFailures += VaultLockedException()
        gateway.mutationFailures += VaultAuthRequiredException()
        vm.addAccount(totp)
        assertEquals(listOf("import", "unlock", "import"), gateway.calls)
        assertEquals(listOf<VaultEvent>(VaultEvent.UnlockToSave), events)
        assertEquals(emptyList(), gateway.stored)
    }

    @Test
    fun `add with a cancelled prompt emits UnlockToSave and imports nothing`() = vaultTest {
        openVault()
        gateway.session.lock()
        val vm = viewModel()
        val events = events(vm)
        gateway.unlockFailures += VaultAuthRequiredException()
        vm.addAccount(totp)
        assertEquals(listOf<VaultEvent>(VaultEvent.UnlockToSave), events)
        assertEquals(emptyList(), gateway.stored)
        assertEquals(VaultUiState.Locked, vm.state.value)
    }

    @Test
    fun `edit follows the retry rule`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        val events = events(vm)
        val id = vm.rows().single().id
        gateway.mutationFailures += VaultLockedException()
        vm.updateAccount(id, "New", "carol")
        assertEquals(listOf("update", "unlock", "update"), gateway.calls)
        assertEquals("New" to "carol", vm.rows().single().let { it.issuer to it.label })
        assertTrue(events.isEmpty())

        gateway.calls.clear()
        gateway.mutationFailures += VaultLockedException()
        gateway.mutationFailures += VaultLockedException()
        vm.updateAccount(id, "Newer", "dave")
        assertEquals(listOf("update", "unlock", "update"), gateway.calls)
        assertEquals(listOf<VaultEvent>(VaultEvent.UnlockToSave), events)
        assertEquals("New", vm.rows().single().issuer)
    }

    @Test
    fun `delete follows the retry rule`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        val events = events(vm)
        val id = vm.rows().single().id
        gateway.mutationFailures += VaultLockedException()
        gateway.mutationFailures += VaultLockedException()
        vm.deleteAccount(id)
        assertEquals(listOf("remove", "unlock", "remove"), gateway.calls)
        assertEquals(listOf<VaultEvent>(VaultEvent.UnlockToSave), events)
        assertEquals(1, vm.rows().size)

        gateway.calls.clear()
        gateway.mutationFailures += VaultLockedException()
        vm.deleteAccount(id)
        assertEquals(listOf("remove", "unlock", "remove"), gateway.calls)
        assertEquals(VaultUiState.Empty, vm.state.value)
    }

    @Test
    fun `edit changes issuer and label only, secret and settings stay`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        val before = gateway.session.accounts().single()
        vm.updateAccount(before.id, "New", "carol")
        val after = gateway.session.accounts().single()
        assertEquals(before.copy(issuer = "New", label = "carol", secret = before.secret), after.copy(secret = before.secret))
        assertTrue(before.secret.contentEquals(after.secret))
        assertEquals(listOf("update"), gateway.calls)
    }

    @Test
    fun `delete removes the account once`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        vm.deleteAccount(vm.rows().single().id)
        assertEquals(listOf("remove"), gateway.calls)
        assertEquals(VaultUiState.Empty, vm.state.value)
    }

    @Test
    fun `copy on a HOTP row with no generated code copies nothing and fails quietly`() = vaultTest {
        openVault(hotp)
        val vm = viewModel()
        val events = events(vm)
        vm.copy(vm.rows().single().id)
        assertTrue(clipboard.copied.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a generated HOTP code shows until the lock`() = vaultTest {
        openVault(hotp)
        val vm = viewModel()
        val id = vm.rows().single().id
        assertNull(vm.rows().single().code)
        vm.generateHotp(id)
        assertEquals("755 224", vm.rows().single().code)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("755 224", vm.rows().single().code)
        vm.copy(id)
        assertEquals(listOf("755224"), clipboard.copied)
        vm.lock()
        assertEquals(VaultUiState.Locked, vm.state.value)
        vm.unlock()
        assertNull(vm.rows().single().code)
    }

    @Test
    fun `the nudge shows with accounts and no backup, and dismissing hides it for a week`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        assertTrue(vm.showNudge.value)
        vm.dismissNudge()
        assertFalse(vm.showNudge.value)
        assertEquals(clock() + NUDGE_HIDE_MILLIS, prefs.nudgeHiddenUntil)
    }

    @Test
    fun `no nudge while locked`() = vaultTest {
        gateway.stored = emptyList()
        val vm = viewModel()
        assertFalse(vm.showNudge.value)
    }

    @Test
    fun `the ticker sleeps while the app is in the background and resumes on return`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(ticks >= 2)
        vm.appLeft()
        val atLeave = ticks
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(atLeave, ticks, "the ticker woke while the app was in the background")
        vm.appReturned()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(ticks > atLeave, "the ticker did not resume on return")
        assertIs<VaultUiState.Unlocked>(vm.state.value)
    }

    @Test
    fun `copying a generated HOTP code after a lock copies nothing and shows Locked`() = vaultTest {
        openVault(hotp)
        val vm = viewModel()
        val id = vm.rows().single().id
        vm.generateHotp(id)
        gateway.session.lock()
        vm.copy(id)
        assertTrue(clipboard.copied.isEmpty(), "a code was copied from a locked vault")
        assertEquals(VaultUiState.Locked, vm.state.value)
    }

    @Test
    fun `the clipboard is still cleared after the view model is gone`() = vaultTest {
        openVault(totp)
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { viewModel() } })[VaultViewModel::class]
        vm.copy(vm.rows().single().id)
        assertEquals(1, clipboard.copied.size)
        store.clear()
        advanceTimeBy(CLIPBOARD_CLEAR_MILLIS)
        runCurrent()
        assertEquals(clipboard.copied, clipboard.cleared)
    }

    @Test
    fun `ensureUnlocked on an open vault is true without a prompt`() = vaultTest {
        openVault(totp)
        val vm = viewModel()
        assertTrue(vm.ensureUnlocked())
        assertEquals(emptyList(), gateway.calls)
    }

    @Test
    fun `ensureUnlocked on no vault creates it`() = vaultTest {
        val vm = viewModel()
        assertTrue(vm.ensureUnlocked())
        assertEquals(listOf("unlock"), gateway.calls)
        assertEquals(VaultUiState.Empty, vm.state.value)
    }

    @Test
    fun `ensureUnlocked on a locked vault unlocks it`() = vaultTest {
        openVault(totp)
        gateway.session.lock()
        val vm = viewModel()
        assertTrue(vm.ensureUnlocked())
        assertEquals(1, vm.rows().size)
    }

    @Test
    fun `ensureUnlocked with a cancelled prompt is false, stays put and emits nothing`() = vaultTest {
        val vm = viewModel()
        val events = events(vm)
        gateway.unlockFailures += VaultAuthRequiredException()
        assertFalse(vm.ensureUnlocked())
        assertEquals(VaultUiState.NoVault, vm.state.value)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `ensureUnlocked with a lost key is false and needs a reset`() = vaultTest {
        gateway.stored = emptyList()
        val vm = viewModel()
        gateway.unlockFailures += VaultKeyLostException()
        assertFalse(vm.ensureUnlocked())
        assertEquals(VaultUiState.NeedsReset, vm.state.value)
    }

    @Test
    fun `ensureUnlocked with an unexpected failure is false with the generic message`() = vaultTest {
        val vm = viewModel()
        val events = events(vm)
        gateway.unlockFailures += IllegalStateException("boom")
        assertFalse(vm.ensureUnlocked())
        assertEquals(listOf<VaultEvent>(VaultEvent.Failed(app.rustmfa.android.R.string.vault_error_generic)), events)
    }
}
