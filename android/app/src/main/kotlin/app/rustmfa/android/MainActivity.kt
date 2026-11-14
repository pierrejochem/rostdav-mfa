package app.rustmfa.android

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.rustmfa.android.enroll.Enrollment
import app.rustmfa.android.ui.AppNavigation
import app.rustmfa.android.ui.ApproveScreen
import app.rustmfa.android.ui.ApproveViewModel
import app.rustmfa.android.ui.OnboardingScreen
import app.rustmfa.android.ui.OnboardingViewModel
import app.rustmfa.android.ui.RustMfaTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = AppContainer(applicationContext)
        setContent {
            RustMfaTheme {
                // null while the store is read; the start destination is chosen once
                val loaded by produceState<Pair<Boolean, Enrollment?>?>(null) {
                    val e = try { app.store.load() } catch (_: Exception) { null }
                    value = (e != null) to e
                }
                loaded?.let { (enrolled, enrollment) ->
                    AppNavigation(
                        enrolled = enrolled,
                        server = enrollment?.server ?: "",
                        version = BuildConfig.VERSION_NAME,
                        onboarding = { onDone ->
                            val vm: OnboardingViewModel =
                                viewModel(factory = viewModelFactory { initializer { OnboardingViewModel(app.enrollmentRepository(), BuildConfig.DEBUG) } })
                            OnboardingScreen(vm, defaultName = Build.MODEL ?: "Phone", onDone = onDone)
                        },
                        approve = { id, onClose ->
                            val e = enrollment
                            if (e != null) {
                                val vm: ApproveViewModel =
                                    viewModel(key = id, factory = viewModelFactory { initializer { ApproveViewModel(app.challengeFlow(e), id) } })
                                ApproveScreen(vm, onClose = onClose, onReconnect = { app.resetEnrollment(); recreate() })
                            }
                        },
                    )
                }
            }
        }
    }
}
