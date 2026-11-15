package app.rustmfa.android.vault

/** UI preferences kept outside the vault. */
interface VaultPrefs {
    /** Epoch milliseconds of the last backup, or null when none was made. */
    var lastBackupAt: Long?

    /** Epoch milliseconds until which the backup nudge stays hidden. */
    var nudgeHiddenUntil: Long
}
