package app.rustmfa.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import app.rustmfa.android.domain.SessionEndGate
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.push.AndroidNotificationSink
import app.rustmfa.android.push.PendingPoller
import app.rustmfa.android.push.PushPayload
import app.rustmfa.android.ui.Dest
import app.rustmfa.android.ui.DevicesScreen
import app.rustmfa.android.ui.DevicesViewModel
import app.rustmfa.android.ui.ReconnectScreen
import app.rustmfa.android.ui.HomeScreen
import app.rustmfa.android.ui.RequestsScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.rustmfa.android.ui.vault.AccountActions
import app.rustmfa.android.ui.vault.AccountsSection
import app.rustmfa.android.ui.vault.SecureWhile
import app.rustmfa.android.vault.BackupFiles
import app.rustmfa.android.vault.BiometricUnlocker
import app.rustmfa.android.vault.VaultEvent
import app.rustmfa.android.vault.VaultUiState
import app.rustmfa.android.vault.VaultViewModel
import android.net.Uri
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private val openChallenge = mutableStateOf<String?>(null)
    private val notificationsOff = mutableStateOf(false)
    private val pendingList = mutableStateOf<List<ChallengeDetails>>(emptyList())
    private var poller: PendingPoller? = null
    private var sessionJob: Job? = null
    private val sessionEnd = SessionEndGate()
    private val sessionEnded = mutableStateOf(false)

    /** Activity scoped: the vault state, the ticker and the lifecycle hooks. */
    private val vault: VaultViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = AppContainer.get(applicationContext)
                // the session's auto-lock is on the monotonic clock (AppContainer); codes and prefs need wall time
                VaultViewModel(app.vault, System::currentTimeMillis, app.clipboard, app.vaultPrefs, NoBackupFiles, app.appScope)
            }
        }
    }

    /** This activity's prompt; onStop removes it only if a newer activity has not put its own in place. */
    private val authenticator: suspend () -> Unit = { withContext(Dispatchers.Main) { BiometricUnlocker(this@MainActivity).authenticate() } }

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNotificationState() }

    /** A new task with no saved navigation state: after a wipe, no screen may come back for an enrollment that is gone. */
    private fun restartFresh() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun refreshNotificationState() {
        notificationsOff.value = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    /** A notification tap names a challenge; anything that is not a plain id is dropped. */
    private fun takeIntent(i: Intent?) {
        val id = PushPayload.challengeId(mapOf("challenge_id" to i?.getStringExtra(AndroidNotificationSink.EXTRA_CHALLENGE).orEmpty()))
        if (id != null) openChallenge.value = id
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        poller?.setForeground(true)
        // the prompt needs a started activity and the main thread
        AppContainer.get(applicationContext).authenticator = authenticator
        vault.appReturned()
    }

    override fun onStop() {
        poller?.setForeground(false)
        vault.appLeft()
        // after restartFresh() the new activity has already started (and set its own) when this one stops
        val app = AppContainer.get(applicationContext)
        if (app.authenticator === authenticator) app.authenticator = AppContainer.NO_AUTHENTICATOR
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationState()
    }

    /** Starts polling and the notification sync for an enrolled phone; safe to call again. */
    private fun startSession(app: AppContainer, e: Enrollment) {
        if (sessionJob?.isActive == true) return
        val p = app.poller(e)
        poller = p
        p.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val n = app.notifier(e)
        sessionJob = lifecycleScope.launch {
            launch { p.run() }
            p.pending.collect { list ->
                pendingList.value = list
                n.sync(list)
            }
        }
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        takeIntent(intent)
        val app = AppContainer.get(applicationContext)
        app.onSessionEnded = {
            runOnUiThread {
                // an open Approve screen shows the message itself and restarts when it closes
                if (sessionEnd.onSessionEnded() == SessionEndGate.Action.RestartNow) {
                    Toast.makeText(this, R.string.result_revoked, Toast.LENGTH_LONG).show()
                    restartFresh()
                } else {
                    // the keys are gone: nothing may sign another request until the restart
                    sessionJob?.cancel()
                    sessionEnded.value = true
                }
            }
        }
        setContent {
            val vaultState by vault.state.collectAsState()
            val showNudge by vault.showNudge.collectAsState()
            var menuId by remember { mutableStateOf<String?>(null) }
            SecureWhile(vaultState is VaultUiState.Unlocked || vaultState == VaultUiState.Empty)
            LaunchedEffect(Unit) {
                vault.events.collect { e ->
                    when (e) {
                        // Android 13+ confirms a copy itself
                        VaultEvent.Copied -> if (Build.VERSION.SDK_INT < 33) Toast.makeText(this@MainActivity, R.string.vault_copied, Toast.LENGTH_SHORT).show()
                        is VaultEvent.Failed -> Toast.makeText(this@MainActivity, e.messageRes, Toast.LENGTH_LONG).show()
                        else -> Unit // the add and edit forms (later tasks) handle the rest
                    }
                }
            }
            RustMfaTheme {
                // null while the store is read; the start destination is chosen once
                var loaded by remember { mutableStateOf<Pair<Boolean, Enrollment?>?>(null) }
                LaunchedEffect(Unit) {
                    val e = try { app.store.load() } catch (_: Exception) { null }
                    loaded = (e != null) to e
                }
                loaded?.let { (enrolled, enrollment) ->
                    val nav = rememberNavController()
                    LaunchedEffect(enrollment) { if (enrollment != null && !enrollment.needsReconnect) startSession(app, enrollment) }
                    val wanted = openChallenge.value
                    LaunchedEffect(wanted, enrolled) {
                        if (wanted != null && enrolled) {
                            nav.navigate(Dest.approve(wanted))
                            openChallenge.value = null
                        }
                    }
                    AppNavigation(
                        nav = nav,
                        needsReconnect = enrollment?.needsReconnect == true,
                        reconnect = {
                            ReconnectScreen(enrollment?.server ?: "") { app.resetEnrollment(); restartFresh() }
                        },
                        devices = {
                            val e = enrollment
                            if (e != null) {
                                val vm: DevicesViewModel =
                                    viewModel(factory = viewModelFactory { initializer { DevicesViewModel(app.signedApi(e), e.deviceId, app.guard) } })
                                DevicesScreen(vm, onThisPhoneRevoked = { restartFresh() })
                            }
                        },
                        home = { onOpen, onSeeAll ->
                            AccountActions(
                                state = vaultState,
                                openId = menuId,
                                onClose = { menuId = null },
                                onCopy = vault::copy,
                                onGenerate = vault::generateHotp,
                                onUpdate = vault::updateAccount,
                                onDelete = vault::deleteAccount,
                            )
                            HomeScreen(enrollment?.server ?: "", pendingList.value, notificationsOff.value, onOpen, onSeeAll) {
                                AccountsSection(
                                    state = vaultState,
                                    showNudge = showNudge,
                                    onUnlock = vault::unlock,
                                    onLock = vault::lock,
                                    onAdd = {}, // the Add account screen (task 5)
                                    onCopy = vault::copy,
                                    onGenerate = vault::generateHotp,
                                    onOpenMenu = { menuId = it },
                                    onBackup = {}, // the Backup screen (task 6)
                                    onDismissNudge = vault::dismissNudge,
                                    onRestore = {}, // the cannot-be-opened flow (task 7)
                                    onNewVault = {}, // the cannot-be-opened flow (task 7)
                                )
                            }
                        },
                        requests = { onOpen -> RequestsScreen(pendingList.value, onOpen) },
                        enrolled = enrolled,
                        pendingCount = pendingList.value.size,
                        server = enrollment?.server ?: "",
                        version = BuildConfig.VERSION_NAME,
                        onboarding = { onDone0 ->
                            val onDone: () -> Unit = {
                                // the new enrollment starts the session; the stored value is read again
                                // read the new enrollment: every screen needs the server and the device id from it
                                lifecycleScope.launch {
                                    val e = runCatching { app.store.load() }.getOrNull()
                                    loaded = (e != null) to e
                                    if (e != null) startSession(app, e)
                                    vault.refresh()
                                    onDone0()
                                }
                            }
                            val vm: OnboardingViewModel =
                                viewModel(factory = viewModelFactory { initializer { OnboardingViewModel(app.enrollmentRepository(), BuildConfig.DEBUG) } })
                            OnboardingScreen(vm, defaultName = Build.MODEL ?: "Phone", onDone = onDone)
                        },
                        approve = { id, onClose ->
                            val e = enrollment
                            if (e != null) {
                                DisposableEffect(Unit) {
                                    sessionEnd.approveOpened()
                                    onDispose { if (sessionEnd.approveClosed()) restartFresh() }
                                }
                                val vm: ApproveViewModel =
                                    viewModel(key = id, factory = viewModelFactory { initializer { ApproveViewModel(app.challengeFlow(e), id) } })
                                ApproveScreen(vm, revoked = sessionEnded.value, onClose = onClose, onReconnect = { invalidated ->
                                    lifecycleScope.launch {
                                        if (invalidated) app.guard.onKeyInvalidated() else app.resetEnrollment()
                                        restartFresh()
                                    }
                                })
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Backup and restore arrive with the Backup screen; nothing calls these yet. */
private object NoBackupFiles : BackupFiles {
    override suspend fun read(uri: Uri, maxBytes: Int): ByteArray = throw UnsupportedOperationException("no backup screen yet")

    override suspend fun write(uri: Uri, bytes: ByteArray) = throw UnsupportedOperationException("no backup screen yet")
}
