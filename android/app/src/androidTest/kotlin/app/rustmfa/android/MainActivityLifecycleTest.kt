package app.rustmfa.android

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
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
}
