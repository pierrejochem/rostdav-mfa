package app.rustmfa.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
    const val APPROVE = "approve/{id}"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"

    fun approve(id: String) = "approve/$id"
}

private data class Tab(val route: String, val label: Int, val glyph: String)

private val Tabs =
    listOf(
        Tab(Dest.HOME, R.string.nav_home, "●"),
        Tab(Dest.DEVICES, R.string.nav_devices, "▣"),
        Tab(Dest.SETTINGS, R.string.nav_settings, "≡"),
    )

/** The bottom bar shows only after enrollment: the onboarding destination has none. */
@Composable
fun AppNavigation(
    enrolled: Boolean,
    server: String,
    version: String,
    onboarding: @Composable (onDone: () -> Unit) -> Unit,
    approve: @Composable (id: String, onClose: () -> Unit) -> Unit,
    nav: NavHostController = rememberNavController(),
) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    Scaffold(
        bottomBar = {
            if (route != null && route != Dest.ONBOARDING) {
                NavigationBar {
                    for (t in Tabs) {
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Text(t.glyph) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = if (enrolled) Dest.HOME else Dest.ONBOARDING, modifier = Modifier.padding(padding)) {
            composable(Dest.ONBOARDING) {
                onboarding {
                    nav.navigate(Dest.HOME) { popUpTo(Dest.ONBOARDING) { inclusive = true } }
                }
            }
            composable(Dest.HOME) { HomeScreen(server) }
            composable(Dest.APPROVE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                approve(e.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
            }
            composable(Dest.DEVICES) { Text(stringResource(R.string.devices_title)) } // Task 8
            composable(Dest.SETTINGS) { SettingsScreen(server, version) }
        }
    }
}
