package app.rustmfa.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import app.rustmfa.android.ui.design.LocalBarClearance
import app.rustmfa.android.ui.design.FloatingTabBar
import app.rustmfa.android.ui.design.FloatingTab
import app.rustmfa.android.ui.design.BarClearance
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import app.rustmfa.android.R

object Dest {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val REQUESTS = "requests"
    const val APPROVE = "approve/{id}"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
    const val RECONNECT = "reconnect"

    fun approve(id: String) = "approve/$id"
}

private data class Tab(val route: String, val label: Int, val icon: Int)

private val Tabs =
    listOf(
        Tab(Dest.HOME, R.string.nav_home, R.drawable.ic_nav_home),
        Tab(Dest.REQUESTS, R.string.nav_requests, R.drawable.ic_nav_list),
        Tab(Dest.SETTINGS, R.string.nav_settings, R.drawable.ic_nav_settings),
    )

/** The routes that have a tab. Devices is reached from Settings and has none. */
val TabRoutes: List<String> = Tabs.map { it.route }

/** The bottom bar shows only after enrollment: the onboarding destination has none. */
@Composable
fun AppNavigation(
    enrolled: Boolean,
    needsReconnect: Boolean = false,
    reconnect: @Composable () -> Unit = {},
    devices: @Composable () -> Unit = {},
    server: String,
    version: String,
    home: @Composable (onOpen: (String) -> Unit, onSeeAll: () -> Unit) -> Unit,
    requests: @Composable (onOpen: (String) -> Unit) -> Unit,
    onboarding: @Composable (onDone: () -> Unit) -> Unit,
    approve: @Composable (id: String, onClose: () -> Unit) -> Unit,
    nav: NavHostController = rememberNavController(),
    pendingCount: Int = 0,
) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route != null && route != Dest.ONBOARDING && route != Dest.RECONNECT && route != Dest.APPROVE
    val tabs = Tabs.map { FloatingTab(it.route, stringResource(it.label), it.icon, if (it.route == Dest.REQUESTS) pendingCount else null) }
    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets.statusBars) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CompositionLocalProvider(LocalBarClearance provides if (showBar) BarClearance else 0.dp) {
                NavHost(nav, startDestination = if (needsReconnect) Dest.RECONNECT else if (enrolled) Dest.HOME else Dest.ONBOARDING, modifier = Modifier.fillMaxSize()) {
                    composable(Dest.ONBOARDING) {
                        onboarding {
                            nav.navigate(Dest.HOME) { popUpTo(Dest.ONBOARDING) { inclusive = true } }
                        }
                    }
                    composable(Dest.RECONNECT) { reconnect() }
                    composable(Dest.HOME) { home({ id -> nav.navigate(Dest.approve(id)) }, { nav.navigate(Dest.REQUESTS) }) }
                    composable(Dest.REQUESTS) { requests { id -> nav.navigate(Dest.approve(id)) } }
                    composable(Dest.APPROVE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                        approve(e.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
                    }
                    composable(Dest.DEVICES) { devices() }
                    composable(Dest.SETTINGS) { SettingsScreen(server, version, onOpenDevices = { nav.navigate(Dest.DEVICES) }) }
                }
            }
            if (showBar) {
                FloatingTabBar(
                    tabs,
                    selected = if (route == Dest.DEVICES) Dest.SETTINGS else route.orEmpty(),
                    onSelect = { target ->
                        nav.navigate(target) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 26.dp),
                )
            }
        }
    }
}
