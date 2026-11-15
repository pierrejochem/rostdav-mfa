package app.rustmfa.android.ui.vault

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavType
import androidx.navigation.navArgument
import app.rustmfa.android.vault.BackupState
import app.rustmfa.android.vault.BackupViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.rustmfa.android.R
import app.rustmfa.android.ui.Dest
import app.rustmfa.android.vault.VaultViewModel
import app.rustmfa.android.vault.secondTicker
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType

object VaultDest {
    const val ADD = "vault/add"
    const val SCAN = "vault/scan"
    const val CONFIRM = "vault/confirm"
    const val MANUAL = "vault/manual"
}

/**
 * The scanned account between the scanner and Confirm, held in memory only (never in saved state or a route) and
 * scoped to the Add screen's back stack entry. The secret is zeroed when Confirm is left or the add flow ends.
 */
class ScannedAccountHolder : ViewModel() {
    var account: OtpAccount? = null
        private set

    fun put(a: OtpAccount) {
        clear()
        account = a
    }

    fun clear() {
        account?.secret?.fill(0)
        account = null
    }

    override fun onCleared() = clear()
}

/** The by-hand form, kept across a rotation and dropped when the screen is left. */
class ManualFormHolder : ViewModel() {
    val form = ManualAccountForm()

    override fun onCleared() = form.clear()
}

/**
 * The add flow (the Add screen, the scanner, Confirm, and by hand; an added account returns to the Overview) and the
 * Backup screen, whose view model [backupFactory] builds (scoped to its back stack entry, so a rotation keeps a
 * running backup).
 */
fun NavGraphBuilder.vaultGraph(nav: NavHostController, vm: VaultViewModel, backupFactory: ViewModelProvider.Factory) {
    composable(
        Dest.BACKUP,
        arguments = listOf(navArgument("restore") {
            type = NavType.BoolType
            defaultValue = false
        }),
    ) { entry ->
        val backup: BackupViewModel = viewModel(factory = backupFactory)
        val state by backup.state.collectAsState()
        // the Overview rows and the nudge follow a restore or a new backup
        LaunchedEffect(state) { if (state == BackupState.Saved || state is BackupState.Restored) vm.refresh() }
        BackupScreen(backup, onBack = { nav.popIfAt(Dest.BACKUP) }, startRestore = entry.arguments?.getBoolean("restore") == true)
    }
    composable(VaultDest.ADD) {
        AddAccountScreen(
            onScan = { nav.navigate(VaultDest.SCAN) { launchSingleTop = true } },
            onManual = { nav.navigate(VaultDest.MANUAL) { launchSingleTop = true } },
            // restore into the open vault, on the Backup screen
            onImport = { nav.navigate(Dest.backup(restore = true)) { launchSingleTop = true } },
            onBack = { nav.popIfAt(VaultDest.ADD) },
        )
    }
    composable(VaultDest.SCAN) { entry ->
        val holder = scannedHolder(nav, entry)
        VaultScanScreen(
            onAccount = { account ->
                holder.put(account)
                nav.navigate(VaultDest.CONFIRM) { popUpTo(VaultDest.SCAN) { inclusive = true } }
            },
            onManual = { nav.navigate(VaultDest.MANUAL) { popUpTo(VaultDest.SCAN) { inclusive = true } } },
            onBack = { nav.popIfAt(VaultDest.SCAN) },
        )
    }
    composable(VaultDest.CONFIRM) { entry ->
        val holder = scannedHolder(nav, entry)
        // this screen's own copy, zeroed when it leaves the composition (the holder keeps its own for a rotation)
        val account = remember { holder.account?.let { it.copy(secret = it.secret.copyOf()) } }
        DisposableEffect(account) { onDispose { account?.secret?.fill(0) } }
        if (account == null) {
            // the process was restarted: the secret was never saved, so scan again
            LaunchedEffect(Unit) { nav.popIfAt(VaultDest.CONFIRM) }
            return@composable
        }
        var duplicate by rememberSaveable { mutableStateOf(false) }
        val leave = {
            holder.clear()
            nav.popIfAt(VaultDest.CONFIRM)
        }
        BackHandler(onBack = leave)
        AddEvents(vm.events, onAdded = {
            holder.clear()
            nav.toOverview()
        }, onDuplicate = { duplicate = true })
        val code by produceState(confirmCode(account, System.currentTimeMillis() / 1000), account) {
            if (account.type == OtpType.TOTP) secondTicker(System::currentTimeMillis).collect { value = confirmCode(account, it) }
        }
        ConfirmAccountScreen(
            account = account,
            firstCode = code,
            onAdd = { issuer, label ->
                duplicate = false
                // an independent copy: the import may still read it after this screen zeroed its own
                vm.addAccount(account.copy(issuer = issuer, label = label, secret = account.secret.copyOf()))
            },
            onBack = leave,
            message = if (duplicate) stringResource(R.string.vault_duplicate) else null,
        )
    }
    composable(VaultDest.MANUAL) {
        val holder: ManualFormHolder = viewModel()
        var duplicate by rememberSaveable { mutableStateOf(false) }
        AddEvents(vm.events, onAdded = {
            holder.form.clear()
            nav.toOverview()
        }, onDuplicate = { duplicate = true })
        ManualAccountScreen(
            form = holder.form,
            onAdd = { account ->
                duplicate = false
                vm.addAccount(account)
            },
            onBack = { nav.popIfAt(VaultDest.MANUAL) },
            message = if (duplicate) stringResource(R.string.vault_duplicate) else null,
        )
    }
}

@Composable
private fun scannedHolder(nav: NavHostController, entry: NavBackStackEntry): ScannedAccountHolder {
    val parent = remember(entry) { nav.getBackStackEntry(VaultDest.ADD) }
    return viewModel(parent)
}

/** Pops only while [route] is on top, so a second tap or a late callback cannot pop the screen below. */
private fun NavHostController.popIfAt(route: String) {
    if (currentBackStackEntry?.destination?.route == route) popBackStack()
}

private fun NavHostController.toOverview() {
    if (!popBackStack(Dest.HOME, inclusive = false)) popBackStack(VaultDest.ADD, inclusive = true)
}
