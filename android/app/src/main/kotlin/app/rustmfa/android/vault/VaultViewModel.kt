package app.rustmfa.android.vault

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rustmfa.android.R
import app.rustmfa.android.domain.Delayer
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.VaultAuthRequiredException
import app.rustmfa.vault.VaultCorruptException
import app.rustmfa.vault.VaultKeyLostException
import app.rustmfa.vault.VaultLockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** How long a copied code stays on the clipboard before the app tries to clear it. */
const val CLIPBOARD_CLEAR_MILLIS = 30_000L

/**
 * The vault state for the Overview and the add, edit and delete flows (activity scoped).
 *
 * The [ticker] is collected only while the vault is open ([VaultUiState.Unlocked] or [VaultUiState.Empty]) and the
 * app is in the foreground (between [appReturned] and [appLeft]); each tick rebuilds the rows, or shows
 * [VaultUiState.Locked] when the session locked itself. A cancelled prompt is
 * never an error. A mutation that finds the vault locked unlocks and retries exactly once; when the prompt is
 * cancelled or the retry fails the same way, it emits [VaultEvent.UnlockToSave].
 *
 * Generated HOTP codes stay in [hotpCodes] until the next lock. All state is touched on the main thread only.
 *
 * [appScope] outlives the view model (the app-wide scope of `AppContainer`): the clipboard clear scheduled by
 * [copy] runs there, so it still happens when the activity finishes within the 30 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModel(
    private val gateway: VaultGateway,
    private val clock: () -> Long,
    private val clipboard: ClipboardWriter,
    private val prefs: VaultPrefs,
    /** Reads the backup file for the restore over an unreadable vault. */
    private val files: BackupFiles,
    private val appScope: CoroutineScope,
    ticker: Flow<Long> = secondTicker(clock),
    private val delayer: Delayer = Delayer { delay(it) },
    /** The restore over a vault that cannot be opened; the restore screen shows its state. */
    val restore: RestoreFlow = RestoreFlow(gateway),
) : ViewModel() {
    private val _state = MutableStateFlow<VaultUiState>(VaultUiState.Loading)
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    private val _showNudge = MutableStateFlow(false)
    val showNudge: StateFlow<Boolean> = _showNudge.asStateFlow()

    private val hotpCodes = mutableMapOf<String, String>()

    /** The prompt this view model is showing, so a reset can dismiss it first (a reset waits for an open prompt). */
    private var unlocking: Deferred<Unit>? = null

    /** False between [appLeft] and [appReturned]: the ticker does not wake the app in the background. */
    private val foreground = MutableStateFlow(true)

    init {
        refresh()
        viewModelScope.launch {
            combine(_state, foreground) { s, fg -> fg && (s is VaultUiState.Unlocked || s is VaultUiState.Empty) }
                .distinctUntilChanged()
                .flatMapLatest { run -> if (run) ticker else emptyFlow() }
                .collect { epochSeconds -> setState(if (gateway.isUnlocked) openState(epochSeconds) else lockedState()) }
        }
    }

    fun refresh() {
        launchSafely { reload() }
    }

    fun unlock() {
        launchSafely { if (tryUnlock(forSave = false)) reload() }
    }

    /**
     * True when the vault is open, after the prompt if it was not (the prompt creates the vault on first use).
     * A cancelled prompt is false with no message; called from the UI before it opens the add flow.
     */
    suspend fun ensureUnlocked(): Boolean {
        if (gateway.isUnlocked) return true
        return try {
            val open = tryUnlock(forSave = false)
            if (open) reload()
            open && gateway.isUnlocked
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(VaultEvent.Failed(R.string.vault_error_generic))
            false
        }
    }

    fun lock() {
        launchSafely {
            gateway.lock()
            hotpCodes.clear()
            reload()
        }
    }

    fun appLeft() {
        foreground.value = false
        gateway.appLeft()
    }

    /** An auto-lock that happened while away shows as [VaultUiState.Locked]. */
    fun appReturned() {
        gateway.appReturned()
        foreground.value = true
        refresh()
    }

    /** Copies the plain (ungrouped) code and clears it again after [CLIPBOARD_CLEAR_MILLIS]. */
    fun copy(id: String) {
        launchSafely {
            // a generated HOTP code is held in memory: a locked vault must not hand it out
            if (!gateway.isUnlocked) {
                reload()
                return@launchSafely
            }
            val code = hotpCodes[id] ?: try {
                gateway.code(id, clock() / 1000)
            } catch (e: VaultLockedException) {
                reload()
                return@launchSafely
            } catch (e: IllegalArgumentException) {
                return@launchSafely // a HOTP account whose code is not generated
            } catch (e: NoSuchElementException) {
                return@launchSafely
            }
            clipboard.copySensitive(code)
            _events.emit(VaultEvent.Copied)
            appScope.launch {
                delayer.delay(CLIPBOARD_CLEAR_MILLIS)
                clipboard.clearIfStillOurs(code)
            }
        }
    }

    fun generateHotp(id: String) {
        launchSafely {
            try {
                saving { gateway.nextHotp(id) }?.let { hotpCodes[id] = it }
            } finally {
                reload()
            }
        }
    }

    fun addAccount(account: OtpAccount) {
        launchSafely {
            try {
                val result = saving { gateway.import(listOf(account)) } ?: return@launchSafely
                _events.emit(if (result.added > 0) VaultEvent.Added else VaultEvent.Duplicate)
            } finally {
                reload()
            }
        }
    }

    /** Changes the service and account name only. */
    fun updateAccount(id: String, issuer: String, label: String) {
        launchSafely {
            try {
                saving {
                    val accounts = gateway.accounts()
                    try {
                        val target = accounts.firstOrNull { it.id == id } ?: throw NoSuchElementException("no such account")
                        gateway.update(target.copy(issuer = issuer, label = label))
                    } finally {
                        accounts.forEach { it.secret.fill(0) }
                    }
                }
            } finally {
                reload()
            }
        }
    }

    fun deleteAccount(id: String) {
        launchSafely {
            try {
                if (saving { gateway.remove(id) } != null) hotpCodes.remove(id)
            } finally {
                reload()
            }
        }
    }

    /**
     * "Start a new vault" after the confirm: dismisses a prompt that is still open, deletes the unreadable vault and
     * shows [VaultUiState.NoVault] (the next add creates the new one). A failed reset stays at
     * [VaultUiState.NeedsReset] with a message.
     */
    fun startNewVault() {
        launchSafely {
            cancelUnlock()
            try {
                gateway.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(VaultEvent.Failed(R.string.vault_new_failed))
                reload()
                return@launchSafely
            }
            hotpCodes.clear()
            reload()
        }
    }

    /** Reads [source] (capped at [MAX_BACKUP_BYTES]) and decrypts it; the vault is not touched. Zeroes [passphrase]. */
    fun restoreStart(source: Uri, passphrase: CharArray) {
        launchSafely { restore.start({ files.read(source, MAX_BACKUP_BYTES) }, passphrase) }
    }

    /** After the confirm: dismisses an open prompt, then reset, prompt and import (see [RestoreFlow]). */
    fun restoreConfirm() {
        launchSafely {
            try {
                cancelUnlock()
                restore.confirmReplace()
            } finally {
                hotpCodes.clear()
                reload()
            }
        }
    }

    /** "Finish restoring": the prompt and the import of the kept list. */
    fun restoreFinish() {
        launchSafely {
            try {
                restore.finish()
            } finally {
                reload()
            }
        }
    }

    /** Drops the decrypted list (its secrets are zeroed). */
    fun restoreAbandon() {
        restore.abandon()
    }

    override fun onCleared() {
        restore.abandon()
    }

    fun dismissNudge() {
        prefs.nudgeHiddenUntil = clock() + NUDGE_HIDE_MILLIS
        updateNudge()
    }

    /**
     * Runs [block]; when the vault is locked, unlocks and runs it once more. Returns null (after emitting
     * [VaultEvent.UnlockToSave] or a state change) when it did not succeed.
     */
    private suspend fun <T : Any> saving(block: suspend () -> T): T? {
        try {
            return block()
        } catch (e: VaultLockedException) {
            // fall through to the unlock
        } catch (e: VaultAuthRequiredException) {
            // fall through to the unlock
        }
        if (!tryUnlock(forSave = true)) return null
        return try {
            block()
        } catch (e: VaultLockedException) {
            _events.emit(VaultEvent.UnlockToSave)
            null
        } catch (e: VaultAuthRequiredException) {
            _events.emit(VaultEvent.UnlockToSave)
            null
        }
    }

    /** False when the vault stays closed; a cancelled prompt emits [VaultEvent.UnlockToSave] only [forSave]. */
    private suspend fun tryUnlock(forSave: Boolean): Boolean =
        try {
            promptUnlock()
            true
        } catch (e: VaultAuthRequiredException) {
            if (forSave) _events.emit(VaultEvent.UnlockToSave)
            false
        } catch (e: VaultKeyLostException) {
            setState(VaultUiState.NeedsReset)
            false
        } catch (e: VaultCorruptException) {
            setState(VaultUiState.NeedsReset)
            false
        }

    /**
     * Runs the gateway's unlock as a job [cancelUnlock] can stop (which dismisses the prompt). A prompt stopped that
     * way counts as cancelled ([VaultAuthRequiredException]); when the caller itself is cancelled, so is the prompt.
     */
    private suspend fun promptUnlock() {
        val job = viewModelScope.async { gateway.unlock() }
        unlocking = job
        try {
            job.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                job.cancel()
                throw e
            }
            throw VaultAuthRequiredException()
        } finally {
            if (unlocking === job) unlocking = null
        }
    }

    private suspend fun cancelUnlock() {
        unlocking?.cancelAndJoin()
    }

    private suspend fun reload() {
        val next = when {
            gateway.isUnlocked -> openState(clock() / 1000)
            !gateway.hasVault() -> VaultUiState.NoVault
            // a refresh (for example on return to the app) does not hide the "cannot be opened" card
            _state.value == VaultUiState.NeedsReset -> VaultUiState.NeedsReset
            else -> lockedState()
        }
        setState(next)
    }

    private fun openState(epochSeconds: Long): VaultUiState {
        val accounts = try {
            gateway.accounts()
        } catch (e: VaultLockedException) {
            return lockedState()
        }
        return try {
            if (accounts.isEmpty()) {
                VaultUiState.Empty
            } else {
                hotpCodes.keys.retainAll(accounts.map { it.id }.toSet())
                VaultUiState.Unlocked(buildRows(accounts, epochSeconds, hotpCodes) { gateway.code(it.id, epochSeconds) })
            }
        } catch (e: VaultLockedException) {
            lockedState()
        } finally {
            accounts.forEach { it.secret.fill(0) }
        }
    }

    private fun lockedState(): VaultUiState {
        hotpCodes.clear()
        return VaultUiState.Locked
    }

    private fun setState(next: VaultUiState) {
        _state.value = next
        updateNudge()
    }

    private fun updateNudge() {
        val count = (_state.value as? VaultUiState.Unlocked)?.rows?.size ?: 0
        _showNudge.value = showBackupNudge(count, prefs.lastBackupAt, prefs.nudgeHiddenUntil, clock())
    }

    /** Launches [block]; an unexpected failure becomes a generic error message instead of a crash. */
    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(VaultEvent.Failed(R.string.vault_error_generic))
            }
        }
    }
}
