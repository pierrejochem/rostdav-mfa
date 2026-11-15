package app.rustmfa.android.vault

import android.content.Context
import androidx.core.content.edit

/** The vault's UI preferences in the app's "ui" shared preferences (never in the vault itself). */
class AndroidVaultPrefs(context: Context) : VaultPrefs {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    override var lastBackupAt: Long?
        get() = if (prefs.contains(LAST_BACKUP_AT)) prefs.getLong(LAST_BACKUP_AT, 0) else null
        set(value) = prefs.edit { if (value == null) remove(LAST_BACKUP_AT) else putLong(LAST_BACKUP_AT, value) }

    override var nudgeHiddenUntil: Long
        get() = prefs.getLong(NUDGE_HIDDEN_UNTIL, 0)
        set(value) = prefs.edit { putLong(NUDGE_HIDDEN_UNTIL, value) }

    private companion object {
        const val LAST_BACKUP_AT = "last_backup_at"
        const val NUDGE_HIDDEN_UNTIL = "nudge_hidden_until"
    }
}
