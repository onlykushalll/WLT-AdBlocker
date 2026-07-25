package com.wlt.adblocker.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wlt.adblocker.data.PrefsRepository
import com.wlt.adblocker.ui.screens.AppFirewallScreen
import com.wlt.adblocker.ui.screens.BlocklistsScreen
import com.wlt.adblocker.ui.screens.CustomRulesScreen
import com.wlt.adblocker.ui.screens.DashboardScreen
import com.wlt.adblocker.ui.screens.DnsLatencyScreen
import com.wlt.adblocker.ui.screens.ForensicsScreen
import com.wlt.adblocker.ui.screens.OnboardingScreen
import com.wlt.adblocker.ui.screens.QueryLogScreen
import com.wlt.adblocker.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Root composable for WLT-Adblocker.
 *
 * Owns the [NavHost] + bottom [NavigationBar] and performs first-launch
 * detection via [PrefsRepository.isFirstLaunch] (showing the Onboarding flow
 * before anything else if the user has never completed it).
 *
 * Bottom nav destinations (6 most-used screens):
 *   Home (Dashboard) / Queries / Lists / Rules / Firewall / Settings
 *
 * Additional routes (not in bottom nav):
 *   - Onboarding (full-screen, shown on first launch only)
 *   - DnsLatency (opened from Settings)
 *   - Forensics (opened from Dashboard)
 */
@Composable
fun WltApp() {
    val navController: NavHostController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // First-launch state. null = "still checking", true = "show onboarding",
    // false = "skip onboarding, go to dashboard".
    var firstLaunchState by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            val prefs = PrefsRepository(context)
            firstLaunchState = prefs.isFirstLaunch()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // While we're checking the first-launch flag, render nothing — avoids
    // a flash of the dashboard before the onboarding screen appears.
    if (firstLaunchState == null) {
        return
    }

    // Onboarding is full-screen — no bottom nav.
    if (firstLaunchState == true && currentRoute != Routes.ONBOARDING) {
        OnboardingScreen(
            onComplete = {
                coroutineScope.launch {
                    PrefsRepository(context).setFirstLaunchDone()
                    firstLaunchState = false
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            },
        )
        return
    }

    val showBottomBar = currentRoute in bottomBarRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(Routes.DASHBOARD) {
                                            saveState = true
                                        }
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        WltNavHost(
            navController = navController,
            innerPadding = innerPadding,
        )
    }
}

@Composable
private fun WltNavHost(
    navController: NavHostController,
    innerPadding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD,
        modifier = Modifier.padding(innerPadding),
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onOpenForensics = { navController.navigate(Routes.FORENSICS) },
            )
        }
        composable(Routes.QUERIES) {
            QueryLogScreen()
        }
        composable(Routes.LISTS) {
            BlocklistsScreen()
        }
        composable(Routes.RULES) {
            CustomRulesScreen()
        }
        composable(Routes.FIREWALL) {
            AppFirewallScreen()
        }
        composable(Routes.DNS_LATENCY) {
            DnsLatencyScreen()
        }
        composable(Routes.FORENSICS) {
            ForensicsScreen()
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenDnsLatency = { navController.navigate(Routes.DNS_LATENCY) },
            )
        }
    }
}

/** Route constants. Kept as a private object so the route strings live in
 *  one place — typos here would silently break navigation. */
private object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val QUERIES = "queries"
    const val LISTS = "lists"
    const val RULES = "rules"
    const val FIREWALL = "firewall"
    const val DNS_LATENCY = "dns_latency"
    const val FORENSICS = "forensics"
    const val SETTINGS = "settings"
}

private val bottomBarRoutes = setOf(
    Routes.DASHBOARD,
    Routes.QUERIES,
    Routes.LISTS,
    Routes.RULES,
    Routes.FIREWALL,
    Routes.SETTINGS,
)

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.DASHBOARD, "Home", Icons.Filled.Home),
    BottomNavItem(Routes.QUERIES, "Queries", Icons.AutoMirrored.Filled.List),
    BottomNavItem(Routes.LISTS, "Lists", Icons.Filled.Dns),
    BottomNavItem(Routes.RULES, "Rules", Icons.Filled.Rule),
    BottomNavItem(Routes.FIREWALL, "Firewall", Icons.Filled.Apps),
    BottomNavItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)
