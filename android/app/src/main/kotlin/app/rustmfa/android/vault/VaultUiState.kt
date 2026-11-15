package app.rustmfa.android.vault

import androidx.annotation.StringRes
import app.rustmfa.android.R

sealed interface VaultUiState {
    data object Loading : VaultUiState

    data object NoVault : VaultUiState

    data object Locked : VaultUiState

    /** Unlocked, no accounts. */
    data object Empty : VaultUiState

    data class Unlocked(val rows: List<AccountRow>) : VaultUiState

    /** The key is lost or the stored vault is corrupt: only a restore or a new vault helps. */
    data object NeedsReset : VaultUiState
}

/** One-shot events for a snackbar or a form. */
sealed interface VaultEvent {
    data object Copied : VaultEvent

    data object Added : VaultEvent

    data object Duplicate : VaultEvent

    data class Failed(@StringRes val messageRes: Int) : VaultEvent

    /** A save needed the prompt and the user cancelled it; the form stays. */
    data object UnlockToSave : VaultEvent
}

/** The toast text for an event that is shown as one (the add forms show their own); null for the rest. */
@StringRes
fun VaultEvent.toastMessage(): Int? =
    when (this) {
        VaultEvent.UnlockToSave -> R.string.vault_unlock_to_save
        is VaultEvent.Failed -> messageRes
        VaultEvent.Copied, VaultEvent.Added, VaultEvent.Duplicate -> null
    }
