package app.rustmfa.android.vault

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rustmfa.android.R
import app.rustmfa.vault.BackupDecryptException
import app.rustmfa.vault.BackupFormatException
import app.rustmfa.vault.ImportResult
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultBackup
import app.rustmfa.vault.VaultLockedException
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backup files larger than this are rejected before they are read in full. */
const val MAX_BACKUP_BYTES = 5 * 1024 * 1024

/** [backupMessage] for a failure that shows no message (the user cancelled the prompt). */
const val NO_MESSAGE = 0

sealed interface BackupState {
    data object Idle : BackupState

    /** The key derivation or the file is in progress. */
    data object Working : BackupState

    /** The vault is open and the passphrase held: the save picker should open. */
    data object ChooseFile : BackupState

    data object Saved : BackupState

    data class Restored(val result: ImportResult) : BackupState

    data class Failed(@StringRes val messageRes: Int) : BackupState
}

/** The suggested file name, with the UTC date: `rostdav-mfa-backup-2023-11-14.json`. */
fun backupFileName(nowMillis: Long): String =
    "rostdav-mfa-backup-${Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate()}.json"

/** The plain-words message for a backup or restore failure; [NO_MESSAGE] for a cancelled prompt. */
@StringRes
fun backupMessage(t: Throwable): Int =
    when (t) {
        is VaultAuthRequiredException -> NO_MESSAGE
        is BackupFormatException -> if (t.message == VaultBackup.KDF_OUT_OF_MEMORY) R.string.backup_error_memory else R.string.backup_error_format
        is BackupDecryptException -> R.string.backup_error_decrypt
        is IOException -> R.string.backup_error_file
        else -> R.string.vault_error_generic
    }

/**
 * Create and restore for the Backup screen. Both make sure the vault is open first (a cancelled prompt ends [Idle]
 * with nothing written or read; for a create this happens before the save picker, which creates the file), run the key derivation on [io] (it takes seconds and about 68 MiB), and zero the
 * passphrase arrays they were given when they are done. Restore imports into the open vault: accounts already in it
 * are skipped. The last-backup time goes to [prefs], never into the vault.
 */
class BackupViewModel(
    private val gateway: VaultGateway,
    private val files: BackupFiles,
    private val prefs: VaultPrefs,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val exporter: (List<OtpAccount>, CharArray) -> ByteArray = { accounts, passphrase -> VaultBackup.export(accounts, passphrase) },
    private val decrypter: (ByteArray, CharArray) -> List<OtpAccount> = VaultBackup::import,
) : ViewModel() {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /** The checked passphrase while the save picker is open; zeroed when it is used, dropped or the screen is left. */
    private var pending: CharArray? = null

    /**
     * Step one of a create: checks the passphrases, makes sure the vault is open (a cancelled prompt ends [Idle]),
     * then holds the passphrase and moves to [BackupState.ChooseFile] so the screen opens the save picker. The picker
     * creates the file, so nothing may stop the backup between the picker and [fileChosen]. A tap while a create is
     * under way is ignored.
     */
    fun prepare(passphrase: CharArray, repeat: CharArray) {
        val problem = passphraseProblem(passphrase, repeat)
        repeat.fill(Char(0))
        val busy = _state.value == BackupState.Working || _state.value == BackupState.ChooseFile
        if (problem != null || busy) {
            passphrase.fill(Char(0))
            if (problem != null && !busy) {
                _state.value = BackupState.Failed(
                    when (problem) {
                        PassphraseProblem.TooShort -> R.string.backup_error_short
                        PassphraseProblem.Mismatch -> R.string.backup_error_mismatch
                    },
                )
            }
            return
        }
        _state.value = BackupState.Working
        viewModelScope.launch {
            val open = try {
                ensureOpen()
            } catch (e: CancellationException) {
                passphrase.fill(Char(0))
                throw e
            } catch (e: Exception) {
                passphrase.fill(Char(0))
                val message = backupMessage(e)
                _state.value = if (message == NO_MESSAGE) BackupState.Idle else BackupState.Failed(message)
                return@launch
            }
            if (!open) {
                passphrase.fill(Char(0))
                _state.value = BackupState.Idle
                return@launch
            }
            dropPending()
            pending = passphrase
            _state.value = BackupState.ChooseFile
        }
    }

    /**
     * Step two: the save picker returned. Null (cancelled) ends [Idle]. A file that arrives with no passphrase held
     * (the app was recreated while the picker was open) is deleted and the screen asks for the passphrase again. A
     * failed or cancelled backup deletes the file the picker created, so no empty or partial backup is left behind.
     */
    fun fileChosen(target: Uri?) {
        val passphrase = pending
        pending = null
        if (target == null) {
            passphrase?.fill(Char(0))
            if (_state.value == BackupState.ChooseFile) _state.value = BackupState.Idle
            return
        }
        if (passphrase == null) {
            _state.value = BackupState.Failed(R.string.backup_error_again)
            viewModelScope.launch { deleteQuietly(target) }
            return
        }
        _state.value = BackupState.Working
        work(passphrase, onFailure = { deleteQuietly(target) }) {
            if (!ensureOpen()) {
                deleteQuietly(target)
                return@work BackupState.Idle
            }
            val accounts = gateway.accounts()
            val bytes = try {
                withContext(io) { exporter(accounts, passphrase) }
            } finally {
                accounts.forEach { it.secret.fill(0) }
            }
            files.write(target, bytes)
            prefs.lastBackupAt = clock()
            BackupState.Saved
        }
    }

    private suspend fun deleteQuietly(target: Uri) {
        try {
            files.delete(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best effort: the message about the backup matters more than a leftover file
        }
    }

    private fun dropPending() {
        pending?.fill(Char(0))
        pending = null
    }

    override fun onCleared() {
        dropPending()
    }

    fun restore(source: Uri, passphrase: CharArray) {
        if (_state.value == BackupState.Working) {
            passphrase.fill(Char(0))
            return
        }
        _state.value = BackupState.Working
        work(passphrase) {
            if (!ensureOpen()) return@work BackupState.Idle
            val bytes = files.read(source, MAX_BACKUP_BYTES)
            val list = withContext(io) { decrypter(bytes, passphrase) }
            try {
                val result = try {
                    gateway.import(list)
                } catch (e: VaultLockedException) {
                    // locked while the key was derived (the app was left): one more prompt, one more try
                    if (!ensureOpen()) return@work BackupState.Idle
                    gateway.import(list)
                }
                BackupState.Restored(result)
            } finally {
                list.forEach { it.secret.fill(0) }
            }
        }
    }

    /**
     * Runs [block] and sets its state; a failure becomes its message, a cancelled prompt [BackupState.Idle]. [onFailure]
     * runs on any failure, cancellation included.
     */
    private fun work(passphrase: CharArray, onFailure: suspend () -> Unit = {}, block: suspend () -> BackupState) {
        viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { onFailure() }
                throw e
            } catch (e: Exception) {
                onFailure()
                val message = backupMessage(e)
                if (message == NO_MESSAGE) BackupState.Idle else BackupState.Failed(message)
            } finally {
                passphrase.fill(Char(0))
            }
        }
    }

    /** True once the vault is open; the prompt shows when it is not (and creates the vault on first use). */
    private suspend fun ensureOpen(): Boolean {
        if (!gateway.isUnlocked) gateway.unlock()
        return gateway.isUnlocked
    }
}
