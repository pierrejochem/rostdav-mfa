package app.rustmfa.android

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.Test

/** The vault prompt may only be shown by a started activity: onStop puts the throwing authenticator back. */
class MainActivityLifecycleTest {
    private val app = AppContainer.get(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun theAuthenticatorIsSetWhileStartedAndRestoredOnStop() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertNotSame(AppContainer.NO_AUTHENTICATOR, app.authenticator, "no prompt is wired while started")
            scenario.moveToState(Lifecycle.State.CREATED)
            assertSame(AppContainer.NO_AUTHENTICATOR, app.authenticator, "onStop must restore the throwing authenticator")
        }
    }

    /**
     * restartFresh() starts a new MainActivity and finishes this one; the new one starts before the old one stops,
     * so the old onStop must not take the new one's authenticator away.
     */
    @Test
    fun aRestartKeepsTheNewActivitysAuthenticator() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var old: Activity? = null
        scenario.onActivity { a ->
            old = a
            a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            a.finish()
        }
        var fresh: Activity? = null
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            instr.waitForIdleSync()
            var oldGone = false
            instr.runOnMainSync {
                val registry = ActivityLifecycleMonitorRegistry.getInstance()
                fresh = registry.getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity && it !== old }
                oldGone = registry.getLifecycleStageOf(old) in setOf(Stage.STOPPED, Stage.DESTROYED)
            }
            if (fresh != null && oldGone) break
            Thread.sleep(100)
        }
        try {
            assertNotNull(fresh, "the new activity did not resume")
            assertNotSame(AppContainer.NO_AUTHENTICATOR, app.authenticator, "the old activity's onStop cleared the new one's authenticator")
        } finally {
            instr.runOnMainSync { fresh?.finish() }
            scenario.close()
        }
    }
}
