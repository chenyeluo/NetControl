package com.netcontrol

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.notification.FirewallNotificationHelper
import com.netcontrol.di.AppContainer
import com.netcontrol.ui.apps.AppListFilter
import com.netcontrol.ui.apps.AppsScreen
import com.netcontrol.ui.dashboard.DashboardScreen
import com.netcontrol.ui.gkd.GkdScreen
import com.netcontrol.ui.groups.GroupsAndSchedulesScreen
import com.netcontrol.ui.networks.NetworksScreen
import com.netcontrol.ui.settings.AuditLogsScreen
import com.netcontrol.ui.settings.SettingsScreen
import com.netcontrol.ui.theme.NetControlTheme
import kotlinx.coroutines.launch

private enum class TopDestination(
    val route: String,
    val label: String,
    val title: String,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", "首页", "NetControl", Icons.Default.Shield),
    APPS("apps", "应用", "应用联网管理", Icons.Default.Apps),
    GROUPS("groups", "分组", "分组与定时规则", Icons.Default.FolderCopy),
    NETWORKS("networks", "网络", "网络档案与双 SIM", Icons.Default.NetworkWifi),
    SETTINGS("settings", "统计与设置", "统计、备份与设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val fallbackVpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val container = (application as NetControlApp).container
            lifecycleScope.launch {
                container.ruleEngine.switchPreferredBackend(BackendType.BLACKHOLE_VPN)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NetControlApp).container
        handleFallbackIntent(intent)
        setContent {
            NetControlTheme {
                NetControlMainApp(container = container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleFallbackIntent(intent)
    }

    private fun handleFallbackIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(FirewallNotificationHelper.EXTRA_REQUEST_BLACKHOLE_FALLBACK, false) != true) {
            return
        }
        intent.removeExtra(FirewallNotificationHelper.EXTRA_REQUEST_BLACKHOLE_FALLBACK)
        val container = (application as NetControlApp).container
        val prepareIntent = container.blackholeVpnBackend.prepareVpnIntent()
        if (prepareIntent != null) {
            fallbackVpnLauncher.launch(prepareIntent)
        } else {
            lifecycleScope.launch {
                container.ruleEngine.switchPreferredBackend(BackendType.BLACKHOLE_VPN)
            }
        }
    }
}

private const val ROUTE_GKD = "gkd"
private const val ROUTE_AUDIT_LOGS = "audit_logs"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetControlMainApp(container: AppContainer) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isGkdRoute = currentDestination?.route == ROUTE_GKD
    val isAuditLogsRoute = currentDestination?.route == ROUTE_AUDIT_LOGS
    val isSecondaryRoute = isGkdRoute || isAuditLogsRoute
    val activeTop = TopDestination.entries.firstOrNull { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    } ?: TopDestination.DASHBOARD

    var settingsRequestedSubTab by remember { mutableStateOf<Int?>(null) }
    var settingsSubTabNonce by remember { mutableIntStateOf(0) }
    var appsRequestedFilter by remember { mutableStateOf<AppListFilter?>(null) }
    var appsFilterNonce by remember { mutableIntStateOf(0) }

    fun navigateTop(dest: TopDestination) {
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isGkdRoute -> "GKD 跳过中心"
                            isAuditLogsRoute -> "审计日志与诊断轨迹"
                            else -> activeTop.title
                        }
                    )
                },
                navigationIcon = {
                    if (isSecondaryRoute) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!isSecondaryRoute) {
                        TextButton(onClick = {
                            navController.navigate(ROUTE_GKD) { launchSingleTop = true }
                        }) {
                            Icon(Icons.Default.Bolt, contentDescription = null)
                            Text("GKD跳过")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!isSecondaryRoute) {
                NavigationBar {
                    TopDestination.entries.forEach { dest ->
                        val selected = activeTop == dest
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTop(dest) },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.DASHBOARD.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopDestination.DASHBOARD.route) {
                DashboardScreen(
                    repository = container.repository,
                    settingsDataStore = container.settingsDataStore,
                    shizukuManager = container.shizukuManager,
                    vpnBackend = container.blackholeVpnBackend,
                    networkStateMonitor = container.networkStateMonitor,
                    ruleEngine = container.ruleEngine,
                    exactAlarmScheduler = container.exactAlarmScheduler,
                    onNavigateToNetworks = { navigateTop(TopDestination.NETWORKS) },
                    onNavigateToGroups = { navigateTop(TopDestination.GROUPS) },
                    onNavigateToBlockedApps = {
                        appsRequestedFilter = AppListFilter.BLOCKED
                        appsFilterNonce++
                        navigateTop(TopDestination.APPS)
                    },
                    onNavigateToPermissionsSettings = {
                        settingsRequestedSubTab = 2
                        settingsSubTabNonce++
                        navigateTop(TopDestination.SETTINGS)
                    },
                    onNavigateToAuditLogs = {
                        navController.navigate(ROUTE_AUDIT_LOGS) { launchSingleTop = true }
                    },
                    onNavigateToGkd = { navController.navigate(ROUTE_GKD) { launchSingleTop = true } }
                )
            }
            composable(ROUTE_GKD) {
                GkdScreen(
                    settingsDataStore = container.settingsDataStore,
                    gkdRepository = container.gkdSubscriptionRepository,
                    snapshotManager = container.gkdSnapshotManager
                )
            }
            composable(ROUTE_AUDIT_LOGS) {
                AuditLogsScreen(
                    repository = container.repository
                )
            }
            composable(TopDestination.APPS.route) {
                AppsScreen(
                    repository = container.repository,
                    ruleEngine = container.ruleEngine,
                    requestedFilter = appsRequestedFilter,
                    requestedFilterNonce = appsFilterNonce
                )
            }
            composable(TopDestination.GROUPS.route) {
                GroupsAndSchedulesScreen(
                    repository = container.repository,
                    ruleEngine = container.ruleEngine
                )
            }
            composable(TopDestination.NETWORKS.route) {
                NetworksScreen(
                    repository = container.repository,
                    settingsDataStore = container.settingsDataStore,
                    networkStateMonitor = container.networkStateMonitor,
                    ruleEngine = container.ruleEngine
                )
            }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen(
                    repository = container.repository,
                    settingsDataStore = container.settingsDataStore,
                    shizukuManager = container.shizukuManager,
                    vpnBackend = container.blackholeVpnBackend,
                    ruleEngine = container.ruleEngine,
                    exactAlarmScheduler = container.exactAlarmScheduler,
                    trafficStatsCollector = container.trafficStatsCollector,
                    backupManager = container.backupManager,
                    onNavigateToGkd = { navController.navigate(ROUTE_GKD) { launchSingleTop = true } },
                    onNavigateToAuditLogs = { navController.navigate(ROUTE_AUDIT_LOGS) { launchSingleTop = true } },
                    requestedSubTab = settingsRequestedSubTab,
                    requestedSubTabNonce = settingsSubTabNonce
                )
            }
        }
    }
}
