package app.rustmfa.android.vault

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** Clipboard reads need a focused window of this app (Android 10+), so the test runs inside an activity. */
class AndroidClipboardWriterTest {
    @get:Rule val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    private fun onActivity(block: (ComponentActivity, ClipboardManager) -> Unit) {
        scenario.scenario.onActivity { block(it, it.getSystemService(ClipboardManager::class.java)) }
    }

    private fun ClipboardManager.firstText(): String? = primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    @Test
    fun aCopiedCodeIsMarkedSensitive() = onActivity { activity, cm ->
        AndroidClipboardWriter(activity).copySensitive("123456")
        assertEquals("123456", cm.firstText())
        assertTrue(cm.primaryClipDescription?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
    }

    @Test
    fun clearIfStillOursClearsOurCode() = onActivity { activity, cm ->
        val w = AndroidClipboardWriter(activity)
        w.copySensitive("123456")
        w.clearIfStillOurs("123456")
        assertTrue(cm.firstText().isNullOrEmpty(), "the clip still holds ${cm.firstText()}")
    }

    @Test
    fun clearIfStillOursLeavesSomethingElse() = onActivity { activity, cm ->
        val w = AndroidClipboardWriter(activity)
        w.copySensitive("123456")
        cm.setPrimaryClip(ClipData.newPlainText("other", "copied elsewhere"))
        w.clearIfStillOurs("123456")
        assertEquals("copied elsewhere", cm.firstText())
    }
}
