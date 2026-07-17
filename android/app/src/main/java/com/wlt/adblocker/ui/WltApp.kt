package com.wlt.adblocker.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wlt.adblocker.data.PrefsRepository
import com.wlt.adblocker.data.WltDataStore
import com.wlt.adblocker.ui.screens.AppFirewallScreen
import com.wlt.adblocker.ui.screens.BlocklistsScreen
import com.wlt.adblocker.ui.screens.CustomRulesScreen
import com.wlt.adblocker.ui.screens.DashboardScreen
import com.wlt.adblocker.ui.screens.DnsLatencyScreen
import com.wlt.adblocker.ui.screens.ForensicsScreen
import com.wlt.adblocker.ui.screens.OnboardingScreen
import com.wlt.adblocker.ui.screens.QueryLogScreen
import com.wlt.adblocker.ui.screens.SettingsScreen

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Onboarding : Screen("onboarding", "Welcome", Icons.Filled.Security)
    data object Dashboard : Screen("dashboard", "Home", Icons.Filled.Shield)
    data object QueryLog : Screen("querylog", "Queries", Icons.Filled.History)
    data object Blocklists : Screen("blocklists", "Lists", Icons.AutoMirrored.Filled.List)
    data object CustomRules : Screen("customrules", "Rules", Icons.Filled.Rule)
    data object AppFirewall : Screen("firewall", "Firewall", Icons.Filled.Block)
    data object DnsLatency : Screen("dns", "DNS Test", Icons.Filled.Dns)
    data object Forensics : Screen("forensics", "Forensics", Icons.Filled.Search)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

// Bottom nav shows 5 most-used; others accessible via routes
private val screens = listOf(Screen.Dashboard, Screen.QueryLog, Screen.Blocklists, Screen.CustomRules, Screen.AppFirewall, Screen.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WltApp(onToggleVpn: (Boolean) -> Unit) {
    val navController = rememberNavController()
    val vpnEnabled by WltDataStore.vpnEnabled.collectAsState(initial = false)
    val context = androidx.compose.ui.platform.LocalContext.current

    // First-launch detection — show onboarding
    var showOnboarding by remember { mutableStateOf(false) }
    var checkedFirstLaunch by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val isFirst = PrefsRepository.isFirstLaunch(context)
        if (isFirst) {
            showOnboarding = true
        }
        checkedFirstLaunch = true
    }

    if (!checkedFirstLaunch) {
        // Wait for first-launch check before rendering
        return
    }

    if (showOnboarding) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        OnboardingScreen(onFinish = {
            showOnboarding = false
            scope.launch {
                PrefsRepository.setFirstLaunchDone(context)
            }
        })
        return
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "WLT Adblocker",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (vpnEnabled) "●" else "○",
                            fontSize = 14.sp,
                            color = if (vpnEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title, fontSize = 10.sp) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(onFinish = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                })
            }
            composable(Screen.Dashboard.route) {
                DashboardScreen(vpnEnabled = vpnEnabled, onToggleVpn = onToggleVpn)
            }
            composable(Screen.QueryLog.route) { QueryLogScreen() }
            composable(Screen.Blocklists.route) { BlocklistsScreen() }
            composable(Screen.CustomRules.route) { CustomRulesScreen() }
            composable(Screen.AppFirewall.route) { AppFirewallScreen() }
            composable(Screen.DnsLatency.route) { DnsLatencyScreen() }
            composable(Screen.Forensics.route) { ForensicsScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
