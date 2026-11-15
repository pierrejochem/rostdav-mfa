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
 * with nothing written or read), run the key derivation on [io] (it takes seconds and about 68 MiB), and zero the
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

    fun create(passphrase: CharArray, repeat: CharArray, target: Uri) {
        val problem = passphraseProblem(passphrase, repeat)
        repeat.fill(Char(0))
        if (problem != null || _state.value == BackupState.Working) {
            passphrase.fill(Char(0))
            if (problem != null) {
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
        work(passphrase) {
            if (!ensureOpen()) return@work BackupState.Idle
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

    /** Runs [block] and sets its state; a failure becomes its message, a cancelled prompt [BackupState.Idle]. */
    private fun work(passphrase: CharArray, block: suspend () -> BackupState) {
        viewModelScope.launch {
            _state.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
