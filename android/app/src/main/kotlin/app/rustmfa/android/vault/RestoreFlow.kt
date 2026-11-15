package app.rustmfa.android.vault

import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface RestoreState {
    data object Idle : RestoreState

    /** The file is read and decrypted, or the reset, the prompt and the import run. */
    data object Working : RestoreState

    /** The backup is decrypted and held in memory; nothing in the vault has changed yet. */
    data object Decrypted : RestoreState

    /** The old vault is gone but the prompt was cancelled; [RestoreFlow.finish] imports the kept list. */
    data object FinishPending : RestoreState

    data class Done(val result: ImportResult) : RestoreState

    /**
     * [canFinish] is true when the old vault is already gone and the decrypted list is still held: only
     * [RestoreFlow.finish] or [RestoreFlow.abandon] go on from here.
     */
    data class Failed(val error: Throwable, val canFinish: Boolean = false) : RestoreState
}

/**
 * Restore over an unreadable vault, in the order that makes a wrong passphrase cost nothing: [start] decrypts
 * (off the main thread, the key derivation takes seconds) and touches no vault state; [confirmReplace] then
 * resets, unlocks (the prompt creates the new key) and imports. A cancelled prompt after the reset keeps the
 * decrypted list for [finish].
 *
 * Once the old vault is reset, the decrypted list is the only copy of the accounts in memory: it is kept until
 * [finish] succeeds or the user explicitly [abandon]s it, and a new [start] is refused meanwhile. The secrets are
 * zeroed on success, on [abandon] and when a reset fails. Every passphrase given to [start] is zeroed.
 */
class RestoreFlow(
    private val gateway: VaultGateway,
    private val decrypt: (ByteArray, CharArray) -> List<OtpAccount> = VaultBackup::import,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    /** The decrypted accounts not yet imported, or null. */
    var pendingList: List<OtpAccount>? = null
        private set

    /** True while the old vault is gone and the list waits for [finish]. */
    private val afterReset: Boolean
        get() = when (val s = _state.value) {
            RestoreState.FinishPending -> true
            is RestoreState.Failed -> s.canFinish
            else -> false
        }

    suspend fun start(bytes: ByteArray, passphrase: CharArray) = start({ bytes }, passphrase)

    /** Reads the file with [read], then decrypts it. Refused while working or while a reset list waits. */
    suspend fun start(read: suspend () -> ByteArray, passphrase: CharArray) {
        try {
            // after the reset the kept list is the only copy of the accounts
            if (afterReset || _state.value == RestoreState.Working) return
            discard()
            _state.value = RestoreState.Working
            try {
                val bytes = read()
                pendingList = withContext(cpu) { decrypt(bytes, passphrase) }
                _state.value = RestoreState.Decrypted
            } catch (e: CancellationException) {
                discard()
                _state.value = RestoreState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = RestoreState.Failed(e)
            }
        } finally {
            passphrase.fill(Char(0))
        }
    }

    /** Replaces the vault with the decrypted list. Does nothing unless [start] succeeded. */
    suspend fun confirmReplace() {
        if (_state.value != RestoreState.Decrypted || pendingList == null) return
        _state.value = RestoreState.Working
        try {
            gateway.reset()
        } catch (e: CancellationException) {
            // whether the reset happened is unknown: the backup file is still there
            discard()
            _state.value = RestoreState.Idle
            throw e
        } catch (e: Exception) {
            // the old vault is still there: start again from the file
            discard()
            _state.value = RestoreState.Failed(e)
            return
        }
        importKept()
    }

    /** Unlocks and imports the kept list: the retry after a cancelled prompt or a failure after the reset. */
    suspend fun finish() {
        // before the reset an import would land in the old vault
        if (!afterReset) return
        importKept()
    }

    /** Drops the decrypted list (zeroing its secrets) and goes back to [RestoreState.Idle]. */
    fun abandon() {
        discard()
        _state.value = RestoreState.Idle
    }

    private suspend fun importKept() {
        val list = pendingList ?: return
        _state.value = RestoreState.Working
        try {
            gateway.unlock()
            val result = gateway.import(list)
            discard()
            _state.value = RestoreState.Done(result)
        } catch (e: VaultAuthRequiredException) {
            _state.value = RestoreState.FinishPending
        } catch (e: CancellationException) {
            _state.value = RestoreState.FinishPending
            throw e
        } catch (e: Exception) {
            // the list stays, so finish() can be tried again
            _state.value = RestoreState.Failed(e, canFinish = true)
        }
    }

    private fun discard() {
        pendingList?.forEach { it.secret.fill(0) }
        pendingList = null
    }
}
