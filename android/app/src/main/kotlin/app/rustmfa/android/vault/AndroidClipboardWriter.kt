package app.rustmfa.android.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Copies codes as sensitive clips, so the system clipboard preview (Android 13+) hides them. Clearing is best
 * effort: Android denies clipboard reads to an app in the background, and then the clip stays.
 */
class AndroidClipboardWriter(context: Context) : ClipboardWriter {
    private val manager = context.getSystemService(ClipboardManager::class.java)

    override fun copySensitive(text: String) {
        val clip = ClipData.newPlainText("code", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        manager.setPrimaryClip(clip)
    }

    override fun clearIfStillOurs(text: String) {
        try {
            val current = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (current == text) manager.clearPrimaryClip()
        } catch (e: SecurityException) {
            // no clipboard access right now (background); the clip stays
        }
    }

    private companion object {
        /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); older versions ignore it. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
