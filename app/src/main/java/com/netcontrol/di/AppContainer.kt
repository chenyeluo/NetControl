package com.netcontrol.di

import android.content.Context
import com.netcontrol.core.alarm.ExactAlarmScheduler
import com.netcontrol.core.backend.FirewallCoordinator
import com.netcontrol.core.backend.shizuku.ShizukuManager
import com.netcontrol.core.backend.vpn.BlackholeVpnBackend
import com.netcontrol.core.engine.RuleEngine
import com.netcontrol.core.gkd.snapshot.GkdSnapshotManager
import com.netcontrol.core.gkd.subscription.GkdSubscriptionRepository
import com.netcontrol.core.network.NetworkStateMonitor
import com.netcontrol.data.backup.BackupManager
import com.netcontrol.data.local.AppDatabase
import com.netcontrol.data.prefs.SettingsDataStore
import com.netcontrol.data.repository.NetControlRepository
import com.netcontrol.data.scanner.AppScanner
import com.netcontrol.data.stats.TrafficStatsCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 轻量级手动依赖容器，避免重型 DI 框架带来的冷启动耗时与内存占用。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy {
        AppDatabase.create(appContext)
    }

    val settingsDataStore: SettingsDataStore by lazy {
        SettingsDataStore(appContext)
    }

    val appScanner: AppScanner by lazy {
        AppScanner(appContext)
    }

    val repository: NetControlRepository by lazy {
        NetControlRepository(
            database = database,
            settingsDataStore = settingsDataStore,
            appScanner = appScanner
        )
    }

    val shizukuManager: ShizukuManager by lazy {
        ShizukuManager(appContext).apply { initialize() }
    }

    val blackholeVpnBackend: BlackholeVpnBackend by lazy {
        BlackholeVpnBackend(appContext)
    }

    val firewallCoordinator: FirewallCoordinator by lazy {
        FirewallCoordinator(
            shizukuManager = shizukuManager,
            blackholeVpnBackend = blackholeVpnBackend
        )
    }

    val networkStateMonitor: NetworkStateMonitor by lazy {
        NetworkStateMonitor(
            context = appContext,
            appScope = appScope,
            database = database,
            settingsDataStore = settingsDataStore,
            shizukuManager = shizukuManager
        )
    }

    val exactAlarmScheduler: ExactAlarmScheduler by lazy {
        ExactAlarmScheduler(
            context = appContext,
            database = database,
            settingsDataStore = settingsDataStore
        )
    }

    val ruleEngine: RuleEngine by lazy {
        RuleEngine(
            context = appContext,
            appScope = appScope,
            database = database,
            settingsDataStore = settingsDataStore,
            networkStateMonitor = networkStateMonitor,
            shizukuManager = shizukuManager,
            coordinator = firewallCoordinator,
            exactAlarmScheduler = exactAlarmScheduler
        )
    }

    val trafficStatsCollector: TrafficStatsCollector by lazy {
        TrafficStatsCollector(
            context = appContext,
            database = database
        )
    }

    val backupManager: BackupManager by lazy {
        BackupManager(
            context = appContext,
            database = database,
            networkStateMonitor = networkStateMonitor,
            ruleEngine = ruleEngine
        )
    }

    val gkdSubscriptionRepository: GkdSubscriptionRepository by lazy {
        GkdSubscriptionRepository(database = database)
    }

    val gkdSnapshotManager: GkdSnapshotManager by lazy {
        GkdSnapshotManager(context = appContext)
    }
}
