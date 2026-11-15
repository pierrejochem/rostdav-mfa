package app.rustmfa.android.vault

import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultBackup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface RestoreState {
    data object Idle : RestoreState

    /** The backup is decrypted and held in memory; nothing in the vault has changed yet. */
    data object Decrypted : RestoreState

    /** The old vault is gone but the prompt was cancelled; [RestoreFlow.finish] imports the kept list. */
    data object FinishPending : RestoreState

    data class Done(val result: ImportResult) : RestoreState

    data class Failed(val error: Throwable) : RestoreState
}

/**
 * Restore over an unreadable vault, in the order that makes a wrong passphrase cost nothing: [start] decrypts
 * (off the main thread, the key derivation takes seconds) and touches no vault state; [confirmReplace] then
 * resets, unlocks (the prompt creates the new key) and imports. A cancelled prompt after the reset keeps the
 * decrypted list for [finish].
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

    suspend fun start(bytes: ByteArray, passphrase: CharArray) {
        discard()
        try {
            pendingList = withContext(cpu) { decrypt(bytes, passphrase) }
            _state.value = RestoreState.Decrypted
        } catch (e: Exception) {
            _state.value = RestoreState.Failed(e)
        }
    }

    /** Replaces the vault with the decrypted list. Does nothing unless [start] succeeded. */
    suspend fun confirmReplace() {
        if (_state.value != RestoreState.Decrypted || pendingList == null) return
        try {
            gateway.reset()
        } catch (e: Exception) {
            _state.value = RestoreState.Failed(e)
            return
        }
        finish()
    }

    /** Unlocks and imports the kept list; also the retry after a cancelled prompt. */
    suspend fun finish() {
        val list = pendingList ?: return
        try {
            gateway.unlock()
            val result = gateway.import(list)
            discard()
            _state.value = RestoreState.Done(result)
        } catch (e: VaultAuthRequiredException) {
            _state.value = RestoreState.FinishPending
        } catch (e: Exception) {
            // the list stays, so finish() can be tried again
            _state.value = RestoreState.Failed(e)
        }
    }

    private fun discard() {
        pendingList?.forEach { it.secret.fill(0) }
        pendingList = null
    }
}
