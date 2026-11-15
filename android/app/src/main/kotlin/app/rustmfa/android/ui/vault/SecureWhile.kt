package app.rustmfa.android.ui.vault

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

/**
 * Blocks screenshots and screen recording of the host window (`FLAG_SECURE`) while [active], and lets go when
 * [active] turns false or this leaves the composition. Several holders can want the flag at once (the Approve
 * screen and the unlocked vault): the flag goes only when the last one lets go.
 */
@Composable
fun SecureWhile(active: Boolean) {
    val window = LocalActivity.current?.window
    DisposableEffect(active, window) {
        if (active && window != null) SecureHolds.take(window)
        onDispose { if (active && window != null) SecureHolds.release(window) }
    }
}

/** Counts the holders per window; touched on the main thread only. */
private object SecureHolds {
    private val holds = WeakHashMap<Window, Int>()

    fun take(window: Window) {
        val n = holds[window] ?: 0
        holds[window] = n + 1
        if (n == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val n = (holds[window] ?: 1) - 1
        if (n <= 0) {
            holds.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holds[window] = n
        }
    }
}
