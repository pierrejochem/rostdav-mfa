package app.rustmfa.android.vault

const val NUDGE_HIDE_MILLIS = 7 * 24 * 3600 * 1000L

/** The "No backup yet" banner: accounts exist, no backup was ever made, and it is not dismissed. */
fun showBackupNudge(accountCount: Int, lastBackupAt: Long?, hiddenUntil: Long, now: Long): Boolean =
    accountCount > 0 && lastBackupAt == null && now >= hiddenUntil
